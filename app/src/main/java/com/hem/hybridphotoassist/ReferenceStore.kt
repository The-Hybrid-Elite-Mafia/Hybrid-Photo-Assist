package com.hem.hybridphotoassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reference photos the user adds. Copies are kept in the app's private storage (never uploaded),
 * scaled to at most [MAX_SIDE] on the long side, so they stay available even if the original is moved.
 */
class ReferenceStore(context: Context) {
    private val dir = File(context.filesDir, "references").apply { mkdirs() }
    private val resolver = context.contentResolver

    /** Newest first. */
    fun list(): List<File> = (dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: emptyArray())
        .sortedByDescending { it.lastModified() }

    fun count() = list().size

    /** Blocking. Copies the photo at [uri] into the store; returns the new file. */
    @Throws(IOException::class)
    fun add(uri: Uri): File {
        if (count() >= MAX_COUNT) throw IOException("you already have $MAX_COUNT reference photos; delete one first")
        val bmp = decode(uri, MAX_SIDE)
        val f = File(dir, "ref_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
        try {
            f.outputStream().use { out -> if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw IOException("could not save the reference") }
        } catch (e: Exception) { f.delete(); throw e } finally { bmp.recycle() }
        return f
    }

    fun delete(f: File) { if (f.parentFile == dir) f.delete() }

    /** Blocking. Loads a stored reference as a mutable ARGB bitmap, long side at most [maxSide]. */
    @Throws(IOException::class)
    fun load(f: File, maxSide: Int = MAX_SIDE): Bitmap = decode(Uri.fromFile(f), maxSide)

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val src = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!)) else ImageDecoder.createSource(resolver, uri)
        var bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val s = maxSide.toFloat() / long
                decoder.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
            }
        }
        if (bmp.config != Bitmap.Config.ARGB_8888 || !bmp.isMutable) {
            val c = bmp.copy(Bitmap.Config.ARGB_8888, true); bmp.recycle(); bmp = c
        }
        return bmp
    }

    companion object {
        const val MAX_SIDE = 2048
        const val MAX_COUNT = 30
    }
}
