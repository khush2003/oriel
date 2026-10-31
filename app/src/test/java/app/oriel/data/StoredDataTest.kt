package app.oriel.data

import app.oriel.core.model.AccountInfo
import app.oriel.core.model.Feed
import app.oriel.core.model.InboxPage
import app.oriel.util.AppJson
import app.oriel.util.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stored data must survive every update: frozen copies of each document as earlier releases
 * wrote it still read, an empty document reads, and a bad entry costs only itself.
 * The fixtures under resources/stored are invented.
 */
class StoredDataTest {
    private fun fixture(name: String): JsonObject =
        AppJson.parseToJsonElement(javaClass.classLoader!!.getResource("stored/$name")!!.readText()).jsonObject

    private fun library(doc: JsonObject) = AppJson.decodeFromJsonElement(LibraryState.serializer(), Schema.LIBRARY.read(doc))

    @Test
    fun libraryVersion0Reads() {
        val doc = fixture("library.v0.json")
        assertEquals(0, Schema.LIBRARY.version(doc))
        val s = library(doc)
        assertEquals(listOf("fakeVideo01", "fakeVideo02"), s.liked.map { it.id })
        assertEquals(754, s.liked[0].durationSec)
        assertEquals("Made-up Channel", s.liked[0].channel?.name)
        assertTrue(s.liked[1].isShort)
        assertEquals(setOf("fakeVideo09"), s.dislikedIds)
        assertEquals(0.5f, s.watchLater.single().progress)
        assertEquals("@madeup", s.subscriptions.single().handle)
        assertEquals(listOf(false, true), s.savedPlaylists.map { it.isMix })
        val local = s.localPlaylists.single()
        assertEquals("11111111-2222-3333-4444-555555555555", local.id)
        assertEquals(1760000000000L, local.createdAt)
        assertEquals("fakeVideo01", local.videos.single().id)
        assertEquals(listOf("fakeVideo04", "fakeVideo01"), s.history.map { it.video.id })
        assertEquals(mapOf("fakeVideo04" to 120000L), s.positions)
        assertEquals(2, s.searchHistory.size)
    }

    @Test
    fun settingsVersion0Reads() {
        val doc = fixture("settings.v0.json")
        val (s, partial) = Settings.decode(Schema.SETTINGS.read(doc))
        assertFalse(partial)
        assertEquals(VideoQuality.P1080, s.videoWifi)
        assertEquals(VideoQuality.P480, s.videoCellular)
        assertEquals(AudioQuality.LOW, s.audioQuality)
        assertTrue(s.dataSaver)
        assertEquals(Transport.SABR, s.transport)
        assertFalse(s.autoplay)
        assertEquals(1.25f, s.defaultSpeed)
        assertEquals("de", s.captionLanguage)
        assertEquals(5, s.doubleTapSeekSec)
        assertEquals(setOf("sponsor", "intro"), s.sponsorCategories)
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals("DE", s.country)
        assertEquals(false, s.telemetry)
        // Not in the old document: today's defaults.
        assertTrue(s.backgroundPlay)
        assertFalse(s.liquidMotion)
        assertFalse(s.liquidMotionAsked)
        assertTrue(s.preferOriginalAudio)
    }

    @Test
    fun emptyDocumentsRead() {
        val empty = JsonObject(emptyMap())
        assertEquals(LibraryState(), library(empty))
        assertEquals(SettingsState(), Settings.decode(Schema.SETTINGS.read(empty)).first)
        assertEquals(Feed(), AppJson.decodeFromString(Feed.serializer(), "{}"))
        assertEquals(InboxPage(), AppJson.decodeFromString(InboxPage.serializer(), "{}"))
        assertEquals(AccountInfo(), AppJson.decodeFromString(AccountInfo.serializer(), "{}"))
        assertTrue(Schema.POSITIONS.read(empty).isEmpty())
    }

    @Test
    fun aBadItemDropsOnlyItself() {
        val doc = AppJson.parseToJsonElement(
            """
            {"liked": [
                {"id": "goodVideo01", "title": "Fine"},
                {"id": "badVideo001", "title": "Wrong type", "durationSec": {"not": "a number"}},
                {"id": "badVideo002"},
                {"id": "goodVideo02", "title": "Also fine"}
            ],
            "history": [{"video": {"id": "goodVideo03", "title": "Kept"}, "watchedAt": 5}, {"video": "nope", "watchedAt": 6}],
            "searchHistory": ["kept"]}
            """,
        ).jsonObject
        val s = library(doc)
        assertEquals(listOf("goodVideo01", "goodVideo02"), s.liked.map { it.id })
        assertEquals(listOf("goodVideo03"), s.history.map { it.video.id })
        assertEquals(listOf("kept"), s.searchHistory)
    }

    @Test
    fun writtenDocumentsCarryTheirVersion() {
        val written = Schema.LIBRARY.write(AppJson.encodeToJsonElement(LibraryState.serializer(), LibraryState()).jsonObject)
        assertEquals(Schema.LIBRARY.current, written[Schema.KEY]!!.jsonPrimitive.int)
        // The key isn't part of the model.
        assertNull(Schema.LIBRARY.read(written)[Schema.KEY])
        val settings = AppJson.parseToJsonElement(Settings.encode(SettingsState())).jsonObject
        assertEquals(Schema.SETTINGS.current, settings[Schema.KEY]!!.jsonPrimitive.int)
    }

    @Test
    fun settingsKeepDefaultsAsChosen() {
        // A value equal to today's default is written all the same: a later default can't take it over.
        val doc = AppJson.parseToJsonElement(Settings.encode(SettingsState())).jsonObject
        assertEquals(JsonPrimitive("HIGH"), doc["audioQuality"])
        assertEquals(JsonPrimitive(true), doc["autoplay"])
    }

    @Test
    fun corruptSettingsKeepWhatReads() {
        val doc = AppJson.parseToJsonElement("""{"telemetry": true, "autoplay": "sometimes", "theme": "LIGHT", "doubleTapSeekSec": [1]}""").jsonObject
        val (s, partial) = Settings.decode(doc)
        assertTrue(partial)
        assertEquals(true, s.telemetry)
        assertEquals(ThemeMode.LIGHT, s.theme)
        assertEquals(SettingsState().autoplay, s.autoplay)
        assertEquals(SettingsState().doubleTapSeekSec, s.doubleTapSeekSec)
    }

    @Test
    fun anUnreadableTelemetryAnswerIsNotAskedAgain() {
        val (s, _) = Settings.decode(AppJson.parseToJsonElement("""{"telemetry": {"x": 1}}""").jsonObject)
        assertEquals(false, s.telemetry)
    }

    @Test
    fun localPlaylistIdentityIsWritten() {
        val p = LocalPlaylist(name = "Mine")
        val again = AppJson.decodeFromString(LocalPlaylist.serializer(), AppJson.encodeToString(LocalPlaylist.serializer(), p))
        assertEquals(p, again)
    }
}
