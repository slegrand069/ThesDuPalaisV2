package fr.thesdupalais.data.database

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/** Private, article-scoped JPEG storage. Generated names avoid collisions between packages. */
class ArticleImageStore(private val filesDir: File) {
    private val root = File(filesDir, "article-images")
    private val pending = File(filesDir, "article-images-pending")

    init {
        // Remove abandoned staging files left by a process death; active imports are never this old.
        pending.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000 }?.forEach { it.delete() }
    }

    fun stage(bytes: ByteArray): StagedArticleImage {
        if (bytes.size > MAX_INPUT_BYTES) throw IOException("Image supérieure à 20 MiB.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 12000 || bounds.outHeight > 12000) throw IOException("Image invalide ou dimensions excessives.")
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Image illisible.")
        val oriented = orient(bitmap, bytes)
        val scaled = scale(oriented)
        val id = UUID.randomUUID().toString()
        if (!pending.exists() && !pending.mkdirs()) throw IOException("Stockage temporaire des images d’articles indisponible.")
        val file = File(pending, "$id.jpg")
        try { FileOutputStream(file).use { if (!scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)) throw IOException("Compression JPEG impossible."); it.fd.sync() } }
        catch (error: Throwable) { file.delete(); throw error }
        finally { if (scaled !== oriented) scaled.recycle(); if (oriented !== bitmap) oriented.recycle(); bitmap.recycle() }
        if (file.length() > MAX_OUTPUT_BYTES) { file.delete(); throw IOException("Image compressée trop volumineuse.") }
        return StagedArticleImage(id, file)
    }

    fun commit(articleId: String, image: StagedArticleImage): File {
        val article = UUID.fromString(articleId).toString()
        val base = pending.canonicalFile
        if (!image.file.canonicalPath.startsWith(base.path + File.separator)) throw IOException("Image temporaire hors du dossier prévu.")
        val destDir = File(root, article)
        if (!destDir.exists() && !destDir.mkdirs()) throw IOException("Création du stockage de l’article impossible.")
        val destination = File(destDir, "${image.id}.jpg")
        if (!image.file.renameTo(destination)) { image.file.copyTo(destination, overwrite = false); if (!image.file.delete()) { destination.delete(); throw IOException("Nettoyage temporaire impossible.") } }
        return destination
    }

    fun rollback(articleId: String, imageIds: Collection<String>) { imageIds.forEach { File(root, "$articleId/$it.jpg").delete() } }
    fun cleanup(articleId: String, retainedNames: Set<String>) {
        File(root, articleId).listFiles()?.forEach { if (it.name !in retainedNames) it.delete() }
    }
    fun file(articleId: String, imageName: String): File {
        require(imageName.matches(Regex("[0-9a-fA-F-]{36}\\.jpg")))
        val dir = File(root, UUID.fromString(articleId).toString()).canonicalFile
        val result = File(dir, imageName).canonicalFile
        require(result.path.startsWith(dir.path + File.separator))
        return result
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val ratio = minOf(1f, MAX_EDGE.toFloat() / max(bitmap.width, bitmap.height))
        return if (ratio < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
    }
    private fun orient(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply { when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f); ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f); ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f); ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); preScale(-1f, 1f) }; ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); preScale(-1f, 1f) }
        } }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    companion object { const val MAX_EDGE = 2560; const val MAX_INPUT_BYTES = 20 * 1024 * 1024; const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024 }
}

data class StagedArticleImage(val id: String, val file: File)
