package fr.thesdupalais.data.backup

import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

enum class MergeDisposition { ADD, UPDATE_PROPOSED, IDENTICAL, PRESERVED, CONFLICT, NOT_COMPARABLE }

data class MergeItem(
    val entityType: String,
    val identity: String,
    val disposition: MergeDisposition,
    val changedFields: List<String> = emptyList(),
    val details: List<String> = emptyList(),
)

data class BackupArchiveMergePlan(
    val archiveSha256: String,
    val localSnapshotSha256: String,
    val selectedSets: Set<String>,
    val items: List<MergeItem>,
    val blockingConflicts: List<String>,
    val limitations: List<String>,
) {
    val canApplyAfterConfirmation: Boolean get() = blockingConflicts.isEmpty()
}

/** Pure read-only planner. Inputs are validated backup ZIPs; this class has no storage access. */
object BackupArchiveMergePlanner {
    private val supportedSets = setOf("teas", "configuration")
    private val teaScalarFields = listOf(
        "legacyId", "name", "categoryId", "description", "technicalInformation", "personalNotes",
        "olfactoryRating", "tasteRating", "brewTemperatureCelsius", "infusionDurationSeconds",
        "statusId", "containerId", "archivedAt",
    )
    private val associationFields = listOf(
        "countryIds", "regionIds", "aromaIds", "aromaticProfileIds", "momentIds", "badgeIds",
    )

