package app.oriel.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSaverTest {
    @Test fun dataSaverStopsPrefetching() {
        assertTrue(SettingsState().prefetching)
        assertFalse(SettingsState(dataSaver = true).prefetching)
        assertFalse(SettingsState(prefetchNext = false).prefetching)
    }
}
