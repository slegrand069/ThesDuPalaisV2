package fr.thesdupalais.data.database

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/** Imports camera and Photo Picker content into app-private JPEG storage. */
class TeaPhotoStore(private val resolver: ContentResolver, private val filesDir: File) {
    private val root get() = File(filesDir, "tea-photos")
    private val pendingRoot get() = File(filesDir, "tea-photos-pending")

    fun import(uri: Uri): TeaPhotoDraft {
        if (!root.exists() && !root.mkdirs()) throw IOException("Le stockage des photos est indisponible.")
        if (!pendingRoot.exists() && !pendingRoot.mkdirs()) throw IOException("Le stockage temporaire est indisponible.")
        val source = resolver.openInputStream(uri) ?: throw IOException("La photo sélectionnée est illisible.")
        val bytes = source.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > 40 * 1024 * 1024) throw IOException("La photo dépasse la taille maximale de 40 Mo.")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Le format de la photo n’est pas pris en charge.")
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("La photo ne peut pas être décodée.")
        val oriented = orient(bitmap, bytes)
        val id = UUID.randomUUID()
        val temp = File(pendingRoot, "$id.jpg")
        try {
            FileOutputStream(temp).use { output ->
                if (!oriented.compress(Bitmap.CompressFormat.JPEG, 88, output)) throw IOException("La compression de la photo a échoué.")
                output.fd.sync()
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        } finally {
            if (oriented !== bitmap) oriented.recycle()
            bitmap.recycle()
        }
        if (temp.length() > MAX_BYTES) { temp.delete(); throw IOException("La photo compressée dépasse la taille maximale autorisée.") }
        return TeaPhotoDraft(id, "tea-photos/$id.jpg", isPrimary = false, pendingFile = temp)
    }

    private fun orient(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); preScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); preScale(-1f, 1f) }
            }
        }
        var result = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val factor = minOf(1f, MAX_EDGE.toFloat() / max(result.width, result.height))
        if (factor < 1f) result = Bitmap.createScaledBitmap(result, (result.width * factor).toInt().coerceAtLeast(1), (result.height * factor).toInt().coerceAtLeast(1), true)
        return result
    }

    fun finalizePending(photo: TeaPhotoDraft): File {
        if (photo.relativePath != "tea-photos/${photo.id}.jpg") throw IOException("Référence interne de photo invalide.")
        val source = photo.pendingFile ?: return File(filesDir, photo.relativePath)
        val pendingBase = pendingRoot.canonicalFile
        if (!source.canonicalPath.startsWith(pendingBase.path + File.separator)) throw IOException("Fichier temporaire de photo hors du dossier prévu.")
        val dest = File(filesDir, photo.relativePath)
        if (!dest.parentFile!!.exists() && !dest.parentFile!!.mkdirs()) throw IOException("Le dossier des photos est indisponible.")
        if (!source.renameTo(dest)) {
            source.copyTo(dest, overwrite = false)
            if (!source.delete()) { dest.delete(); throw IOException("Le fichier temporaire de photo n’a pas pu être nettoyé.") }
        }
        return dest
    }

    fun deletePending(photo: TeaPhotoDraft) { photo.pendingFile?.delete() }
    fun deleteRelative(path: String) { if (path.startsWith("tea-photos/") && !path.contains("..")) File(filesDir, path).delete() }

    companion object { const val MAX_EDGE = 2560; const val MAX_BYTES = 8 * 1024 * 1024 }
}

data class TeaPhotoDraft(val id: UUID, val relativePath: String, val isPrimary: Boolean, val pendingFile: File? = null)

object TeaPhotoSelection {
    fun normalize(photos: List<TeaPhotoDraft>): List<TeaPhotoDraft> {
        require(photos.size <= 3) { "Un thé peut avoir au maximum trois photos." }
        require(photos.map { it.id }.distinct().size == photos.size) { "Une photo est présente plusieurs fois." }
        require(photos.count { it.isPrimary } <= 1) { "Une seule photo peut être principale." }
        val selected = photos.indexOfFirst { it.isPrimary }.let { if (it >= 0) it else photos.indices.firstOrNull() ?: -1 }
        return photos.mapIndexed { index, photo -> photo.copy(isPrimary = index == selected) }
    }

    fun remove(photos: List<TeaPhotoDraft>, photoId: UUID): List<TeaPhotoDraft> {
        val removedPrimary = photos.firstOrNull { it.id == photoId }?.isPrimary == true
        val remaining = photos.filterNot { it.id == photoId }
        return if (removedPrimary) normalize(remaining.mapIndexed { index, photo -> photo.copy(isPrimary = index == 0) }) else normalize(remaining)
    }

    fun setPrimary(photos: List<TeaPhotoDraft>, photoId: UUID): List<TeaPhotoDraft> {
        require(photos.any { it.id == photoId }) { "La photo sélectionnée n’existe pas." }
        return photos.map { it.copy(isPrimary = it.id == photoId) }
    }
}
