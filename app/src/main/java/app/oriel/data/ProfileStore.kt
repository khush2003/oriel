package app.oriel.data

import android.content.Context
import androidx.core.content.edit
import app.oriel.core.YouTube
import app.oriel.core.model.AccountInfo
import app.oriel.util.AppJson
import app.oriel.util.Schema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.util.UUID

@Serializable
enum class ProfileKind {
    /** Oriel used without an account: its library is this device's alone. */
    @SerialName("GUEST") GUEST,
    /** A YouTube account signed in here, or signed out with its library kept. */
    @SerialName("ACCOUNT") ACCOUNT,
}

/**
 * One person's Oriel: a library of its own (in [ProfileStore.dir]), and for an account, the sign-in
 * it uses. Ids are made here, never derived from the account, so a path in a crash note says
 * nothing about whose it is.
 */
@Serializable
data class Profile(
    val id: String,
    val kind: ProfileKind,
    /**
     * The sign-in this profile uses, sealed in the prefs under `cookie:<credential>`; null when
     * signed out (its library is kept, and signing in again picks it up). Kept apart from [id] so a
     * channel of the same Google account can later be a profile sharing one sign-in.
     */
    val credential: String? = null,
    /** The account's DATASYNC_ID: which channel requests act as, and how a sign-in is recognised as this account again. */
    val dataSyncId: String? = null,
    val info: AccountInfo? = null,
    /** Whether this sign-in's first sync, the one that only adds, has gone through. */
    val merged: Boolean = false,
    val lastSync: Long = 0L,
    val addedAt: Long = 0L,
    /** The account has Premium: its formats may be asked for. */
    val premiumFormats: Boolean = false,
) {
    val isGuest: Boolean get() = kind == ProfileKind.GUEST
    val signedIn: Boolean get() = credential != null
}

@Serializable
data class ProfileIndex(
    val profiles: List<Profile>,
    val active: String,
    /** Removed profiles whose files may still be on disk: deleted at the next launch, and only these. */
    val removed: List<String> = emptyList(),
    /** The profile the files from before profiles belong to, until they've all moved into its directory. */
    val legacyOwner: String? = null,
) {
    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }
    val activeProfile: Profile get() = profile(active) ?: profiles.first { it.isGuest }
    fun with(profile: Profile) = copy(profiles = profiles.map { if (it.id == profile.id) profile else it })

    /** [id]'s profile as [change] makes it from what it is in this index, if it's still here. */
    fun change(id: String, change: (Profile) -> Profile) = copy(profiles = profiles.map { if (it.id == id) change(it) else it })
}

/**
 * Where profiles are kept: the index and the sealed sign-ins in the `account` prefs (read at
 * launch before the first frame, and replaced as a whole by each edit), each profile's files in
 * `files/profiles/<id>/`, and its visitor identity in the `session` prefs.
 *
 * Every step here survives the process dying part-way: the index changes in one edit, files only
 * ever move by rename and never overwrite, and removal deletes only what the index has tombstoned.
 */
