package fr.thesdupalais.data.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BackupArchiveMergePreviewOptions(
    val backupId: String,
    val createdAt: String,
    val includedSets: Set<String>,
    val plans: List<BackupArchiveMergePlan>,
    val unsupportedSets: Set<String>,
    val businessLabels: Map<String, String> = emptyMap(),
    val teaNames: Map<String, String> = emptyMap(),
    val historyTeaNames: Map<String, String> = emptyMap(),
)

/** SAF read boundary for merge preview. Creates only an in-memory local backup snapshot. */
class BackupArchiveMergePreviewReader(
    private val contentResolver: ContentResolver,
    private val localExporter: BackupArchiveExporter,
) {
    suspend fun read(uri: Uri): BackupArchiveMergePreviewOptions {
        val archiveBytes = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { input ->
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
        }
        return previewBytes(archiveBytes)
    }

    internal suspend fun previewBytes(archiveBytes: ByteArray): BackupArchiveMergePreviewOptions = withContext(Dispatchers.IO) {
        if (archiveBytes.size.toLong() > BackupArchiveValidator.MAX_ARCHIVE_BYTES) {
            throw BackupArchiveValidator.ValidationException("ZIP", "L’archive dépasse la limite compressée de 50 Mio.")
        }
        val archive = BackupArchiveValidator.validate(archiveBytes)
        val localBytes = localExporter.createArchive(setOf(BackupExportSet.TEAS, BackupExportSet.CONFIGURATION))
        val local = BackupArchiveValidator.validate(localBytes)
        val supported = archive.includedSets.intersect(setOf("teas", "configuration"))
        val selections = buildList {
            if ("teas" in supported) add(setOf("teas"))
            if ("configuration" in supported) add(setOf("configuration"))
            if (supported.containsAll(setOf("teas", "configuration"))) add(setOf("teas", "configuration"))
        }
        val plans = selections.map { selected -> BackupArchiveMergePlanner.plan(archiveBytes, localBytes, selected) }
        val labels = mutableMapOf<String, String>()
        val teaNames = mutableMapOf<String, String>()
        val historyTeaNames = mutableMapOf<String, String>()
        listOf(archive, local).forEach { validated ->
            validated.entries["data/configuration.json"]?.let { bytes ->
                val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
                val refs = root.optJSONArray("references")
                if (refs != null) for (index in 0 until refs.length()) {
                    val ref = refs.getJSONObject(index)
                    labels[ref.optString("id")] = ref.optString("label", ref.optString("kind", "Référence"))
                }
            }
            validated.entries["data/teas.json"]?.let { bytes ->
                val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
                val teas = root.optJSONArray("teas")
                if (teas != null) for (index in 0 until teas.length()) {
                    val tea = teas.getJSONObject(index)
                    teaNames[tea.optString("id")] = tea.optString("name", "Thé sans nom")
                }
                listOf("tastings", "stockMovements").forEach { key ->
                    val rows = root.optJSONArray(key)
                    if (rows != null) for (index in 0 until rows.length()) {
                        val row = rows.getJSONObject(index)
                        val teaName = teaNames[row.optString("teaId")]
                        if (!teaName.isNullOrBlank()) historyTeaNames[row.optString("id")] = teaName
                    }
                }
            }
        }
        BackupArchiveMergePreviewOptions(
            backupId = archive.backupId,
            createdAt = archive.createdAt,
            includedSets = archive.includedSets,
            plans = plans,
            unsupportedSets = archive.includedSets - supported,
            businessLabels = labels,
            teaNames = teaNames,
            historyTeaNames = historyTeaNames,
        )
    }
}
