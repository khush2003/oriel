package app.oriel.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The version of a stored document's shape, so data written by any earlier release still reads
 * after an update.
 *
 * The version lives in the document's top-level [KEY] ("schema"), next to the model's fields but
 * not one of them; a document without it is version 0. [read] brings an older document up to
 * [current], one step at a time, and [write] stamps the version on what's saved.
 *
 * Changing a persisted model in a way old data can't decode into means: bump [current], add the
 * step from the previous version, and add a frozen fixture of the old shape to the tests
 * (see CONTRIBUTING.md).
 */
class Schema(val current: Int, private val steps: Map<Int, (JsonObject) -> JsonObject>) {
    init {
        require((0 until current).all { it in steps }) { "a step from every version below $current is needed" }
    }

    /** The version [doc] was written at; 0 when it predates versioning. */
    fun version(doc: JsonObject): Int = (doc[KEY] as? JsonPrimitive)?.intOrNull ?: 0

    /**
     * [doc] as the current version expects it, without the version key. A document from a newer
     * release (after a downgrade) is read as it is: unknown keys are ignored and missing ones
     * take their defaults.
     */
    fun read(doc: JsonObject): JsonObject {
        var d = JsonObject(doc - KEY)
        for (v in version(doc) until current) d = steps.getValue(v)(d)
        return d
    }

    /** [doc] stamped with the current version. */
    fun write(doc: JsonObject): JsonObject = JsonObject(doc + (KEY to JsonPrimitive(current)))

    companion object {
        const val KEY = "schema"

        private val identity: (JsonObject) -> JsonObject = { it }

        /** library.json: likes, Watch later, subscriptions, playlists, history, searches. */
        val LIBRARY = Schema(1, mapOf(0 to identity))

        /** The settings document in SharedPreferences. */
        val SETTINGS = Schema(1, mapOf(0 to identity))

        /** positions.json: resume points by video id. */
        val POSITIONS = Schema(1, mapOf(0 to identity))
    }
}

/**
 * A list that drops the elements it can't decode instead of failing the whole document: one
 * malformed history entry shouldn't cost the person their library. Each drop is counted through
 * [onDropped]. Encoding is a plain list.
 */
open class SkipBadList<T>(private val element: KSerializer<T>, private val what: String) : KSerializer<List<T>> {
    private val list = ListSerializer(element)
    override val descriptor: SerialDescriptor = list.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return list.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        var dropped = 0
        val out = array.mapNotNull { e ->
            runCatching { json.json.decodeFromJsonElement(element, e) }.getOrElse { dropped++; null }
        }
        if (dropped > 0) onDropped("Stored $what: skipped $dropped unreadable of ${array.size}")
        return out
    }

    companion object {
        /** Where drops are noted; the app points it at the problem-report trail. */
        @Volatile
        var onDropped: (String) -> Unit = {}
    }
}
