package fr.thesdupalais.data.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class BackupPreviewOptions(val includedSets: Set<String>, val previews: List<BackupArchivePreview>)

/** SAF/Room/DataStore read boundary for preview. This class contains no mutating operations. */
class BackupArchivePreviewReader(
    private val contentResolver: ContentResolver,
    private val localExporter: BackupArchiveExporter,
) {
    suspend fun read(uri: Uri): BackupPreviewOptions = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size().toLong() + n > BackupArchiveValidator.MAX_ARCHIVE_BYTES) {
                    throw BackupArchiveValidator.ValidationException("ZIP", "L’archive dépasse la limite compressée de 50 Mio.")
                }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        } ?: throw IOException("Le fichier sélectionné n’a pas pu être lu.")
        val inputValidated = BackupArchiveValidator.validate(bytes)
        val localBytes = localExporter.createArchive(setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val localValidated = BackupArchiveValidator.validate(localBytes)
        val sets = inputValidated.includedSets.sorted()
        val selectionOptions = mutableListOf<Set<String>>()
        for (mask in 1 until (1 shl sets.size)) {
            selectionOptions += sets.indices.filter { mask and (1 shl it) != 0 }.map { sets[it] }.toSet()
        }
        val previews = selectionOptions.map { selected ->
            BackupArchivePreviewAnalyzer.analyze(bytes, inputValidated, localValidated, selected)
        }
        BackupPreviewOptions(inputValidated.includedSets, previews)
    }
}
