package fr.thesdupalais.data.backup

import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

enum class PreviewDisposition { ADDED, MODIFIED, IDENTICAL, REMOVED, PRESERVED, CONFLICT, NOT_COMPARABLE }

data class PreviewCount(val disposition: PreviewDisposition, val count: Int)
data class PreviewGroup(
    val name: String,
    val counts: List<PreviewCount>,
    val blockingConflicts: List<String> = emptyList(),
    val details: List<String> = emptyList(),
)
data class BackupArchivePreview(
    val backupId: String,
    val createdAt: String,
    val archiveSha256: String,
    val archiveSets: Set<String>,
    val selectedSets: Set<String>,
    val archiveCounts: Map<String, Int>,
    val groups: List<PreviewGroup>,
    val limitations: List<String>,
) {
    val hasBlockingConflicts: Boolean get() = groups.any { it.blockingConflicts.isNotEmpty() }
}

/** Pure comparison of two already validated archives. It has no Room, DataStore or file access. */
object BackupArchivePreviewAnalyzer {
    private val previewable = setOf("teas", "configuration", "articles")

    fun analyze(
        archiveBytes: ByteArray,
        archive: BackupArchiveValidator.ValidatedArchive,
        local: BackupArchiveValidator.ValidatedArchive,
        selectedSets: Set<String>,
    ): BackupArchivePreview {
        require(selectedSets.isNotEmpty()) { "Sélectionnez au moins un ensemble présent dans l’archive." }
        require(selectedSets.all { it in previewable && it in archive.includedSets }) { "La sélection contient un ensemble absent de l’archive." }
        val groups = mutableListOf<PreviewGroup>()
        val limits = mutableListOf<String>()
        val counts = archiveCounts(archive)
        val localRefs = local.json("data/configuration.json")?.array("references") ?: emptyList()
        val localTeas = local.json("data/teas.json")
        val retainedTeaRefs = if ("teas" !in selectedSets) usedReferenceIds(localTeas) else emptySet()

        if ("teas" in selectedSets) {
            val incomingTeas = archive.json("data/teas.json")!!
            val currentTeas = localTeas ?: JSONObject()
            groups += compareTeas(incomingTeas.array("teas"), currentTeas.arrayOrEmpty("teas"))
            groups += compareRows("Dégustations", incomingTeas.array("tastings"), currentTeas.arrayOrEmpty("tastings"))
            groups += compareRows("Mouvements de stock", incomingTeas.array("stockMovements"), currentTeas.arrayOrEmpty("stockMovements"))
            groups += compareReferences(
                "Références dépendantes des thés",
                incomingTeas.array("references"), localRefs,
                removeMissing = false,
                preserveIds = emptySet(),
            )
        }
        if ("configuration" in selectedSets) {
            val incoming = archive.json("data/configuration.json")!!
            val current = local.json("data/configuration.json")
            val futureTeaRefs = if ("teas" in selectedSets) {
                resolveReferenceUsageIds(archive.json("data/teas.json"), localRefs)
            } else emptySet()
            groups += compareReferences(
                "Référentiels de configuration",
                incoming.array("references"), localRefs,
                removeMissing = true,
                preserveIds = retainedTeaRefs + futureTeaRefs,
            )
            val incomingPrefs = incoming.obj("preferences")
            val currentPrefs = current?.obj("preferences")
            groups += comparePreference("Préférences métier", incomingPrefs, currentPrefs)
        }
        if ("articles" in selectedSets) {
            val incoming = archive.json("articles/index.json")!!.array("articles")
            groups += group("Articles (comparaison locale indisponible)", mapOf(PreviewDisposition.NOT_COMPARABLE to incoming.size))
            limits += "Les articles sont listés dans l’archive, mais cette version de l’application ne possède pas encore de modèle local pour les comparer."
        }
        limits += "Le contrat de sauvegarde v1 ne transporte pas les photos des thés; cette archive ne constitue pas une sauvegarde intégrale de l’application."
        return BackupArchivePreview(
            backupId = archive.backupId,
            createdAt = archive.createdAt,
            archiveSha256 = sha256(archiveBytes),
            archiveSets = archive.includedSets,
            selectedSets = selectedSets,
            archiveCounts = counts,
            groups = groups,
            limitations = limits.distinct(),
        )
    }

