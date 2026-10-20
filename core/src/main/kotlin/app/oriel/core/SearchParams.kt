package app.oriel.core

import java.util.Base64

/**
 * YouTube's search params are a protobuf message, base64'd: field 1 the sort, field 2 a message
 * of filters (1 upload date, 2 type, 3 duration, the rest features, one field each).
 *
 * A filtered page only offers combinations it can add to, and a filter already on comes with no
 * params at all, so there's no asking YouTube for "these filters minus that one". Two serialised
 * messages concatenated parse as the two merged, though, so any set of single filters taken from
 * the unfiltered page combines into one params string here, and dropping one is just leaving it out.
 */
object SearchParams {
    /** The params for all of [single] together (each one filter's own params), or null for none. */
    fun combine(single: List<String>): String? {
        if (single.isEmpty()) return null
        val bytes = single.fold(ByteArray(0)) { acc, p -> acc + decode(p) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Which slot a single filter fills: "sort", "date", "type", "duration", or "feature:N".
     * Picking a filter replaces whatever holds its slot; features each have their own, so several
     * can be on at once, as on YouTube.
     */
    fun slot(params: String): String {
        val top = fields(decode(params))
        if (top.any { it.first == 1 }) return "sort"
        val filters = top.firstOrNull { it.first == 2 }?.second ?: return "other"
        val inner = fields(filters).firstOrNull()?.first ?: return "other"
        return when (inner) {
            1 -> "date"
            2 -> "type"
            3 -> "duration"
            else -> "feature:$inner"
        }
    }

    private fun decode(params: String): ByteArray = runCatching { Base64.getDecoder().decode(params) }
        .recoverCatching { Base64.getUrlDecoder().decode(params) }.getOrDefault(ByteArray(0))

    /** Top-level fields of a message: number and, for length-delimited ones, the payload (else empty). */
    private fun fields(b: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        fun varint(): Long {
            var shift = 0
            var v = 0L
            while (i < b.size) {
                val x = b[i++].toInt() and 0xFF
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
            }
            return v
        }
        while (i < b.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> { varint(); out += field to ByteArray(0) }
                1 -> { i += 8; out += field to ByteArray(0) }
                2 -> {
                    val n = varint().toInt()
                    if (n < 0 || i + n > b.size) return out
                    out += field to b.copyOfRange(i, i + n); i += n
                }
                5 -> { i += 4; out += field to ByteArray(0) }
                else -> return out
            }
        }
        return out
    }
}
