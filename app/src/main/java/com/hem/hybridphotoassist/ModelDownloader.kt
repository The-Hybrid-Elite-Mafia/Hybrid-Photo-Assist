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

    fun isReady(): Boolean = file.exists() && file.length() >= MIN_BYTES

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
        if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("could not save model") }
    }

    companion object {
        const val FILE_NAME = "lama_dilated.tflite"
        // Apache-2.0 LaMa (Samsung Research) converted for LiteRT.
        const val URL_STRING = "https://huggingface.co/mlboydaisuke/LaMa-Dilated-LiteRT/resolve/main/lama_dilated.tflite"
        const val MIN_BYTES = 150L * 1024 * 1024
    }
}
