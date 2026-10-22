package app.oriel.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/** GitHub answered, but not with releases: rate-limited, or down. Quietly retried at the next check. */
class UpdateCheckException(message: String) : IOException(message)

/**
 * Talks to GitHub, and only GitHub: its own client, with no cookies, no cache and none of the
 * YouTube client's headers, so nothing of the person's YouTube session can ride along.
 */
class UpdateChecker(private val userAgent: String, base: OkHttpClient = OkHttpClient()) {
    private val http = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        // Network interceptors see every hop, so a redirect off GitHub fails before it's fetched.
        .addNetworkInterceptor(GitHubOnly)
        .build()

    /** Release downloads go through objects.githubusercontent.com; anything else is refused. */
    object GitHubOnly : Interceptor {
        override fun intercept(chain: Interceptor.Chain) = chain.request().let { r ->
            if (!Releases.isAllowedHost(r.url)) throw IOException("Refused to fetch an update from ${r.url.host}")
            chain.proceed(r)
        }
    }

    private fun request(url: String, accept: String) = Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .header("Accept", accept)
        .build()

    fun releases(): List<Release> {
        http.newCall(request(Releases.API_URL, "application/vnd.github+json").newBuilder().header("X-GitHub-Api-Version", "2022-11-28").build()).execute().use { r ->
            if (!r.isSuccessful) throw UpdateCheckException("GitHub answered ${r.code}")
            return Releases.parse(readCapped(r.body.byteStream(), MAX_API_BYTES).decodeToString())
        }
    }

    fun checksums(asset: ReleaseAsset): Map<String, String> {
        http.newCall(request(asset.url, "application/octet-stream")).execute().use { r ->
            if (!r.isSuccessful) throw UpdateCheckException("Checksums answered ${r.code}")
            return Releases.parseChecksums(readCapped(r.body.byteStream(), MAX_CHECKSUM_BYTES).decodeToString())
        }
    }

    /**
     * Streams [asset] into [into], hashing as it goes, and returns the SHA-256 hex. Stops (and
     * deletes the part written) past [MAX_APK_BYTES] or past the size the release lists, or when
     * the coroutine is cancelled.
     */
    suspend fun download(asset: ReleaseAsset, into: File, onProgress: (read: Long, total: Long) -> Unit): String {
        val limit = minOf(MAX_APK_BYTES, asset.size.takeIf { it > 0 } ?: MAX_APK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        val call = http.newCall(request(asset.url, "application/octet-stream"))
        // Cancelling the coroutine also breaks a read that's blocked waiting on the network.
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw UpdateCheckException("Download answered ${r.code}")
                val length = r.body.contentLength()
                if (length > limit) throw IOException("Update is larger than expected")
                val total = if (length > 0) length else asset.size
                r.body.byteStream().use { input ->
                    into.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > limit) throw IOException("Update is larger than expected")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            onProgress(read, total)
                        }
                        out.fd.sync()
                        if (asset.size > 0 && read != asset.size) throw IOException("Update download was cut short")
                    }
                }
            }
        } catch (t: Throwable) {
            into.delete()
            throw t
        } finally {
            cancel?.dispose()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray = input.use {
        val bytes = it.readNBytesCompat(max + 1)
        if (bytes.size > max) throw UpdateCheckException("Answer too large")
        bytes
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < n) {
            val r = read(buf, 0, minOf(buf.size, n - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    companion object {
        /** Far above an Oriel APK (about 8 MB universal), far below filling a phone. */
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val MAX_API_BYTES = 4 * 1024 * 1024
        private const val MAX_CHECKSUM_BYTES = 64 * 1024
    }
}
