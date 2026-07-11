package com.izzy2lost.neshd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * On-screen NES Max-style touch controller.
 *
 * The Android key mappings also expose TurboA and TurboB, so the small gray
 * buttons use the NES core's native autofire logic instead of local timers.
 */
class TouchControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val actionPlateRect = RectF()
    private val btnARect = RectF()
    private val btnBRect = RectF()
    private val btnABRect = RectF()
    private val btnTurboARect = RectF()
    private val btnTurboBRect = RectF()
    private val btnStartRect = RectF()
    private val btnSelectRect = RectF()

    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadOuterRadius = 0f
    private var dpadHitRadius = 0f
    private var dpadDeadZone = 0f
    private var density = 1f

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val dpadBackplatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 139, 136, 128)
        style = Paint.Style.FILL
    }
    private val dpadBackplateStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 235, 235, 230)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 8, 10, 12)
        style = Paint.Style.FILL
    }
    private val blackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(214, 35, 46)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSkewX = -0.18f
    }
    private val buttonLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 0, 0)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSkewX = -0.18f
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(78, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val pressed = mutableSetOf<Int>()
    private val pointerButtons = mutableMapOf<Int, Set<Int>>()

    fun releaseAllButtons() {
        for (btn in pressed) NativeLib.setButtonState(btn, false)
        pressed.clear()
        pointerButtons.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        releaseAllButtons()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)

        density = resources.displayMetrics.density
        val width = w.toFloat()
        val height = h.toFloat()
        val isPortrait = height > width
        val bottomPad = maxOf(10f * density, height * 0.015f)
        val controlBandHeight = min(height * 0.48f, width * 0.31f)
        val leftInset = maxOf(width * 0.065f, 44f * density)
        val rightInset = maxOf(width * 0.07f, 48f * density)

        val minDpadSize = (if (isPortrait) 96f else 112f) * density
        val dpadSize = min(controlBandHeight * 0.58f, width * 0.16f).coerceAtLeast(minDpadSize)
        dpadOuterRadius = dpadSize * 0.5f
        dpadHitRadius = dpadOuterRadius * 1.18f
        dpadDeadZone = dpadOuterRadius * 0.27f
        val portraitControlLift = if (isPortrait) min(height * 0.085f, dpadOuterRadius * 1.80f) else 0f
        dpadCx = leftInset + dpadOuterRadius
        dpadCy = height - bottomPad - dpadOuterRadius * 1.05f - portraitControlLift

        val actionRadius = (dpadOuterRadius * 0.40f).coerceAtLeast(32f * density)
        val turboRadius = actionRadius * 0.52f
        val turboGap = actionRadius * 0.78f
        val btnACx = width - rightInset - actionRadius
        val btnBCx = btnACx - actionRadius * 3.10f
        val turboACx = btnACx + actionRadius * 0.24f
        val rightClusterBottomPad = bottomPad + actionRadius * 0.55f
        val turboBCy = height - rightClusterBottomPad - turboRadius - portraitControlLift
        val turboACy = turboBCy - actionRadius * 0.50f
        val btnBCy = turboACy - turboRadius - turboGap - actionRadius
        val btnACy = btnBCy - actionRadius * 0.50f
        btnBRect.setCircle(btnBCx, btnBCy, actionRadius)
        btnARect.setCircle(btnACx, btnACy, actionRadius)

        // Touch screens cannot always report two nearby fingers reliably. Provide
        // a dedicated bridge between the face buttons that presses A and B as one
        // chord, without changing the normal hit areas for either button.
        val chordCx = (btnACx + btnBCx) * 0.5f
        val chordCy = (btnACy + btnBCy) * 0.5f
        val chordRadius = actionRadius * 0.52f
        btnABRect.setCircle(chordCx, chordCy, chordRadius)

        actionPlateRect.set(
            btnBRect.left - actionRadius * 0.44f,
            btnARect.top - actionRadius * 0.30f,
            btnARect.right + actionRadius * 0.46f,
            btnBRect.bottom + actionRadius * 0.42f
        )

        btnTurboBRect.setCircle(btnBCx, turboBCy, turboRadius)
        btnTurboARect.setCircle(turboACx, turboACy, turboRadius)
        val smallButtonW = (dpadOuterRadius * 0.95f).coerceAtLeast(76f * density)
        val smallButtonH = (dpadOuterRadius * 0.26f).coerceAtLeast(28f * density)
        val smallGap = smallButtonW * 0.36f
        val smallY = height - bottomPad - smallButtonH * 1.05f
        val centerX = width * 0.5f
        btnSelectRect.set(
            centerX - smallGap * 0.5f - smallButtonW,
            smallY - smallButtonH,
            centerX - smallGap * 0.5f,
            smallY
        )
        btnStartRect.set(
            centerX + smallGap * 0.5f,
            smallY - smallButtonH,
            centerX + smallGap * 0.5f + smallButtonW,
            smallY
        )

        val labelSize = (dpadOuterRadius * 0.18f).coerceAtLeast(13f * density)
        labelPaint.textSize = labelSize
        buttonLabelPaint.textSize = labelSize
        dpadBackplateStrokePaint.strokeWidth = 2f * density
        blackStrokePaint.strokeWidth = 2f * density
        bevelPaint.strokeWidth = 2f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawDpad(canvas)
        drawActionCluster(canvas)
        drawSystemButtons(canvas)
        drawTurboButtons(canvas)
    }

    private fun drawDpad(canvas: Canvas) {
        val anyPressed = listOf(
            NativeLib.BTN_UP,
            NativeLib.BTN_DOWN,
            NativeLib.BTN_LEFT,
            NativeLib.BTN_RIGHT
        ).any { it in pressed }

        val backplateRadius = dpadOuterRadius * 1.12f
        shadowPaint.color = Color.argb(80, 0, 0, 0)
        canvas.drawCircle(dpadCx + 3f * density, dpadCy + 5f * density, backplateRadius, shadowPaint)
        dpadBackplatePaint.shader = RadialGradient(
            dpadCx - backplateRadius * 0.30f,
            dpadCy - backplateRadius * 0.35f,
            backplateRadius * 1.25f,
            intArrayOf(
                Color.argb(165, 185, 183, 174),
                Color.argb(150, 128, 125, 118),
                Color.argb(155, 82, 82, 78)
            ),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(dpadCx, dpadCy, backplateRadius, dpadBackplatePaint)
        dpadBackplatePaint.shader = null
        canvas.drawCircle(dpadCx, dpadCy, backplateRadius, dpadBackplateStrokePaint)

        shadowPaint.color = Color.argb(105, 0, 0, 0)
        canvas.drawCircle(dpadCx + 3f * density, dpadCy + 5f * density, dpadOuterRadius * 1.03f, shadowPaint)
        shadowPaint.color = Color.argb(95, 0, 0, 0)

        blackPaint.shader = RadialGradient(
            dpadCx - dpadOuterRadius * 0.25f,
            dpadCy - dpadOuterRadius * 0.30f,
            dpadOuterRadius * 1.12f,
            intArrayOf(
                Color.argb(235, 34, 39, 43),
                Color.argb(240, 7, 9, 12),
                Color.argb(245, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(dpadCx, dpadCy, dpadOuterRadius, blackPaint)
        blackPaint.shader = null

        blackStrokePaint.color = Color.argb(220, 0, 0, 0)
        canvas.drawCircle(dpadCx, dpadCy, dpadOuterRadius, blackStrokePaint)

        repeat(8) { index ->
            val angle = Math.toRadians((index * 45f + 22.5f).toDouble())
            val startX = dpadCx + cos(angle).toFloat() * dpadOuterRadius * 0.74f
            val startY = dpadCy + sin(angle).toFloat() * dpadOuterRadius * 0.74f
            val endX = dpadCx + cos(angle).toFloat() * dpadOuterRadius * 0.95f
            val endY = dpadCy + sin(angle).toFloat() * dpadOuterRadius * 0.95f
            canvas.drawLine(startX, startY, endX, endY, bevelPaint)
        }

        drawDirectionTriangle(canvas, -90f, NativeLib.BTN_UP in pressed)
        drawDirectionTriangle(canvas, 90f, NativeLib.BTN_DOWN in pressed)
        drawDirectionTriangle(canvas, 180f, NativeLib.BTN_LEFT in pressed)
        drawDirectionTriangle(canvas, 0f, NativeLib.BTN_RIGHT in pressed)

        val centerRadius = dpadOuterRadius * 0.43f
        drawGlossyCircle(
            canvas = canvas,
            cx = dpadCx,
            cy = dpadCy + if (anyPressed) 1.5f * density else 0f,
            radius = centerRadius,
            baseColor = Color.rgb(218, 31, 43),
            pressedColor = Color.rgb(176, 20, 30),
            isPressed = anyPressed
        )
        canvas.drawCircle(dpadCx, dpadCy, centerRadius * 1.02f, bevelPaint)
    }

    private fun drawDirectionTriangle(canvas: Canvas, degrees: Float, isPressed: Boolean) {
        val angle = Math.toRadians(degrees.toDouble())
        val tangentAngle = angle + Math.PI / 2.0
        val tipRadius = dpadOuterRadius * 0.81f
        val baseRadius = dpadOuterRadius * 0.58f
        val halfBase = dpadOuterRadius * 0.11f
        val tipX = dpadCx + cos(angle).toFloat() * tipRadius
        val tipY = dpadCy + sin(angle).toFloat() * tipRadius
        val baseX = dpadCx + cos(angle).toFloat() * baseRadius
        val baseY = dpadCy + sin(angle).toFloat() * baseRadius
        val tx = cos(tangentAngle).toFloat() * halfBase
        val ty = sin(tangentAngle).toFloat() * halfBase

        val triangle = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseX + tx, baseY + ty)
            lineTo(baseX - tx, baseY - ty)
            close()
        }
        blackPaint.color = if (isPressed) Color.rgb(185, 24, 34) else Color.argb(210, 20, 24, 28)
        canvas.drawPath(triangle, blackPaint)
        blackPaint.color = Color.argb(225, 8, 10, 12)
    }

    private fun drawActionCluster(canvas: Canvas) {
        canvas.save()
        canvas.rotate(-13f, actionPlateRect.centerX(), actionPlateRect.centerY())
        canvas.drawRoundRect(actionPlateRect, actionPlateRect.height() * 0.52f, actionPlateRect.height() * 0.52f, shadowPaint)
        blackPaint.shader = LinearGradient(
            actionPlateRect.left,
            actionPlateRect.top,
            actionPlateRect.right,
            actionPlateRect.bottom,
            Color.argb(235, 34, 38, 42),
            Color.argb(240, 4, 5, 7),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(actionPlateRect, actionPlateRect.height() * 0.52f, actionPlateRect.height() * 0.52f, blackPaint)
        blackPaint.shader = null
        canvas.drawRoundRect(actionPlateRect, actionPlateRect.height() * 0.52f, actionPlateRect.height() * 0.52f, bevelPaint)
        canvas.restore()

        drawGlossyCircle(canvas, btnBRect.centerX(), btnBRect.centerY(), btnBRect.width() * 0.5f, Color.rgb(219, 29, 42), Color.rgb(171, 19, 29), NativeLib.BTN_B in pressed)
        drawGlossyCircle(canvas, btnARect.centerX(), btnARect.centerY(), btnARect.width() * 0.5f, Color.rgb(219, 29, 42), Color.rgb(171, 19, 29), NativeLib.BTN_A in pressed)

        val chordPressed = NativeLib.BTN_A in pressed && NativeLib.BTN_B in pressed
        drawGlossyCircle(
            canvas,
            btnABRect.centerX(),
            btnABRect.centerY(),
            btnABRect.width() * 0.5f,
            Color.rgb(126, 128, 128),
            Color.rgb(74, 75, 75),
            chordPressed
        )

        buttonLabelPaint.textSize = btnBRect.width() * 0.40f
        drawCenteredButtonLabel(canvas, btnBRect, "B", NativeLib.BTN_B)
        drawCenteredButtonLabel(canvas, btnARect, "A", NativeLib.BTN_A)
        buttonLabelPaint.textSize = btnABRect.width() * 0.24f
        drawCenteredButtonLabel(canvas, btnABRect, "A+B", if (chordPressed) NativeLib.BTN_A else -1)
    }

    private fun drawSystemButtons(canvas: Canvas) {
        drawPillButton(canvas, btnSelectRect, NativeLib.BTN_SELECT in pressed)
        drawPillButton(canvas, btnStartRect, NativeLib.BTN_START in pressed)
        drawPillLabel(canvas, btnSelectRect, "SELECT", NativeLib.BTN_SELECT in pressed)
        drawPillLabel(canvas, btnStartRect, "START", NativeLib.BTN_START in pressed)
    }

    private fun drawTurboButtons(canvas: Canvas) {
        drawGlossyCircle(canvas, btnTurboBRect.centerX(), btnTurboBRect.centerY(), btnTurboBRect.width() * 0.5f, Color.rgb(99, 101, 101), Color.rgb(67, 68, 68), NativeLib.BTN_TURBO_B in pressed)
        drawGlossyCircle(canvas, btnTurboARect.centerX(), btnTurboARect.centerY(), btnTurboARect.width() * 0.5f, Color.rgb(99, 101, 101), Color.rgb(67, 68, 68), NativeLib.BTN_TURBO_A in pressed)

        labelPaint.textSize = dpadOuterRadius * 0.15f
        val labelX = (btnTurboBRect.centerX() + btnTurboARect.centerX()) * 0.5f
        val labelCenterY = (btnTurboBRect.centerY() + btnTurboARect.centerY()) * 0.5f
        val metrics = labelPaint.fontMetrics
        val labelY = labelCenterY - (metrics.ascent + metrics.descent) * 0.5f
        canvas.save()
        canvas.rotate(-15f, labelX, labelCenterY)
        canvas.drawText("TURBO", labelX, labelY, labelPaint)
        canvas.restore()
    }

    private fun drawPillLabel(canvas: Canvas, rect: RectF, label: String, isPressed: Boolean) {
        val offset = if (isPressed) 1.5f * density else 0f
        val centerY = rect.centerY() + offset
        labelPaint.textSize = rect.height() * 0.42f
        val metrics = labelPaint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) * 0.5f

        canvas.save()
        canvas.rotate(-14f, rect.centerX(), centerY)
        canvas.drawText(label, rect.centerX(), baseline, labelPaint)
        canvas.restore()
    }

    private fun drawCenteredButtonLabel(canvas: Canvas, rect: RectF, label: String, btnId: Int) {
        val offset = if (btnId in pressed) 2f * density else 0f
        val centerY = rect.centerY() + offset
        val metrics = buttonLabelPaint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) * 0.5f
        canvas.drawText(label, rect.centerX(), baseline, buttonLabelPaint)
    }

    private fun drawPillButton(canvas: Canvas, rect: RectF, isPressed: Boolean) {
        val offset = if (isPressed) 1.5f * density else 0f
        val drawRect = RectF(rect)
        drawRect.offset(0f, offset)

        canvas.save()
        canvas.rotate(-14f, drawRect.centerX(), drawRect.centerY())
        shadowPaint.color = Color.argb(115, 0, 0, 0)
        val shadowRect = RectF(drawRect)
        shadowRect.offset(2f * density, 3f * density)
        canvas.drawRoundRect(shadowRect, drawRect.height() * 0.5f, drawRect.height() * 0.5f, shadowPaint)
        shadowPaint.color = Color.argb(95, 0, 0, 0)

        val topColor = if (isPressed) Color.rgb(126, 129, 129) else Color.rgb(213, 216, 214)
        val bottomColor = if (isPressed) Color.rgb(66, 67, 67) else Color.rgb(131, 134, 134)
        blackPaint.shader = LinearGradient(
            drawRect.left,
            drawRect.top,
            drawRect.left,
            drawRect.bottom,
            topColor,
            bottomColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(drawRect, drawRect.height() * 0.5f, drawRect.height() * 0.5f, blackPaint)
        blackPaint.shader = null
        canvas.drawRoundRect(drawRect, drawRect.height() * 0.5f, drawRect.height() * 0.5f, blackStrokePaint)

        val shine = RectF(
            drawRect.left + drawRect.width() * 0.14f,
            drawRect.top + drawRect.height() * 0.15f,
            drawRect.right - drawRect.width() * 0.18f,
            drawRect.centerY()
        )
        canvas.drawOval(shine, highlightPaint)
        canvas.restore()
    }

    private fun drawGlossyCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        baseColor: Int,
        pressedColor: Int,
        isPressed: Boolean
    ) {
        val drawCy = cy + if (isPressed) 2f * density else 0f
        shadowPaint.color = Color.argb(120, 0, 0, 0)
        canvas.drawCircle(cx + 2f * density, drawCy + 3f * density, radius * 1.04f, shadowPaint)
        shadowPaint.color = Color.argb(95, 0, 0, 0)

        val color = if (isPressed) pressedColor else baseColor
        blackPaint.shader = RadialGradient(
            cx - radius * 0.35f,
            drawCy - radius * 0.45f,
            radius * 1.45f,
            intArrayOf(
                lighten(color, 42),
                color,
                darken(color, 58)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, drawCy, radius, blackPaint)
        blackPaint.shader = null
        canvas.drawCircle(cx, drawCy, radius, blackStrokePaint)

        highlightPaint.color = Color.argb(if (isPressed) 48 else 88, 255, 255, 255)
        canvas.drawOval(
            RectF(
                cx - radius * 0.36f,
                drawCy - radius * 0.58f,
                cx + radius * 0.38f,
                drawCy - radius * 0.08f
            ),
            highlightPaint
        )
        highlightPaint.color = Color.argb(78, 255, 255, 255)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_CANCEL -> pointerButtons.clear()

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                updatePointers(event, event.actionIndex)
                pointerButtons.remove(event.getPointerId(event.actionIndex))
            }

            else -> updatePointers(event)
        }

        // A button remains down while any pointer is on it. Keeping ownership per
        // pointer is important for chords such as A+B and also prevents lifting one
        // finger from releasing a button that another finger is still holding.
        val newPressed = mutableSetOf<Int>()
        for (buttons in pointerButtons.values) newPressed.addAll(buttons)
        val toRelease = pressed - newPressed
        val toPress = newPressed - pressed

        for (btn in toRelease) NativeLib.setButtonState(btn, false)
        for (btn in toPress) NativeLib.setButtonState(btn, true)

        pressed.clear()
        pressed.addAll(newPressed)

        invalidate()
        return true
    }

    private fun updatePointers(event: MotionEvent, excludedIndex: Int = -1) {
        val activePointerIds = mutableSetOf<Int>()
        for (i in 0 until event.pointerCount) {
            if (i == excludedIndex) continue

            val pointerId = event.getPointerId(i)
            activePointerIds.add(pointerId)
            pointerButtons[pointerId] = buildSet {
                collectHits(event.getX(i), event.getY(i), this)
            }
        }

        // MOVE events describe every currently active pointer. Drop any stale
        // ownership defensively in case Android cancelled a pointer sequence.
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            pointerButtons.keys.retainAll(activePointerIds)
        }
    }

    private fun collectHits(x: Float, y: Float, out: MutableSet<Int>) {
        val dx = x - dpadCx
        val dy = y - dpadCy
        val distance = hypot(dx, dy)
        if (distance <= dpadHitRadius && distance > dpadDeadZone) {
            val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (angle <= -25f && angle >= -155f) out.add(NativeLib.BTN_UP)
            if (angle >= 25f && angle <= 155f) out.add(NativeLib.BTN_DOWN)
            if (angle <= -115f || angle >= 115f) out.add(NativeLib.BTN_LEFT)
            if (angle >= -65f && angle <= 65f) out.add(NativeLib.BTN_RIGHT)
        }

        if (btnABRect.contains(x, y)) {
            out.add(NativeLib.BTN_A)
            out.add(NativeLib.BTN_B)
        } else {
            if (btnARect.contains(x, y)) out.add(NativeLib.BTN_A)
            if (btnBRect.contains(x, y)) out.add(NativeLib.BTN_B)
        }
        if (btnTurboARect.contains(x, y)) out.add(NativeLib.BTN_TURBO_A)
        if (btnTurboBRect.contains(x, y)) out.add(NativeLib.BTN_TURBO_B)
        if (btnStartRect.contains(x, y)) out.add(NativeLib.BTN_START)
        if (btnSelectRect.contains(x, y)) out.add(NativeLib.BTN_SELECT)
    }

    private fun RectF.setCircle(cx: Float, cy: Float, radius: Float) {
        set(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    private fun lighten(color: Int, amount: Int): Int {
        return Color.rgb(
            (Color.red(color) + amount).coerceAtMost(255),
            (Color.green(color) + amount).coerceAtMost(255),
            (Color.blue(color) + amount).coerceAtMost(255)
        )
    }

    private fun darken(color: Int, amount: Int): Int {
        return Color.rgb(
            (Color.red(color) - amount).coerceAtLeast(0),
            (Color.green(color) - amount).coerceAtLeast(0),
            (Color.blue(color) - amount).coerceAtLeast(0)
        )
    }
}