    private fun compareRows(name: String, incoming: List<JSONObject>, local: List<JSONObject>): PreviewGroup {
        val localById = local.associateBy { it.getString("id") }
        val incomingIds = incoming.map { it.getString("id") }.toSet()
        val counts = mutableMapOf<PreviewDisposition, Int>()
        val details = mutableListOf<String>()
        incoming.forEach { row ->
            val existing = localById[row.getString("id")]
            val status = when {
                existing == null -> PreviewDisposition.ADDED
                canonical(existing) == canonical(row) -> PreviewDisposition.IDENTICAL
                else -> PreviewDisposition.MODIFIED
            }
            counts[status] = counts.getOrDefault(status, 0) + 1
            details += "${row.optString("name").ifBlank { row.getString("id") }} — ${status.label()}"
        }
        local.filter { it.getString("id") !in incomingIds }.forEach {
            counts[PreviewDisposition.REMOVED] = counts.getOrDefault(PreviewDisposition.REMOVED, 0) + 1
            details += "${it.optString("name").ifBlank { it.getString("id") }} — supprimé par le remplacement"
        }
        return group(name, counts, details = details)
    }

    private fun compareTeas(incoming: List<JSONObject>, local: List<JSONObject>): PreviewGroup {
        val byId = local.associateBy { it.getString("id") }
        val byLegacyId = local.filter { it.nullableLong("legacyId") != null }.associateBy { it.getLong("legacyId") }
        val incomingIds = incoming.map { it.getString("id") }.toSet()
        val counts = mutableMapOf<PreviewDisposition, Int>()
        val details = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        incoming.forEach { tea ->
            val id = tea.getString("id")
            val sameId = byId[id]
            val legacyMatch = tea.nullableLong("legacyId")?.let(byLegacyId::get)
            val status = when {
                sameId != null && legacyMatch != null && legacyMatch.getString("id") != id -> {
                    conflicts += "${tea.getString("name")} : legacyId ${tea.getLong("legacyId")} appartient localement à ${legacyMatch.getString("id")}."
                    PreviewDisposition.CONFLICT
                }
                sameId == null && legacyMatch != null -> {
                    conflicts += "${tea.getString("name")} : legacyId ${tea.getLong("legacyId")} existe sous un autre UUID (${legacyMatch.getString("id")})."
                    PreviewDisposition.CONFLICT
                }
                sameId == null -> PreviewDisposition.ADDED
                canonical(sameId) == canonical(tea) -> PreviewDisposition.IDENTICAL
                else -> PreviewDisposition.MODIFIED
            }
            counts[status] = counts.getOrDefault(status, 0) + 1
            details += "${tea.getString("name")} ($id) — ${status.label()}"
        }
        local.filter { it.getString("id") !in incomingIds }.forEach {
            counts[PreviewDisposition.REMOVED] = counts.getOrDefault(PreviewDisposition.REMOVED, 0) + 1
            details += "${it.getString("name")} (${it.getString("id")}) — supprimé par le remplacement"
        }
        return group("Fiches de thé", counts, conflicts, details)
    }

