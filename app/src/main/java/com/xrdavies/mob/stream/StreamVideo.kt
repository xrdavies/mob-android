package com.xrdavies.mob.stream

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoSink
import java.util.UUID

/**
 * Renders the [StreamSession]'s video track into a TextureView, preserving graphicsLayer transforms
 * (pinch zoom, pan). Call [key] once per instance; it tells the session which view's size this is.
 */
@Composable
fun StreamVideo(session: StreamSession, modifier: Modifier = Modifier, key: String = remember { UUID.randomUUID().toString() }) {
    DisposableEffect(session, key) {
        val renderer = object : EglRenderer(key) {}
        renderer.init(session.eglContext, intArrayOf(), GlRectDrawer())
        val sink = VideoSink { frame -> renderer.onFrame(frame) }
        session.addSink(sink)
        onDispose {
            session.removeSink(sink)
            session.reportView(key, null)
            renderer.release()
        }
    }
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var renderer: EglRenderer? = null
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        val r = session.eglContext.let { object : EglRenderer("$key-render") {} }
                        r.init(session.eglContext, intArrayOf(), GlRectDrawer())
                        r.createEglSurface(surface)
                        renderer = r
                        session.addSink(VideoSink { frame -> r.onFrame(frame) })
                        session.reportView(key, width to height)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        session.reportView(key, width to height)
                    }
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        renderer?.let { r ->
                            session.removeSink(VideoSink { frame -> r.onFrame(frame) })
                            r.releaseEglSurface { r.release() }
                        }
                        renderer = null
                        session.reportView(key, null)
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier,
    )
}
