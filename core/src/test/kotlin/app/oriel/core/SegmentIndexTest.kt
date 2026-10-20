package app.oriel.core

import app.oriel.core.parse.Pages
import app.oriel.core.stream.SegmentIndex
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The indexes SABR seeking is built on, from hand-built files. */
class SegmentIndexTest {
    // ---- a tiny EBML writer ----

    private fun id(v: Long): ByteArray = when {
        v > 0xFFFFFF -> byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        v > 0xFFFF -> byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        v > 0xFF -> byteArrayOf((v shr 8).toByte(), v.toByte())
        else -> byteArrayOf(v.toByte())
    }

    /** Sizes as 4-byte vints, as muxers often write them. */
    private fun size(n: Int): ByteArray = byteArrayOf((0x10 or (n shr 24)).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    private fun el(idv: Long, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { a, b -> a + b }
        return id(idv) + size(body.size) + body
    }

    private fun uint(idv: Long, v: Long, bytes: Int): ByteArray = id(idv) + byteArrayOf((0x80 or bytes).toByte()) + ByteArray(bytes) { (v shr (8 * (bytes - 1 - it))).toByte() }

    private fun cue(timeTicks: Long, cluster: Long) =
        el(0xBB, uint(0xB3, timeTicks, 4), el(0xB7, uint(0xF7, 1, 1), uint(0xF1, cluster, 4)))

    @Test fun webmCuesGiveSegmentStarts() {
        // Timecode scale of 1 ms, as YouTube writes; three clusters at 0, 10 s and 20 s.
        val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000, 4))
        val cues = el(0x1C53BB6B, cue(0, 100), cue(10_000, 5_100), cue(20_001, 9_900))
        val segmentBody = info + cues
        val header = el(0x1A45DFA3, uint(0x4286, 1, 1))
        // An unknown segment size (all ones), as in live-muxed files.
        val init = header + id(0x18538067) + byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) + segmentBody
        val segmentStart = header.size + 4 + 8L

        val index = assertNotNull(SegmentIndex.parse(init))
        assertEquals(3, index.size)
        assertEquals(segmentStart + 100, index.offsetAt(0))
        assertEquals(segmentStart + 5_100, index.offsetAt(1))
        // A byte inside the second cluster starts from its beginning; one before any cluster from 0.
        assertEquals(10_000, index.timeAt(segmentStart + 5_100))
        assertEquals(10_000, index.timeAt(segmentStart + 9_899))
        assertEquals(20_001, index.timeAt(segmentStart + 1_000_000))
        assertEquals(0, index.timeAt(3))
    }

    @Test fun mp4SidxGivesSegmentStarts() {
        val out = ByteArrayOutputStream()
        fun u32(v: Long) = out.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        // ftyp (16 bytes), then a version-0 sidx with two references.
        u32(16); out.write("ftyp".toByteArray()); out.write("dash".toByteArray()); u32(0)
        val sidxSize = 8 + 4 + 4 + 4 + 8 + 4 + 2 * 12L
        u32(sidxSize); out.write("sidx".toByteArray())
        u32(0) // version 0, flags
        u32(1) // reference ID
        u32(48_000) // timescale
        u32(0); u32(0) // earliest presentation time, first offset
        out.write(byteArrayOf(0, 0, 0, 2)) // reserved, reference count
        u32(70_000); u32(480_000); u32(0x90000000) // 70 kB, 10 s
        u32(65_000); u32(480_000); u32(0x90000000)
        val index = assertNotNull(SegmentIndex.parse(out.toByteArray()))
        val first = 16 + sidxSize
        assertEquals(2, index.size)
        assertEquals(first, index.offsetAt(0))
        assertEquals(first + 70_000, index.offsetAt(1))
        assertEquals(10_000, index.timeAt(first + 70_000 + 5))
    }

    @Test fun garbageHasNoIndex() {
        assertNull(SegmentIndex.parse(ByteArray(64) { 7 }))
        assertNull(SegmentIndex.parse(ByteArray(0)))
    }

    @Test fun shortsAdsAreDropped() {
        val root = Json.parseToJsonElement(
            """
            {"entries":[
              {"command":{"reelWatchEndpoint":{"videoId":"short-one","playerParams":"x"}}},
              {"command":{"reelWatchEndpoint":{"videoId":"advert","adClientParams":{"isAd":true}}}},
              {"command":{"reelWatchEndpoint":{"videoId":"short-two"}}}
            ],"continuationEndpoint":{"continuationCommand":{"token":"next"}}}
            """.trimIndent(),
        )
        val batch = Pages.shortsSequence(root)
        assertEquals(listOf("short-one", "short-two"), batch.ids)
        assertEquals("next", batch.continuation)
    }
}