    private fun compareReferences(
        name: String,
        incoming: List<JSONObject>,
        local: List<JSONObject>,
        removeMissing: Boolean,
        preserveIds: Set<String>,
    ): PreviewGroup {
        val counts = mutableMapOf<PreviewDisposition, Int>()
        val conflicts = mutableListOf<String>()
        val details = mutableListOf<String>()
        val localById = local.associateBy { it.getString("id") }
        val localBySemantic = local.filter { it.nullableString("semanticKey") != null }
            .groupBy { it.getString("kind") + "|" + it.getString("semanticKey") }
        val matchedLocalIds = mutableSetOf<String>()
        incoming.forEach { ref ->
            val id = ref.getString("id")
            val kind = ref.getString("kind")
            val semantic = ref.nullableString("semanticKey")
            val parent = ref.nullableString("parentCountryId")
            val byId = localById[id]
            val semanticMatches = semantic?.let { localBySemantic["$kind|$it"].orEmpty() }.orEmpty()
            val candidates = (listOfNotNull(byId) + semanticMatches).distinctBy { it.getString("id") }
            val exactIdentity = candidates.singleOrNull { candidate ->
                candidate.getString("kind") == kind &&
                    (semantic == null || candidate.nullableString("semanticKey") == semantic) &&
                    candidate.nullableString("parentCountryId") == parent
            }
            val differentIdLabelCollision = local.firstOrNull { candidate ->
                candidate.getString("id") != id &&
                    candidate.getString("kind") == kind &&
                    candidate.nullableString("parentCountryId") == parent &&
                    ReferenceLabelNormalizer.normalize(candidate.getString("label")) == ReferenceLabelNormalizer.normalize(ref.getString("label"))
            }
            val status = when {
                candidates.size > 1 -> {
                    conflicts += "${ref.getString("label")} : UUID et clé sémantique désignent plusieurs références locales."
                    PreviewDisposition.CONFLICT
                }
                byId != null && exactIdentity == null -> {
                    conflicts += "${ref.getString("label")} : l’UUID existe déjà avec un type, une clé sémantique ou un pays parent incompatible."
                    PreviewDisposition.CONFLICT
                }
                semanticMatches.isNotEmpty() && exactIdentity == null -> {
                    conflicts += "${ref.getString("label")} : clé sémantique ambiguë ou pays parent incompatible."
                    PreviewDisposition.CONFLICT
                }
                differentIdLabelCollision != null -> {
                    conflicts += "${ref.getString("label")} : collision de libellé avec une référence d’identité différente."
                    PreviewDisposition.CONFLICT
                }
                exactIdentity == null -> PreviewDisposition.ADDED
                else -> {
                    val matchedId = exactIdentity.getString("id")
                    matchedLocalIds += matchedId
                    if (canonicalReference(exactIdentity) == canonicalReference(ref)) PreviewDisposition.IDENTICAL else PreviewDisposition.MODIFIED
                }
            }
            counts[status] = counts.getOrDefault(status, 0) + 1
            details += "${ref.optString("label")} (${ref.getString("id")}) — ${status.label()}"
        }
        if (removeMissing) {
            local.filter { it.getString("id") !in matchedLocalIds && incoming.none { ref -> ref.getString("id") == it.getString("id") } }
                .forEach { ref ->
                    val status = if (ref.getString("id") in preserveIds) PreviewDisposition.PRESERVED else PreviewDisposition.REMOVED
                    counts[status] = counts.getOrDefault(status, 0) + 1
                    details += "${ref.optString("label")} (${ref.getString("id")}) — ${if (status == PreviewDisposition.PRESERVED) "conservé car utilisé localement ou requis par les thés" else "supprimé par le remplacement de configuration"}"
                }
        }
        return group(name, counts, conflicts, details)
    }

    private fun comparePreference(name: String, incoming: JSONObject, local: JSONObject?): PreviewGroup {
        val status = when {
            local == null -> PreviewDisposition.ADDED
            canonical(incoming) == canonical(local) -> PreviewDisposition.IDENTICAL
            else -> PreviewDisposition.MODIFIED
        }
        return group(name, mapOf(status to 1))
    }

    private fun usedReferenceIds(teas: JSONObject?): Set<String> {
        if (teas == null) return emptySet()
        val ids = mutableSetOf<String>()
        teas.array("teas").forEach { tea ->
            listOf("categoryId", "statusId", "containerId").forEach { key -> tea.optString(key).takeIf(String::isNotEmpty)?.let(ids::add) }
            listOf("countryIds", "regionIds", "aromaIds", "aromaticProfileIds", "momentIds", "badgeIds").forEach { key ->
                tea.optJSONArray(key)?.let { arr -> (0 until arr.length()).forEach { ids += arr.getString(it) } }
            }
        }
        val regions = teas.array("references").filter { it.optString("kind") == "REGION" }.associate { it.getString("id") to it.nullableString("parentCountryId") }
        regions.forEach { (regionId, parentId) -> if (regionId in ids && parentId != null) ids += parentId }
        return ids
    }

