package app.oriel.security

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.Video
import app.oriel.playback.MediaItems
import app.oriel.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What another app connected to the media session can get the player to open. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class ControllerItemTest {
    private val id = "aaaaaaaaaaa"

    @Test fun anyUriIsReplacedWithOrielsOwn() {
        val item = MediaItem.Builder().setMediaId(id).setUri(Uri.parse("https://example.com/x.mp4")).build()
        assertEquals("oriel", PlaybackService.controllerItem(item, trusted = false, height = 720)?.localConfiguration?.uri?.scheme)
        assertEquals("oriel", PlaybackService.controllerItem(item, trusted = true, height = 720)?.localConfiguration?.uri?.scheme)
    }

    @Test fun onlyVideoIdsPlay() {
        listOf("", "short", "../../etc/passwd", "aaaaaaaaaaa?h=1", "file:///sdcard/x", "aaaaaaaaaaa/b").forEach {
            assertNull(it, PlaybackService.controllerItem(MediaItem.Builder().setMediaId(it).build(), trusted = false, height = null))
        }
    }

    @Test fun otherAppsDontWriteTitlesIntoHistory() {
        val forged = Video(id, "Forged title")
        val item = MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Forged title").setExtras(Bundle().apply {
                putString(MediaItems.EXTRA_VIDEO, app.oriel.util.AppJson.encodeToString(Video.serializer(), forged))
            }).build())
            .build()
        val rebuilt = PlaybackService.controllerItem(item, trusted = false, height = null)!!
        assertEquals("", MediaItems.video(rebuilt)?.title)
        // A forged id inside the extras is checked too, for trusted controllers.
        val badExtras = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putString(MediaItems.EXTRA_VIDEO, app.oriel.util.AppJson.encodeToString(Video.serializer(), Video("../x", "t")))
        }).build()).build()
        assertNull(PlaybackService.controllerItem(badExtras, trusted = true, height = null))
    }

    @Test fun orielsOwnItemsPassThrough() {
        val own = MediaItems.build(Video(id, "Mine"), 1080)
        assertSame(own, PlaybackService.controllerItem(own, trusted = true, height = 720))
    }
}
