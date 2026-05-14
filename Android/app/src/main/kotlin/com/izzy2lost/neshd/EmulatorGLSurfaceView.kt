package com.izzy2lost.neshd

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 surface that presents NES frames from the native core.
 * The renderer polls the C++ side each vsync for a new pixel buffer.
 */
class EmulatorGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var viewWidth  = 0
    private var viewHeight = 0

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(NesRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private inner class NesRenderer : Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            NativeLib.glInit()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth  = width
            viewHeight = height
        }

        override fun onDrawFrame(gl: GL10?) {
            NativeLib.glDrawFrame(viewWidth, viewHeight)
        }
    }

    override fun onPause() {
        queueEvent { NativeLib.glDestroy() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // glInit is called from onSurfaceCreated after resume
    }
}