    private fun resolveReferenceUsageIds(teas: JSONObject?, localRefs: List<JSONObject>): Set<String> {
        val dependencies = teas?.array("references").orEmpty()
        val usedArchiveIds = usedReferenceIds(teas)
        val result = mutableSetOf<String>()
        dependencies.filter { it.getString("id") in usedArchiveIds }.forEach { dep ->
            val matches = localRefs.filter { local ->
                local.getString("id") == dep.getString("id") ||
                    (dep.nullableString("semanticKey") != null && local.getString("kind") == dep.getString("kind") && local.nullableString("semanticKey") == dep.nullableString("semanticKey"))
            }
            if (matches.size == 1) result += matches.single().getString("id")
        }
        return result
    }

    private fun archiveCounts(archive: BackupArchiveValidator.ValidatedArchive): Map<String, Int> = buildMap {
        archive.json("data/teas.json")?.let { root ->
            put("Thés", root.array("teas").size); put("Dégustations", root.array("tastings").size)
            put("Mouvements de stock", root.array("stockMovements").size); put("Références dépendantes", root.array("references").size)
        }
        archive.json("data/configuration.json")?.let { root ->
            put("Références de configuration", root.array("references").size)
            put("Préférences métier", root.obj("preferences").length())
        }
        archive.json("articles/index.json")?.let { root -> put("Articles", root.array("articles").size) }
    }

    private fun group(name: String, values: Map<PreviewDisposition, Int>, conflicts: List<String> = emptyList(), details: List<String> = emptyList()) =
        PreviewGroup(name, PreviewDisposition.entries.mapNotNull { status -> values[status]?.takeIf { it > 0 }?.let { PreviewCount(status, it) } }, conflicts, details)

    private fun canonicalReference(ref: JSONObject): String = canonical(JSONObject()
        .put("kind", ref.opt("kind")).put("semanticKey", ref.opt("semanticKey"))
        .put("label", ref.opt("label")).put("parentCountryId", ref.opt("parentCountryId"))
        .put("archivedAt", ref.opt("archivedAt")).put("displayPriority", ref.opt("displayPriority")))

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { key -> "${JSONObject.quote(key)}:${canonical(value.opt(key))}" }
        is JSONArray -> (0 until value.length()).map { canonical(value.opt(it)) }.sorted().joinToString(prefix = "[", postfix = "]")
        JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun PreviewDisposition.label() = when (this) {
        PreviewDisposition.ADDED -> "ajouté"
        PreviewDisposition.MODIFIED -> "modifié"
        PreviewDisposition.IDENTICAL -> "identique"
        PreviewDisposition.REMOVED -> "supprimé"
        PreviewDisposition.PRESERVED -> "conservé"
        PreviewDisposition.CONFLICT -> "conflit bloquant"
        PreviewDisposition.NOT_COMPARABLE -> "comparaison locale indisponible"
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun BackupArchiveValidator.ValidatedArchive.json(path: String) = entries[path]?.let { JSONObject(String(it, Charsets.UTF_8)) }
    private fun JSONObject.array(name: String) = (0 until getJSONArray(name).length()).map { getJSONArray(name).getJSONObject(it) }
    private fun JSONObject.arrayOrEmpty(name: String) = optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    private fun JSONObject.obj(name: String) = getJSONObject(name)
    private fun JSONObject.nullableString(name: String): String? = when (val value = opt(name)) {
        null, JSONObject.NULL -> null
        is String -> value
        else -> value.toString()
    }
    private fun JSONObject.nullableLong(name: String): Long? = when (val value = opt(name)) {
        null, JSONObject.NULL -> null
        is Number -> value.toLong()
        else -> null
    }
}
