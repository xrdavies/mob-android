package com.xrdavies.mob.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoSink
import java.util.UUID

/**
 * Renders the [StreamSession]'s video into a TextureView (so graphicsLayer zoom and pan apply). The
 * picture fills the view: size the view to the stream's aspect ratio. Each view reports its size,
 * and the session fits the stream to the largest one showing it.
 */
@Composable
fun StreamVideo(session: StreamSession, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context -> StreamTextureView(context, session) },
        modifier = modifier,
        onRelease = { it.detach() },
    )
}

private class StreamTextureView(context: Context, private val session: StreamSession) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private val key = UUID.randomUUID().toString()
    private var renderer: EglRenderer? = null
    private var sink: VideoSink? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        detach()
        val r = object : EglRenderer(key) {}
        r.init(session.eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        r.createEglSurface(surface)
        // The same sink instance is added and removed, so no frame reaches a released renderer.
        val s = VideoSink { frame -> r.onFrame(frame) }
        renderer = r
        sink = s
        session.addSink(s)
        session.reportView(key, width to height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        session.reportView(key, width to height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detach()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun detach() {
        sink?.let(session::removeSink)
        sink = null
        renderer?.release() // blocks until the render thread has let go of the surface
        renderer = null
        session.reportView(key, null)
    }
}
