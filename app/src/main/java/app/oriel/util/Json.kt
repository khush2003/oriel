package app.oriel.util

import kotlinx.serialization.json.Json

/** One lenient Json for everything the app persists; unknown keys survive model evolution. */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}
