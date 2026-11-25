package app.oriel.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.oriel.core.model.Video
import app.oriel.playback.PlayerConnection
import app.oriel.ui.components.RemoteImage
import app.oriel.util.Thumbnails

/**
 * The picture: the service's own player drawn into a TextureView, so the
 * frame can be clipped to the floating window's rounded corners and fly between
 * the two without a black flash. The thumbnail stands in until the first frame.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(connection: PlayerConnection, video: Video?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit, audioOnly: Boolean = false) {
    val player by connection.surfacePlayer.collectAsState()
    // Which video the picture last showed a frame of. The frame is kept across a re-prepare
    // (no flash on a recovered error), but a different video shows its own poster, not the
    // last one's final frame, until its first frame arrives.
    // A fresh surface has nothing to keep: ContentFrame's own shutter covers it until a frame lands.
    var framed by remember(player) { mutableStateOf(player?.takeIf { it.playbackState == Player.STATE_READY }?.currentMediaItem?.mediaId) }
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { framed = p.currentMediaItem?.mediaId }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (!audioOnly && player != null) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = contentScale,
                keepContentOnReset = true,
                shutter = { if (video != null) Poster(video) },
            )
            if (video != null && framed != video.id) Poster(video)
        } else if (video != null) {
            Poster(video)
        }
    }
}

@Composable
private fun Poster(video: Video) {
    RemoteImage(Thumbnails.frame(video.id, 720), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), border = false)
}
