package app.oriel.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** Live: a short's sound credit, and the shorts that use the sound. Opt-in (-Plive=true). */
class ShortsSoundLiveTest {
    @Test fun sound() = runBlocking {
        if (System.getProperty("oriel.live") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val ids = yt.shorts().ids.take(3)
        for (id in ids) {
            val info = yt.shortInfo(id)
            println("SOUND $id title=${info.title} sound=${info.sound}")
            val token = info.sound?.token ?: continue
            val page = yt.soundPage(token)
            println("SOUND page title=${page.title} subtitle=${page.subtitle} image=${page.image != null} shorts=${page.shorts.size} first=${page.shorts.firstOrNull()?.let { "${it.id} ${it.title} ${it.viewsText}" }} more=${page.continuation != null}")
            kotlin.test.assertTrue(page.shorts.isNotEmpty(), "a sound lists at least the short that uses it")
        }
    }
}
