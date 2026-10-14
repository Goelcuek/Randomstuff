package com.example.widgetnotes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Photos attached to notes live in the app's private storage (filesDir/images). */
object ImageStore {
    private const val THUMB_PX = 160

    private val thumbCache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun dir(context: Context): File = File(context.filesDir, "images").apply { mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), name)

    private fun thumbFile(context: Context, name: String) = File(dir(context), "thumb_$name")

    fun newPhotoFile(context: Context): File =
        File(dir(context), "img_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")

    /** content:// URI the camera app can write into. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Copies a picked image (gallery / photo picker) into app storage. Returns its name. */
    suspend fun importFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = newPhotoFile(context)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            makeThumb(context, f.name)
            f.name
        }.getOrNull()
    }

    /** Small square, rotation-corrected thumbnail used by the widget. */
    fun makeThumb(context: Context, name: String) {
        val bmp = decodeSampled(file(context, name), THUMB_PX * 2) ?: return
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val thumb = Bitmap.createScaledBitmap(square, THUMB_PX, THUMB_PX, true)
        runCatching { thumbFile(context, name).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        thumbCache.put(name, thumb)
    }

    fun loadThumb(context: Context, name: String): Bitmap? {
        thumbCache.get(name)?.let { return it }
        val tf = thumbFile(context, name)
        if (!tf.exists()) {
            if (file(context, name).exists()) makeThumb(context, name)
            return thumbCache.get(name)
        }
        return BitmapFactory.decodeFile(tf.path)?.also { thumbCache.put(name, it) }
    }

    /** Decodes a downsampled, EXIF-rotated bitmap whose short side is at least [targetPx]. */
    fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun delete(context: Context, names: Collection<String>) {
        names.forEach {
            file(context, it).delete()
            thumbFile(context, it).delete()
            thumbCache.remove(it)
        }
    }
}