    /**
     * The local snapshot is a backup ZIP produced from the current Room/DataStore state.
     * Archive identity is tied to the exact selected bytes; local identity hashes canonical payloads.
     */
    fun plan(archiveBytes: ByteArray, localSnapshotBytes: ByteArray, selectedSets: Set<String>): BackupArchiveMergePlan {
        require(selectedSets.isNotEmpty()) { "Sélectionnez au moins un ensemble à fusionner." }
        require(selectedSets.all { it in supportedSets }) {
            "La fusion des articles n’est pas disponible tant que leur modèle et leur lecteur Android ne sont pas implémentés."
        }
        val incoming = BackupArchiveValidator.validate(archiveBytes)
        val local = BackupArchiveValidator.validate(localSnapshotBytes)
        require(selectedSets.all { it in incoming.includedSets }) { "La sélection contient un ensemble absent de l’archive." }
        val incomingTeas = incoming.json("data/teas.json")
        val incomingConfig = incoming.json("data/configuration.json")
        val localTeas = local.json("data/teas.json")
        val localConfig = local.json("data/configuration.json")
        val incomingRefs = mutableListOf<JSONObject>().apply {
            if ("teas" in selectedSets) addAll(incomingTeas?.array("references").orEmpty())
            if ("configuration" in selectedSets) addAll(incomingConfig?.array("references").orEmpty())
        }.distinctBy { it.getString("id") }
        val localRefs = localConfig?.array("references").orEmpty()
        val conflicts = mutableListOf<String>()
        val items = mutableListOf<MergeItem>()
        val referenceMap = resolveReferences(incomingRefs, localRefs, conflicts, items)

        if ("teas" in selectedSets) {
            val oldTeas = localTeas?.array("teas").orEmpty()
            val newTeas = incomingTeas!!.array("teas")
            val oldById = oldTeas.associateBy { it.getString("id") }
            val oldByLegacy = oldTeas.filter { it.longOrNull("legacyId") != null }.associateBy { it.getLong("legacyId") }
            val oldTastings = localTeas?.array("tastings").orEmpty()
            val newTastings = incomingTeas.array("tastings")
            val oldMovements = localTeas?.array("stockMovements").orEmpty()
            val newMovements = incomingTeas.array("stockMovements")

            newTeas.forEach { tea ->
                val id = tea.getString("id")
                val same = oldById[id]
                val legacy = tea.longOrNull("legacyId")?.let(oldByLegacy::get)
                if (legacy != null && legacy.getString("id") != id) {
                    val message = "Thé $id : legacyId ${tea.longOrNull("legacyId")} appartient au thé local ${legacy.getString("id")}."
                    conflicts += message
                    items += MergeItem("Thé", id, MergeDisposition.CONFLICT, listOf("legacyId"), listOf(message))
                } else if (same == null) {
                    items += MergeItem("Thé", id, MergeDisposition.ADD, teaScalarFields + associationFields + "stockMilliGrams")
                } else {
                    val normalizedTea = JSONObject(tea.toString())
                    listOf("categoryId", "statusId", "containerId").forEach { field ->
                        if (!normalizedTea.isNull(field)) normalizedTea.put(field, referenceMap[normalizedTea.getString(field)] ?: normalizedTea.getString(field))
                    }
                    val scalarChanges = changedFields(normalizedTea, same, teaScalarFields)
                    val associationDetails = associationFields.mapNotNull { key ->
                        val incomingIds = remapIds(tea.arrayStrings(key), referenceMap)
                        val localIds = same.arrayStrings(key).toSet()
                        val added = incomingIds - localIds
                        val kept = localIds - incomingIds.toSet()
                        when {
                            added.isNotEmpty() && kept.isNotEmpty() -> "$key : ajout ${added.sorted().joinToString()}; conservation ${kept.sorted().joinToString()}."
                            added.isNotEmpty() -> "$key : ajout ${added.sorted().joinToString()}."
                            kept.isNotEmpty() -> "$key : conservation ${kept.sorted().joinToString()} absente(s) de l’archive."
                            else -> null
                        }
                    }
                    val stockFields = assessStock(id, tea, same, oldMovements, newMovements, conflicts, items)
                    val fields = scalarChanges + associationDetails.map { it.substringBefore(" :") } + stockFields
                    val disposition = when {
                        fields.isNotEmpty() || associationDetails.isNotEmpty() -> MergeDisposition.UPDATE_PROPOSED
                        else -> MergeDisposition.IDENTICAL
                    }
                    items += MergeItem("Thé", id, disposition, fields.distinct(), fieldDetails(normalizedTea, same, scalarChanges) + associationDetails)
                }
            }
            compareHistory("Dégustation", oldTastings, newTastings, conflicts, items)
            compareHistory("Mouvement de stock", oldMovements, newMovements, conflicts, items)
            oldTeas.filter { it.getString("id") !in newTeas.map { row -> row.getString("id") }.toSet() }
                .forEach { items += MergeItem("Thé", it.getString("id"), MergeDisposition.PRESERVED, details = listOf("Absent de l’archive : conservé localement.")) }
        }

        if ("configuration" in selectedSets) {
            val incoming = incomingConfig!!
            val resolvedLocalIds = referenceMap.values.toSet()
            localRefs.filter { it.getString("id") !in resolvedLocalIds }.forEach { ref ->
                items += MergeItem("Référence locale", ref.getString("id"), MergeDisposition.PRESERVED,
                    details = listOf("Absente de la configuration importée : conservée localement."))
            }
            val localPrefs = localConfig?.obj("preferences")
            val incomingPrefs = incoming.obj("preferences")
            val changed = listOf("quantityPerCupMilliGrams", "lowStockThresholdMilliGrams")
                .filter { localPrefs == null || localPrefs.opt(it) != incomingPrefs.opt(it) }
            items += MergeItem(
                "Préférences métier", "configuration", when {
                    localPrefs == null -> MergeDisposition.ADD
                    changed.isEmpty() -> MergeDisposition.IDENTICAL
                    else -> MergeDisposition.UPDATE_PROPOSED
                }, changed,
                changed.map { key -> "$key : ${localPrefs?.opt(key) ?: "absent"} → ${incomingPrefs.opt(key)} (confirmation requise)." },
            )
        }

        val archiveHash = sha256(archiveBytes)
        return BackupArchiveMergePlan(
            archiveSha256 = archiveHash,
            localSnapshotSha256 = localFingerprint(local),
            selectedSets = selectedSets.toSortedSet(),
            items = items.sortedWith(compareBy<MergeItem> { it.entityType }.thenBy { it.identity }.thenBy { it.disposition.name }),
            blockingConflicts = conflicts.distinct().sorted(),
            limitations = listOf(
                "Le plan est en lecture seule et ne peut pas être appliqué par ce composant.",
                "Les photos de thé ne sont pas transportées par le contrat v1 et doivent rester inchangées.",
            ),
        )
    }