class ProfileStore(
    context: Context,
    private val filesDir: File,
    private val box: SecretBox,
    /** The Premium setting from before it belonged to an account, for the account the migration makes. */
    private val legacyPremium: () -> Boolean = { false },
    private val report: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val root = File(filesDir, "profiles")

    fun dir(profileId: String): File = File(root, profileId)

    private var loaded: ProfileIndex? = null

    /** The index as it is now; read (and made from what an earlier version kept) on first use. */
    val index: ProfileIndex get() = synchronized(prefs) { loaded ?: load().also { loaded = it } }

    /** Changes the index, and with it the sealed sign-ins in [cookies] (null removes one), in one edit. */
    fun update(cookies: Map<String, String?> = emptyMap(), transform: (ProfileIndex) -> ProfileIndex): ProfileIndex = synchronized(prefs) {
        val next = transform(index)
        save(next, cookies)
        loaded = next
        next
    }

    private fun load(): ProfileIndex {
        if (prefs.getInt(KEY_SCHEMA, 0) < SCHEMA) return migrate()
        val text = prefs.getString(KEY_INDEX, null) ?: return fresh()
        return try {
            AppJson.decodeFromJsonElement(ProfileIndex.serializer(), Schema.PROFILES.read(AppJson.parseToJsonElement(text).jsonObject))
                .takeIf { index -> index.profiles.any { it.isGuest } && index.profiles.distinctBy { it.id }.size == index.profiles.size }
                ?: throw IllegalStateException("profile index without a guest or with repeated ids")
        } catch (e: Exception) {
            // Kept aside, not lost; the profiles' files stay where they are. Only a Guest is made,
            // and nothing is swept: a sweep from an index that couldn't be read would delete every library.
            report("Profile index decode", e)
            val index = ProfileIndex(listOf(guest()), GUEST)
            prefs.edit { putString(KEY_UNREADABLE, text).putString(KEY_INDEX, encode(index)) }
            index
        }
    }

    private fun fresh(): ProfileIndex {
        val index = ProfileIndex(listOf(guest()), GUEST)
        prefs.edit { putInt(KEY_SCHEMA, SCHEMA).putString(KEY_INDEX, encode(index)) }
        return index
    }

    /**
     * Before profiles there was one session, and one library shared signed in or out. The library
     * becomes the account's if someone was signed in, otherwise the Guest's. The prefs change in
     * one edit; the files move afterwards ([adoptLegacyFiles]), as the libraries are first read.
     */
    private fun migrate(): ProfileIndex {
        val cookie = prefs.getString(LEGACY_COOKIE, null)?.takeIf { SecretBox.isSealed(it) || YouTube.isSignedInCookie(it) }
        val index = if (cookie == null) {
            ProfileIndex(listOf(guest()), GUEST, legacyOwner = GUEST)
        } else {
            val info = prefs.getString(LEGACY_ACCOUNT, null)?.let { runCatching { AppJson.decodeFromString(AccountInfo.serializer(), it) }.getOrNull() }
            val account = Profile(
                id = MIGRATED,
                kind = ProfileKind.ACCOUNT,
                credential = MIGRATED,
                dataSyncId = prefs.getString(LEGACY_DATASYNC, null),
                info = info?.takeIf { it.name.isNotBlank() },
                merged = prefs.getBoolean(LEGACY_MERGED, false),
                lastSync = prefs.getLong(LEGACY_LAST_SYNC, 0L),
                addedAt = System.currentTimeMillis(),
                premiumFormats = legacyPremium(),
            )
            ProfileIndex(listOf(guest(), account), MIGRATED, legacyOwner = MIGRATED)
        }
        prefs.edit {
            LEGACY_KEYS.forEach(::remove)
            // A plain cookie from before sealing moves as it is, and is sealed by [sealPlain].
            if (cookie != null) putString(cookieKey(MIGRATED), cookie)
            putInt(KEY_SCHEMA, SCHEMA).putString(KEY_INDEX, encode(index))
        }
        session.getString(LEGACY_VISITOR, null)?.let { session.edit { putString(visitorKey(index.active), it).remove(LEGACY_VISITOR) } }
        return index
    }

    /**
     * Moves the files from before profiles into [ProfileIndex.legacyOwner]'s directory. Renames on
     * one filesystem are atomic, so a launch that died part-way finishes the rest; a file already
     * at the destination is never overwritten, the old one is kept beside it instead. [ProfileIndex.legacyOwner] is
     * cleared once nothing is left to move. Cheap once done,
     * so whatever reads a profile's files first calls it.
     */
    fun adoptLegacyFiles(): Unit = synchronized(prefs) {
        val owner = index.legacyOwner ?: return
        val dir = dir(owner)
        val legacy = filesDir.listFiles { f -> f.isFile && LEGACY_FILES.any { f.name.startsWith(it) } }.orEmpty()
        var left = 0
        for (f in legacy) {
            try {
                dir.mkdirs()
                val to = File(dir, f.name).let { if (it.exists()) File(dir, "${f.name}.legacy-${System.currentTimeMillis()}") else it }
                if (!f.renameTo(to)) throw IOException("rename failed")
            } catch (e: Exception) {
                report("Profile file adoption", e)
                left++
            }
        }
        if (left == 0) update { it.copy(legacyOwner = null) }
    }

    private fun save(index: ProfileIndex, cookies: Map<String, String?>) {
        prefs.edit {
            cookies.forEach { (credential, sealed) -> if (sealed != null) putString(cookieKey(credential), sealed) else remove(cookieKey(credential)) }
            putInt(KEY_SCHEMA, SCHEMA).putString(KEY_INDEX, encode(index))
        }
    }

    /** Whether [credential] has a sign-in stored, without opening it (on the main thread at launch). */
    fun hasCookie(credential: String): Boolean = prefs.getString(cookieKey(credential), null)
        ?.let { SecretBox.isSealed(it) || YouTube.isSignedInCookie(it) } == true

    sealed interface Opened {
        data class Cookie(val value: String) : Opened
        /** Can never be opened (the key is gone or the value was tampered with): the sign-in is lost. */
        data object Gone : Opened
        /** The keystore is busy: the sign-in is kept for later. */
        data object Busy : Opened
    }

    /** [credential]'s cookie, opened; a keystore that's only busy gets a few more tries. */
    fun open(credential: String): Opened {
        val stored = prefs.getString(cookieKey(credential), null) ?: return Opened.Gone
        if (!SecretBox.isSealed(stored)) return if (YouTube.isSignedInCookie(stored)) Opened.Cookie(stored) else Opened.Gone
        repeat(KEYSTORE_TRIES) { attempt ->
            val opened = try {
                box.open(stored)
            } catch (_: SecretBox.Unavailable) {
                if (attempt < KEYSTORE_TRIES - 1) Thread.sleep(KEYSTORE_RETRY_MS)
                return@repeat
            }
            return if (opened != null) Opened.Cookie(opened) else Opened.Gone
        }
        return Opened.Busy
    }

    /** [cookie] sealed for storage, or null if the keystore can't now (then it's never stored). */
    fun seal(cookie: String): String? = runCatching { box.seal(cookie) }.getOrNull()

    /**
     * Earlier versions kept the cookie as plain text; it's sealed in place, and the session carries
     * on. If the keystore can't seal it now, it stays as it was and the next launch tries again.
     */
    fun sealPlain(credential: String) = synchronized(prefs) {
        val stored = prefs.getString(cookieKey(credential), null) ?: return
        if (SecretBox.isSealed(stored) || !YouTube.isSignedInCookie(stored)) return
        val sealed = seal(stored) ?: return
        prefs.edit { putString(cookieKey(credential), sealed) }
    }

    fun visitor(profileId: String): String? = session.getString(visitorKey(profileId), null)

    fun putVisitor(profileId: String, visitorData: String?) = session.edit {
        if (visitorData != null) putString(visitorKey(profileId), visitorData) else remove(visitorKey(profileId))
    }

    /** Deletes what tombstoned profiles left on disk, then the tombstones. Call off the main thread. */
    fun sweep(pages: (String) -> Unit) {
        val removed = index.removed
        if (removed.isEmpty()) return
        val swept = removed.filter { id ->
            pages(id)
            putVisitor(id, null)
            val d = dir(id)
            !d.exists() || d.deleteRecursively()
        }.toSet()
        update { it.copy(removed = it.removed - swept) }
    }

    /** A new account profile's id. */
    fun newId(): String = UUID.randomUUID().toString()

    private fun encode(index: ProfileIndex): String =
        Schema.PROFILES.write(AppJson.encodeToJsonElement(ProfileIndex.serializer(), index).jsonObject).toString()

    companion object {
        const val GUEST = "guest"
        /** The account an update from before profiles was signed in to. Fixed, so a migration cut short finds the same place again. */
        const val MIGRATED = "a0"
        /** How many accounts can be signed in at once: several of one person's, not a shared pool. */
        const val MAX_ACCOUNTS = 5

        fun guest() = Profile(id = GUEST, kind = ProfileKind.GUEST)

        private const val KEYSTORE_TRIES = 3
        private const val KEYSTORE_RETRY_MS = 150L

        /** The version of what's kept in the `account` prefs ([app.oriel.util.Schema] versions the index inside). */
        private const val KEY_SCHEMA = "schema"
        /** 3: profiles; 2 had one sealed cookie; 1 kept it as plain text. */
        private const val SCHEMA = 3
        private const val KEY_INDEX = "index"
        private const val KEY_UNREADABLE = "index.unreadable"

        private fun cookieKey(credential: String) = "cookie:$credential"
        private fun visitorKey(profileId: String) = "visitorData:$profileId"

        // Schema 1 and 2.
        private const val LEGACY_COOKIE = "cookie"
        private const val LEGACY_DATASYNC = "datasync"
        private const val LEGACY_ACCOUNT = "account"
        private const val LEGACY_LAST_SYNC = "lastSync"
        private const val LEGACY_MERGED = "merged"
        private val LEGACY_KEYS = listOf(LEGACY_COOKIE, LEGACY_DATASYNC, LEGACY_ACCOUNT, LEGACY_LAST_SYNC, LEGACY_MERGED)
        private const val LEGACY_VISITOR = "visitorData"

        /** The library, resume points and queue, with their temporary, backup and set-aside copies. */
        private val LEGACY_FILES = listOf("library.json", "library.v", "positions.json", "queue.json")
    }
}
