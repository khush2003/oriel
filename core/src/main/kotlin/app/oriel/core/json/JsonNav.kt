package app.oriel.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Null-tolerant navigation over InnerTube JSON.
 *
 * InnerTube responses are deep, renderer-keyed trees whose layout YouTube
 * reshuffles without notice. Every accessor here returns null instead of
 * throwing, so a parser written against one layout degrades to "field missing"
 * rather than a crash when the layout moves.
 */

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }

operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

/** Walks [keys] (String for object keys, Int for array indices). */
fun JsonElement?.at(vararg keys: Any): JsonElement? {
    var cur = this
    for (k in keys) {
        cur = when (k) {
            is String -> cur[k]
            is Int -> cur[k]
            else -> null
        } ?: return null
    }
    return cur
}

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull
val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull

/** The first key of a single-renderer wrapper object, e.g. `{"musicShelfRenderer": {...}}`. */
val JsonElement?.rendererKey: String? get() = (this as? JsonObject)?.keys?.firstOrNull()
val JsonElement?.renderer: JsonElement? get() = (this as? JsonObject)?.values?.firstOrNull()

/** Text of a `runs`/`simpleText`/`content` text object. */
val JsonElement?.text: String?
    get() {
        if (this == null) return null
        this["simpleText"].string?.let { return it }
        val runs = this["runs"]
        if (runs is JsonArray) return runs.joinToString("") { it["text"].string.orEmpty() }.ifEmpty { null }
        this["content"].string?.let { return it }
        return this.string
    }

val JsonElement?.runs: List<JsonElement> get() = this["runs"].arr

/** Depth-first search for every value stored under [key], anywhere below this node. */
fun JsonElement.findAll(key: String, limit: Int = Int.MAX_VALUE): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    fun visit(e: JsonElement) {
        if (out.size >= limit) return
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == key) out += v
                if (out.size >= limit) return
                if (v is JsonObject || v is JsonArray) visit(v)
            }
            is JsonArray -> for (v in e) {
                visit(v)
                if (out.size >= limit) return
            }
            else -> Unit
        }
    }
    visit(this)
    return out
}

fun JsonElement.findFirst(key: String): JsonElement? = findAll(key, limit = 1).firstOrNull()