    private fun resolveReferences(
        incoming: List<JSONObject>, local: List<JSONObject>, conflicts: MutableList<String>, items: MutableList<MergeItem>,
    ): Map<String, String> {
        val byId = local.associateBy { it.getString("id") }
        val bySemantic = local.filter { it.stringOrNull("semanticKey") != null }
            .groupBy { it.getString("kind") + "|" + it.getString("semanticKey") }
        val remap = mutableMapOf<String, String>()
        incoming.sortedWith(compareBy<JSONObject> { referenceOrder(it.getString("kind")) }.thenBy { it.getString("id") }).forEach { ref ->
            val id = ref.getString("id"); val kind = ref.getString("kind"); val key = ref.stringOrNull("semanticKey")
            val incomingParent = ref.stringOrNull("parentCountryId")?.let { remap[it] ?: it }
            val byUuid = byId[id]
            val semanticMatches = key?.let { bySemantic["$kind|$it"].orEmpty() }.orEmpty()
            val candidates = (listOfNotNull(byUuid) + semanticMatches).distinctBy { it.getString("id") }
            val conflict = when {
                candidates.size > 1 -> "Référence $id : UUID et clé sémantique désignent des références locales différentes."
                byUuid != null && byUuid.getString("kind") != kind -> "Référence $id : collision UUID avec le type ${byUuid.getString("kind")} au lieu de $kind."
                semanticMatches.size > 1 -> "Référence $id : clé sémantique ambiguë ($kind/$key)."
                semanticMatches.isNotEmpty() && semanticMatches.single().stringOrNull("parentCountryId") != incomingParent -> "Référence $id : collision de pays parent incompatible pour $kind/$key."
                else -> null
            }
            if (conflict != null) {
                conflicts += conflict
                items += MergeItem("Référence", id, MergeDisposition.CONFLICT, details = listOf(conflict))
                return@forEach
            }
            val match = candidates.singleOrNull()
            if (match == null) {
                val collision = local.firstOrNull {
                    it.getString("kind") == kind && it.stringOrNull("parentCountryId") == ref.stringOrNull("parentCountryId") &&
                        ReferenceLabelNormalizer.normalize(it.getString("label")) == ReferenceLabelNormalizer.normalize(ref.getString("label"))
                }
                if (collision != null) {
                    val message = "Référence $id : collision de libellé avec ${collision.getString("id")}; pas de résolution par libellé seul."
                    conflicts += message
                    items += MergeItem("Référence", id, MergeDisposition.CONFLICT, details = listOf(message))
                } else {
                    remap[id] = id
                    val parent = ref.stringOrNull("parentCountryId")
                    val mappedParent = parent?.let { remap[it] ?: it }
                    items += MergeItem("Référence", id, MergeDisposition.ADD, listOf("définition complète"),
                        if (parent != null && parent != mappedParent) listOf("Pays parent $parent résolu vers $mappedParent.") else emptyList())
                }
            } else {
                remap[id] = match.getString("id")
                val normalizedRef = JSONObject(ref.toString()).put("parentCountryId", incomingParent ?: JSONObject.NULL)
                val fields = changedFields(normalizedRef, match, listOf("kind", "semanticKey", "label", "parentCountryId", "archivedAt", "displayPriority"))
                val labelCollision = if ("label" in fields) local.firstOrNull {
                    it.getString("id") != match.getString("id") && it.getString("kind") == kind &&
                        it.stringOrNull("parentCountryId") == incomingParent &&
                        ReferenceLabelNormalizer.normalize(it.getString("label")) == ReferenceLabelNormalizer.normalize(ref.getString("label"))
                } else null
                if (labelCollision != null) {
                    val message = "Référence $id : le nouveau libellé entre en collision avec ${labelCollision.getString("id")}."
                    conflicts += message
                    items += MergeItem("Référence", match.getString("id"), MergeDisposition.CONFLICT, fields, listOf(message))
                } else if (fields.any { it in setOf("kind", "semanticKey", "parentCountryId") }) {
                    val message = "Référence $id : modification d’identité ou de parent non compatible (${fields.joinToString()})."
                    conflicts += message
                    items += MergeItem("Référence", id, MergeDisposition.CONFLICT, fields, listOf(message))
                } else if (fields.isEmpty()) {
                    items += MergeItem("Référence", match.getString("id"), MergeDisposition.IDENTICAL,
                        details = if (match.getString("id") != id) listOf("UUID d’archive $id résolu vers l’identité locale ${match.getString("id")}.") else emptyList())
                } else if (key != null) {
                    items += MergeItem("Référence standard", match.getString("id"), MergeDisposition.PRESERVED, fields,
                        buildList {
                            if (match.getString("id") != id) add("UUID d’archive $id résolu vers l’identité locale ${match.getString("id")}.")
                            addAll(fieldDetails(normalizedRef, match, fields))
                            add("Définition locale conservée par défaut; mise à jour compatible proposée mais à confirmer.")
                        })
                } else {
                    items += MergeItem("Référence personnalisée", match.getString("id"), MergeDisposition.UPDATE_PROPOSED, fields,
                        buildList {
                            if (match.getString("id") != id) add("UUID d’archive $id résolu vers l’identité locale ${match.getString("id")}.")
                            addAll(fieldDetails(normalizedRef, match, fields))
                            add("Mise à jour compatible soumise à confirmation.")
                        })
                }
            }
        }
        return remap
    }

