package app.oriel.update

/**
 * A Semantic Versioning 2.0.0 version, ordered by its precedence rules: numbers compare as
 * numbers, a pre-release sorts before its release, and pre-release identifiers compare one by
 * one (numeric ones as numbers and below alphanumeric ones). Build metadata is ignored.
 */
class SemVer private constructor(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val preRelease: List<String>,
) : Comparable<SemVer> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (preRelease.isEmpty() != other.preRelease.isEmpty()) return if (preRelease.isEmpty()) 1 else -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    override fun equals(other: Any?): Boolean = other is SemVer && compareTo(other) == 0
    override fun hashCode(): Int = listOf(major, minor, patch, preRelease).hashCode()
    override fun toString(): String = "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else preRelease.joinToString(".", prefix = "-")

    companion object {
        private val NUM = "0|[1-9][0-9]*"
        private val ID = "(?:$NUM|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
        private val PATTERN = Regex("^($NUM)\\.($NUM)\\.($NUM)(?:-($ID(?:\\.$ID)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")

        /** A version, or a release tag with a leading `v`; null for anything that isn't strict SemVer. */
        fun parse(text: String): SemVer? {
            val m = PATTERN.matchEntire(text.trim().removePrefix("v")) ?: return null
            val (major, minor, patch) = (1..3).map { m.groupValues[it].toLongOrNull() ?: return null }
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            return SemVer(major, minor, patch, pre)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            return when {
                // Lengths first: no leading zeros, so the longer number is the bigger, with no overflow.
                an && bn -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
