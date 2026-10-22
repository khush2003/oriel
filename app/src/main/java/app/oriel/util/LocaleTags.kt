package app.oriel.util

import java.util.Locale

/**
 * Locales in the forms YouTube's `hl` and `gl` take. YouTube knows a fixed set of interface
 * languages, most of them a bare language code and a few with a region or script; anything else
 * is answered in English, so a device's "de-AT" is sent as "de".
 */
object LocaleTags {
    private val LATIN_AMERICA = setOf(
        "419", "AR", "BO", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN", "MX", "NI", "PA", "PE", "PR", "PY", "SV", "UY", "VE",
    )

    /** YouTube's interface language for a BCP 47 [tag]. */
    fun hl(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val region = locale.country
        val script = locale.script
        return when (val language = locale.language.lowercase()) {
            "" -> "en"
            "en" -> if (region == "GB" || region == "IN") "en-$region" else "en"
            "es" -> when {
                region == "US" -> "es-US"
                region in LATIN_AMERICA -> "es-419"
                else -> "es"
            }
            "fr" -> if (region == "CA") "fr-CA" else "fr"
            // YouTube's "pt" is Brazilian Portuguese.
            "pt" -> if (region == "PT") "pt-PT" else "pt"
            "zh" -> when {
                region == "HK" || region == "MO" -> "zh-HK"
                script == "Hant" || region == "TW" -> "zh-TW"
                else -> "zh-CN"
            }
            "sr" -> if (script == "Latn") "sr-Latn" else "sr"
            // YouTube keeps the older codes for these.
            "he" -> "iw"
            "in" -> "id"
            "nb", "nn" -> "no"
            "tl" -> "fil"
            else -> language
        }
    }

    /** YouTube's content region: a two-letter country, or the US when there's none. */
    fun gl(country: String?): String =
        country?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' } }?.uppercase() ?: "US"
}
