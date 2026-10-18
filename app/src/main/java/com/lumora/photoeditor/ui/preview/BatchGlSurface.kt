package com.lumora.photoeditor.ui.preview

import android.content.Context
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumora.photoeditor.engine.GlEngine
import com.lumora.photoeditor.export.GlHost

/**
 * Headless GL surface for batch export: a 1dp [GLSurfaceView] that owns a
 * [GlEngine] on its GL thread and exposes a [GlHost], without any of the
 * editor preview's touch handling, overlays, or frame rendering. The export
 * pipeline ([GlEngine.renderExport]) renders into its own framebuffer, so
 * the surface never needs to draw anything — it only has to exist so EGL
 * can create a context.
 *
 * [onReady] fires once, on the UI thread, after [GlEngine.init] succeeds.
 * [onError] fires instead when GL initialization fails (the batch screen
 * then explains that batch export is unavailable rather than crashing).
 */
@Composable
fun BatchGlSurface(
    onReady: (GlEngine, GlHost) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = remember { mutableStateOf<BatchGlView?>(null) }
    // Stop the GL render thread when the screen leaves the composition so
    // it cannot outlive its surface (same rationale as EditorGlView).
    DisposableEffect(Unit) {
        onDispose { view.value?.onPause() }
    }
    AndroidView(
        factory = { ctx ->
            BatchGlView(ctx).also { v ->
                v.onReady = { engine -> onReady(engine, v.glHost) }
                v.onGlError = onError
                view.value = v
            }
        },
        // 1dp keeps the surface real (EGL needs an attached surface) while
        // staying visually invisible.
        modifier = modifier.size(1.dp),
    )
}

private class BatchGlView(context: Context) : GLSurfaceView(context) {

    val engine = GlEngine()

    /** Fired once on the UI thread after [GlEngine.init] succeeds. */
    var onReady: ((GlEngine) -> Unit)? = null

    /** Fired on the UI thread when GL initialization fails. */
    var onGlError: ((String) -> Unit)? = null

    @Volatile
    private var reported = false

    val glHost = object : GlHost {
        override fun queueGl(task: () -> Unit) {
            queueEvent(task)
        }
    }

    init {
        setEGLContextClientVersion(3)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                try {
                    engine.init()
                } catch (e: Exception) {
                    android.util.Log.e("BatchGlView", "GL init failed", e)
                    post { onGlError?.invoke(e.message ?: "3D renderer failed to start") }
                    return
                }
                if (!reported) {
                    reported = true
                    post { onReady?.invoke(engine) }
                }
            }

            override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) = Unit

            override fun onDrawFrame(gl: GL10?) = Unit
        })
        // The surface never draws: frames are only needed for the EGL
        // context that the export pipeline renders into offscreen.
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