    private fun assessStock(
        teaId: String, incomingTea: JSONObject, localTea: JSONObject,
        localRows: List<JSONObject>, incomingRows: List<JSONObject>, conflicts: MutableList<String>, items: MutableList<MergeItem>,
    ): List<String> {
        val localForTea = localRows.filter { it.getString("teaId") == teaId }
        val incomingForTea = incomingRows.filter { it.getString("teaId") == teaId }
        val localById = localForTea.associateBy { it.getString("id") }
        val combined = localForTea.toMutableList()
        incomingForTea.forEach { row ->
            val old = localById[row.getString("id")]
            if (old == null) combined += row
            else if (canonical(old) != canonical(row)) {
                val message = "Mouvement ${row.getString("id")} du thé $teaId : même UUID avec un contenu différent."
                conflicts += message
                items += MergeItem("Mouvement de stock", row.getString("id"), MergeDisposition.CONFLICT, details = listOf(message))
            }
        }
        val localBalance = localTea.longOrNull("stockMilliGrams")
        val incomingBalance = incomingTea.longOrNull("stockMilliGrams")
        val changed = localBalance != incomingBalance
        if (localForTea.isEmpty() && incomingForTea.isNotEmpty()) return listOf("stockMilliGrams")
        if (incomingForTea.isEmpty() && localForTea.isNotEmpty() && incomingBalance == null) {
            return emptyList() // unknown incoming stock carries no authority over known local history
        }
        if (localBalance != null && incomingBalance != null && localBalance != incomingBalance) {
            val message = "Thé $teaId : stocks local ($localBalance) et archive ($incomingBalance) divergents; aucune réconciliation de mouvement n’est démontrée."
            conflicts += message
            items += MergeItem("Stock", teaId, MergeDisposition.CONFLICT, listOf("stockMilliGrams", "stockMovements"), listOf(message))
            return emptyList()
        }
        val chronologyError = validateMergedMovements(teaId, combined, incomingTea, localTea)
        if (chronologyError != null) {
            conflicts += chronologyError
            items += MergeItem("Stock", teaId, MergeDisposition.CONFLICT, listOf("stockMovements", "stockMilliGrams"), listOf(chronologyError))
            return emptyList()
        }
        return if (changed && localBalance == null && incomingBalance != null) listOf("stockMilliGrams") else emptyList()
    }

