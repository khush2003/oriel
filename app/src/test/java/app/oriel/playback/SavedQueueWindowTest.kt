package app.oriel.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** The saved queue keeps a window around what's playing, not the first [QueueController.MAX_SAVED]. */
class SavedQueueWindowTest {
    @Test fun aShortQueueIsSavedWhole() {
        assertEquals(0 until 12, QueueController.savedWindow(12, 7))
    }

    @Test fun deepInALongQueueTheWindowIsCentredOnIt() {
        val window = QueueController.savedWindow(1_000, 600)
        assertEquals(450 until 750, window)
        assertEquals(150, 600 - window.first)
    }

    @Test fun nearTheEndsTheWindowStaysFull() {
        assertEquals(0 until 300, QueueController.savedWindow(1_000, 10))
        assertEquals(700 until 1_000, QueueController.savedWindow(1_000, 990))
    }
}
