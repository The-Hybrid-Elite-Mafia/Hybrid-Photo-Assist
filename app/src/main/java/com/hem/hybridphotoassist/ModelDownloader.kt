package com.hem.hybridphotoassist

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the erase-and-fill model once and keeps it in the app's private storage.
 * Photos are never uploaded anywhere; this is the only network use.
 */
class ModelDownloader(private val context: Context) {

    val file: File get() = File(context.filesDir, FILE_NAME)

    private val verifiedMarker: File get() = File(context.filesDir, "$FILE_NAME.sha256")

    /** The model file is there (it may still need [ensureVerified]). */
    fun hasFile(): Boolean = file.exists() && file.length() >= MIN_BYTES

    /** The model file is there and its SHA-256 has been checked. */
    fun isReady(): Boolean = hasFile() && verifiedMarker.exists() && verifiedMarker.readText().trim() == SHA256

    /**
     * Checks the model file against the known SHA-256 (once; remembered afterwards).
     * A damaged or different file is deleted and an error is thrown.
     */
    @Throws(IOException::class)
    fun ensureVerified() {
        if (isReady()) return
        if (!hasFile()) throw IOException("the fill AI is not downloaded")
        val got = sha256(file)
        if (got != SHA256) {
            file.delete(); verifiedMarker.delete()
            throw IOException("the fill AI file didn't pass its safety check and was deleted. Tap Erase again to download it fresh")
        }
        verifiedMarker.writeText(SHA256)
    }

    /** Blocking. [progress] gets (downloaded bytes, total bytes or -1). */
    @Throws(IOException::class)
    fun download(progress: (Long, Long) -> Unit) {
        if (isReady()) return
        val tmp = File(context.filesDir, "$FILE_NAME.part")
        var url = URL(URL_STRING)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("redirect without location")
                conn.disconnect()
                url = URL(url, loc)
                if (++redirects > 8) throw IOException("too many redirects")
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IOException("download failed: HTTP $code")
            }
            break
        }
        val total = conn.contentLengthLong
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 1_000_000) { progress(done, total); lastReport = done }
                    }
                    progress(done, total)
                }
            }
        } finally {
            conn.disconnect()
        }
        if (total > 0 && tmp.length() != total) { tmp.delete(); throw IOException("download incomplete") }
        if (tmp.length() < MIN_BYTES) { tmp.delete(); throw IOException("downloaded file is too small (${tmp.length()} bytes)") }
        val got = sha256(tmp)
        if (got != SHA256) { tmp.delete(); throw IOException("the download didn't pass its safety check (checksum mismatch), so it was thrown away") }
        if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("could not save model") }
        verifiedMarker.writeText(SHA256)
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FILE_NAME = "lama_dilated.tflite"
        // Apache-2.0 LaMa (Samsung Research) converted for LiteRT.
        const val URL_STRING = "https://huggingface.co/mlboydaisuke/LaMa-Dilated-LiteRT/resolve/main/lama_dilated.tflite"
        const val MIN_BYTES = 150L * 1024 * 1024
        /** SHA-256 of the file at [URL_STRING]; CI downloads the same file and checks it matches. */
        const val SHA256 = "SHA256_FROM_CI"
    }
}
