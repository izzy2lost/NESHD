package com.izzy2lost.neshd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import java.util.WeakHashMap

/**
 * Carousel cover: draws the art fit into the top of the view, a soft drop shadow shaped
 * like the art, and a fading mirror of it in the bottom [REFLECTION_FRACTION]. Cover art
 * is box renders on transparent backgrounds, so both follow the art's alpha, not its
 * bounding box.
 */
class ReflectedCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    companion object {
        /** Reflection height as a fraction of the cover height. */
        const val REFLECTION_FRACTION = 0.3f
        private const val REFLECTION_START_ALPHA = 0.35f
        private const val SHADOW_ALPHA = 0.45f
        /** Shadows are built from a downscaled copy of the art; they're blurry anyway. */
        private const val SHADOW_SAMPLE = 4
        private const val SHADOW_BLUR_PX = 4f
        private const val SHADOW_OFFSET_DP = 6f

        private class ArtMetrics(
            val shadow: Bitmap,
            val shadowLeft: Float,
            val shadowTop: Float,
            /** Lowest opaque row of the art, as a fraction of its height. */
            val contentBottom: Float,
            val sampleWidth: Int
        )

        // Bitmaps are shared through the cover memory cache, so key on the bitmap.
        private val metricsCache = WeakHashMap<Bitmap, ArtMetrics>()

        private fun metricsFor(source: Bitmap): ArtMetrics? {
            metricsCache[source]?.let { return it }
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                source.config == Bitmap.Config.HARDWARE
            ) {
                source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else {
                source
            }
            val w = maxOf(1, bitmap.width / SHADOW_SAMPLE)
            val h = maxOf(1, bitmap.height / SHADOW_SAMPLE)
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)

            val row = IntArray(w)
            var bottomRow = h - 1
            scan@ while (bottomRow > 0) {
                small.getPixels(row, 0, w, 0, bottomRow, w, 1)
                for (pixel in row) {
                    if ((pixel ushr 24) > 48) break@scan
                }
                bottomRow--
            }

            val offset = IntArray(2)
            val blur = Paint().apply {
                maskFilter = BlurMaskFilter(SHADOW_BLUR_PX, BlurMaskFilter.Blur.NORMAL)
            }
            val shadow = small.extractAlpha(blur, offset)
            if (small !== bitmap) small.recycle()
            return ArtMetrics(
                shadow,
                offset[0].toFloat(),
                offset[1].toFloat(),
                (bottomRow + 1f) / h,
                w
            ).also { metricsCache[source] = it }
        }
    }

    private val drawMatrix = Matrix()
    private val coverRect = RectF()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = (SHADOW_ALPHA * 255f).toInt() shl 24
    }
    // No anti-aliasing: a partially covered edge pixel would keep part of the unfaded art.
    private val fadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var fadeTop = Float.NaN
    private var fadeHeight = Float.NaN

    /** Fits the art (fitCenter, bottom-aligned) into the area above the reflection. */
    private fun updateCoverRect() {
        val d = drawable
        val coverAreaHeight = height / (1f + REFLECTION_FRACTION)
        if (d == null || width <= 0 || coverAreaHeight <= 0f) {
            coverRect.setEmpty()
            return
        }
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (iw <= 0f || ih <= 0f) {
            coverRect.set(0f, 0f, width.toFloat(), coverAreaHeight)
        } else {
            val scale = minOf(width / iw, coverAreaHeight / ih)
            val w = iw * scale
            val h = ih * scale
            // Bottom-aligned so the reflection always meets the cover's lower edge.
            val left = (width - w) / 2f
            coverRect.set(left, coverAreaHeight - h, left + w, coverAreaHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable ?: return
        updateCoverRect()
        if (coverRect.isEmpty) return
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: coverRect.width().toInt()
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: coverRect.height().toInt()
        d.setBounds(0, 0, iw, ih)
        drawMatrix.setRectToRect(
            RectF(0f, 0f, iw.toFloat(), ih.toFloat()),
            coverRect,
            Matrix.ScaleToFit.FILL
        )
        val metrics = (d as? BitmapDrawable)?.bitmap?.let(::metricsFor)

        if (metrics != null) {
            val scale = coverRect.width() / metrics.sampleWidth
            canvas.save()
            canvas.translate(
                coverRect.left,
                coverRect.top + SHADOW_OFFSET_DP * resources.displayMetrics.density
            )
            canvas.scale(scale, scale)
            canvas.drawBitmap(metrics.shadow, metrics.shadowLeft, metrics.shadowTop, shadowPaint)
            canvas.restore()
        }

        canvas.save()
        canvas.concat(drawMatrix)
        d.draw(canvas)
        canvas.restore()

        // Mirror about the bottom of the box itself, not the transparent margin under it.
        val reflectionTop =
            coverRect.top + coverRect.height() * (metrics?.contentBottom ?: 1f)
        val reflectionHeight = minOf(coverRect.height() * REFLECTION_FRACTION, height - reflectionTop)
        if (reflectionHeight <= 0f) return
        val reflectionBottom = reflectionTop + reflectionHeight
        val layer = canvas.saveLayer(
            coverRect.left, reflectionTop, coverRect.right, reflectionBottom, null
        )
        canvas.clipRect(coverRect.left, reflectionTop, coverRect.right, reflectionBottom)
        canvas.save()
        canvas.translate(0f, 2f * reflectionTop)
        canvas.scale(1f, -1f)
        canvas.concat(drawMatrix)
        d.draw(canvas)
        canvas.restore()
        if (reflectionTop != fadeTop || reflectionHeight != fadeHeight) {
            fadeTop = reflectionTop
            fadeHeight = reflectionHeight
            val startAlpha = (REFLECTION_START_ALPHA * 255f).toInt() shl 24
            fadePaint.shader = LinearGradient(
                0f, reflectionTop, 0f, reflectionBottom,
                startAlpha, 0, Shader.TileMode.CLAMP
            )
        }
        // Overdrawn past the layer so its fractional edges are fully faded too.
        canvas.drawRect(
            coverRect.left - 2f, reflectionTop, coverRect.right + 2f, reflectionBottom + 2f,
            fadePaint
        )
        canvas.restoreToCount(layer)
    }
}