    private fun validateMergedMovements(teaId: String, rows: List<JSONObject>, incomingTea: JSONObject, localTea: JSONObject): String? {
        val sorted = rows.sortedWith(compareBy<JSONObject> { it.getString("occurredAt") }.thenBy { it.getString("recordedAt") }.thenBy { it.getString("id") })
        val localKnown = localTea.longOrNull("stockMilliGrams")
        val incomingKnown = incomingTea.longOrNull("stockMilliGrams")
        if (sorted.isEmpty()) return if (localKnown != null || incomingKnown != null) "Thé $teaId : stock connu sans historique de mouvements fusionnable." else null
        var balance: Long? = null
        for (row in sorted) {
            val kind = row.getString("kind")
            if (kind == "INVENTORY") {
                if (balance != null) return "Thé $teaId : plusieurs soldes INVENTORY empêchent d’établir un historique fusionné unique."
                balance = row.getLong("stockAfterMilliGrams")
            } else {
                val previous = balance ?: return "Thé $teaId : le mouvement ${row.getString("id")} précède tout solde INVENTORY dans l’historique fusionné."
                val delta = row.opt("deltaMilliGrams") as? Number ?: return "Thé $teaId : delta absent dans l’historique fusionné."
                val next = try { Math.addExact(previous, delta.toLong()) } catch (_: ArithmeticException) { return "Thé $teaId : dépassement numérique dans l’historique fusionné." }
                if (next < 0 || next != row.getLong("stockAfterMilliGrams")) return "Thé $teaId : le mouvement ${row.getString("id")} ne respecte pas le solde précédent après fusion."
                balance = next
            }
        }
        if (balance != localKnown && balance != incomingKnown) return "Thé $teaId : solde final fusionné $balance différent des stocks connus local/archive ($localKnown / $incomingKnown)."
        return null
    }

    private fun compareHistory(type: String, local: List<JSONObject>, incoming: List<JSONObject>, conflicts: MutableList<String>, items: MutableList<MergeItem>) {
        val oldById = local.associateBy { it.getString("id") }
        val incomingIds = incoming.map { it.getString("id") }.toSet()
        incoming.sortedBy { it.getString("id") }.forEach { row ->
            val id = row.getString("id"); val old = oldById[id]
            when {
                old == null -> items += MergeItem(type, id, MergeDisposition.ADD)
                canonical(old) == canonical(row) -> items += MergeItem(type, id, MergeDisposition.IDENTICAL)
                else -> {
                    val message = "$type $id : même UUID avec un contenu différent."
                    conflicts += message
                    items += MergeItem(type, id, MergeDisposition.CONFLICT, details = listOf(message))
                }
            }
        }
        local.filter { it.getString("id") !in incomingIds }.sortedBy { it.getString("id") }
            .forEach { items += MergeItem(type, it.getString("id"), MergeDisposition.PRESERVED, details = listOf("Absent de l’archive : historique conservé.")) }
    }

    private fun changedFields(a: JSONObject, b: JSONObject, fields: List<String>): List<String> = fields.filter { canonical(a.opt(it)) != canonical(b.opt(it)) }
    private fun fieldDetails(incoming: JSONObject, local: JSONObject, fields: List<String>) = fields.map { field ->
        "$field : ${display(local.opt(field))} → ${display(incoming.opt(field))}."
    }
    private fun display(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> value
        else -> value.toString()
    }
    private fun referenceOrder(kind: String) = when (kind) { "COUNTRY" -> 0; "REGION" -> 2; else -> 1 }
    private fun remapIds(ids: List<String>, mapping: Map<String, String>) = ids.map { mapping[it] ?: it }.toSet()
    private fun localFingerprint(archive: BackupArchiveValidator.ValidatedArchive): String {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.entries.toSortedMap().forEach { (path, bytes) ->
            digest.update(path.toByteArray(StandardCharsets.UTF_8)); digest.update(0)
            digest.update(canonicalJson(bytes).toByteArray(StandardCharsets.UTF_8)); digest.update(0)
        }
        return digest.digest().hex()
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun canonicalJson(bytes: ByteArray): String = canonical(JSONObject(String(bytes, StandardCharsets.UTF_8)))
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { key -> "${JSONObject.quote(key)}:${canonical(value.opt(key))}" }
        is JSONArray -> (0 until value.length()).map { canonical(value.opt(it)) }.sorted().joinToString(prefix = "[", postfix = "]")
        JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value?.toString() ?: "null"
    }
    private fun BackupArchiveValidator.ValidatedArchive.json(path: String) = entries[path]?.let { JSONObject(String(it, StandardCharsets.UTF_8)) }
    private fun JSONObject.array(key: String): List<JSONObject> = (0 until getJSONArray(key).length()).map { getJSONArray(key).getJSONObject(it) }
    private fun JSONObject.arrayStrings(key: String): List<String> = (0 until getJSONArray(key).length()).map { getJSONArray(key).getString(it) }
    private fun JSONObject.obj(key: String) = getJSONObject(key)
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else opt(key).let { (it as? Number)?.toLong() }
}
