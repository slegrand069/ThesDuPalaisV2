package fr.thesdupalais.data.backup

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import org.json.JSONArray
import org.json.JSONObject

/** Pure input metadata which is not carried by backup v1. Paths are app-private relative paths. */
data class TargetPhotoRecord(val id: String, val teaId: String, val relativePath: String, val position: Int)
data class TargetImportRecord(val datasetId: String, val contentSha256: String, val schemaVersion: Int)

enum class TargetOperation { REPLACE, MERGE }
data class ConfirmedMergeChange(val entityType: String, val identity: String, val field: String)

data class BackupTargetRequest(
    val archiveBytes: ByteArray,
    val localSnapshotBytes: ByteArray,
    val operation: TargetOperation,
    val selectedSets: Set<String>,
    val replacementConfirmed: Boolean = false,
    val confirmedChanges: Set<ConfirmedMergeChange> = emptySet(),
    val photos: List<TargetPhotoRecord> = emptyList(),
    val importHistory: List<TargetImportRecord> = emptyList(),
    val planner: BackupArchiveMergePlan? = null,
)

data class BackupTargetState(
    /** Complete, canonicalized v1 payloads for the supported sets. */
    val payloads: Map<String, String>,
    val photosToKeep: List<TargetPhotoRecord>,
    /** Historical rows remain present; these IDs must cease acting as current duplicate-import guards. */
    val supersededImportDatasetIds: Set<String>,
)

data class TargetChange(val set: String, val identity: String, val action: String, val fields: List<String> = emptyList())
data class BackupTargetFingerprints(
    val archiveSha256: String,
    val localSnapshotSha256: String,
    val operationSha256: String,
    val targetSha256: String,
)
data class BackupTargetResult(
    val state: BackupTargetState?,
    val changes: List<TargetChange>,
    val preserved: List<TargetChange>,
    val warnings: List<String>,
    val blockers: List<String>,
    val fingerprints: BackupTargetFingerprints,
) {
    val applicable: Boolean get() = state != null && blockers.isEmpty()
}

/**
 * Builds a full desired state in memory. It has no Room, DataStore, Android, clock, or file access.
 * The local snapshot is the validated, complete teas+configuration ZIP produced by the exporter.
 */
object BackupTargetStateBuilder {
    private val supported = setOf("teas", "configuration")
    private val teaScalars = listOf(
        "legacyId", "name", "categoryId", "description", "technicalInformation", "personalNotes",
        "olfactoryRating", "tasteRating", "brewTemperatureCelsius", "infusionDurationSeconds", "statusId",
        "containerId", "stockMilliGrams", "archivedAt",
    )
    private val associations = listOf("countryIds", "regionIds", "aromaIds", "aromaticProfileIds", "momentIds", "badgeIds")

    fun build(request: BackupTargetRequest): BackupTargetResult {
        val blockers = mutableListOf<String>()
        val changes = mutableListOf<TargetChange>()
        val preserved = mutableListOf<TargetChange>()
        val warnings = mutableListOf<String>()
        val archive = try { BackupArchiveValidator.validate(request.archiveBytes) } catch (e: Exception) {
            return failed(request, listOf("Archive invalide : ${e.message ?: "validation impossible"}"))
        }
        val local = try { BackupArchiveValidator.validate(request.localSnapshotBytes) } catch (e: Exception) {
            return failed(request, listOf("Instantané local invalide : ${e.message ?: "lecture impossible"}"))
        }
        if (request.selectedSets.isEmpty() || !request.selectedSets.all { it in supported }) blockers += "Sélection vide ou ensemble non pris en charge (les articles sont hors périmètre)."
        if (!archive.includedSets.containsAll(request.selectedSets)) blockers += "Un ensemble sélectionné est absent de l’archive."
        if (local.includedSets != supported) blockers += "L’instantané local doit contenir les thés et la configuration."
        val archiveHash = sha(request.archiveBytes)
        val plannerLocalHash = BackupArchiveMergePlanner.localFingerprint(local)
        val localHash = sha(buildString {
            append(plannerLocalHash)
            request.photos.sortedWith(compareBy<TargetPhotoRecord> { it.id }).forEach { append('|').append(it.id).append(':').append(it.teaId).append(':').append(it.relativePath).append(':').append(it.position) }
            request.importHistory.sortedBy { it.datasetId }.forEach { append('|').append(it.datasetId).append(':').append(it.contentSha256).append(':').append(it.schemaVersion) }
        })
        var plan = request.planner
        if (request.operation == TargetOperation.REPLACE && !request.replacementConfirmed) blockers += "Le remplacement doit être explicitement confirmé."
        if (request.operation == TargetOperation.MERGE) {
            val expectedPlan = try { BackupArchiveMergePlanner.plan(request.archiveBytes, request.localSnapshotBytes, request.selectedSets) }
            catch (e: Exception) { blockers += "Plan de fusion impossible : ${e.message ?: "erreur"}"; null }
            plan = request.planner ?: expectedPlan
            if (request.planner != null && expectedPlan != null && request.planner != expectedPlan) {
                blockers += "Le plan fourni est incomplet ou différent du plan recalculé à partir des mêmes données."
            }
            if (plan != null) {
                if (plan.archiveSha256 != archiveHash || plan.localSnapshotSha256 != plannerLocalHash || plan.selectedSets != request.selectedSets) blockers += "Le plan ne correspond plus à l’archive, à l’instantané local ou aux ensembles sélectionnés."
                if (plan.blockingConflicts.isNotEmpty()) blockers += plan.blockingConflicts
                if (plan.items.any { it.disposition == MergeDisposition.NOT_COMPARABLE }) blockers += "Le plan contient des éléments non comparables."
                if (plan.items.distinctBy { Triple(it.entityType, it.identity, it.disposition) }.size != plan.items.size) blockers += "Le plan est ambigu : plusieurs décisions portent sur la même identité."
            }
        }
        if (blockers.isNotEmpty()) return failed(request, blockers.distinct(), archiveHash, localHash)

        val localTea = obj(local, "data/teas.json")!!
        val localConfig = obj(local, "data/configuration.json")!!
        val incomingTea = obj(archive, "data/teas.json")
        val incomingConfig = obj(archive, "data/configuration.json")
        val payloads = mutableMapOf(
            "data/teas.json" to JSONObject(localTea.toString()),
            "data/configuration.json" to JSONObject(localConfig.toString()),
        )
        if (request.operation == TargetOperation.REPLACE) {
            replace(request.selectedSets, incomingTea, incomingConfig, payloads, changes, preserved, blockers)
        } else {
            merge(request, plan!!, incomingTea, incomingConfig, payloads, changes, preserved, blockers)
        }
        val targetTea = payloads["data/teas.json"]!!
        val targetConfig = payloads["data/configuration.json"]!!
        reconcileAndCheck(targetTea, targetConfig, blockers)

        val targetTeaIds = targetTea.array("teas").map { it.getString("id") }.toSet()
        val keptPhotos = request.photos.filter { it.teaId in targetTeaIds }.sortedWith(compareBy<TargetPhotoRecord> { it.teaId }.thenBy { it.position }.thenBy { it.id })
        val removedPhotos = request.photos.filter { it.teaId !in targetTeaIds }
        if (removedPhotos.isNotEmpty()) warnings += "${removedPhotos.size} photo(s) associée(s) à des thés retirés doivent être mises en quarantaine lors de l’application. Les images ne sont pas transportées par ZIP v1."
        val superseded = if (payloadCanonical(targetTea, targetConfig) != payloadCanonical(localTea, localConfig)) request.importHistory.map { it.datasetId }.toSortedSet() else emptySet()
        val state = if (blockers.isEmpty()) BackupTargetState(
            payloads = payloads.mapValues { canonicalJson(it.value) }.toSortedMap(),
            photosToKeep = keptPhotos,
            supersededImportDatasetIds = superseded,
        ) else null
        val opDigest = sha(canonicalRequest(request, archiveHash, localHash, plan))
        val targetDigest = state?.let { digestState(it) } ?: ""
        val fingerprints = BackupTargetFingerprints(archiveHash, localHash, opDigest, targetDigest)
        val changeOrder = compareBy<TargetChange> { it.set }.thenBy { it.identity }.thenBy { it.action }.thenBy { it.fields.joinToString() }
        return BackupTargetResult(state, changes.sortedWith(changeOrder), preserved.sortedWith(changeOrder), warnings.distinct().sorted(), blockers.distinct().sorted(), fingerprints)
    }

    private fun replace(
        selected: Set<String>, incomingTea: JSONObject?, incomingConfig: JSONObject?, payloads: MutableMap<String, JSONObject>,
        changes: MutableList<TargetChange>, preserved: MutableList<TargetChange>, blockers: MutableList<String>,
    ) {
        if ("teas" in selected) {
            if (incomingTea == null) blockers += "Données de thés absentes."
            else {
                val old = payloads.getValue("data/teas.json")
                val refs = combineReferences(old.array("references"), incomingTea.array("references"), blockers)
                val next = JSONObject(incomingTea.toString()).put("references", JSONArray(refs))
                reportRows("thé", old.array("teas"), next.array("teas"), changes)
                reportRows("dégustation", old.array("tastings"), next.array("tastings"), changes)
                reportRows("mouvement", old.array("stockMovements"), next.array("stockMovements"), changes)
                payloads["data/teas.json"] = next
                val currentConfig = payloads.getValue("data/configuration.json")
                if ("configuration" !in selected) {
                    val allRefs = combineReferences(currentConfig.array("references"), refs, blockers)
                    currentConfig.put("references", JSONArray(allRefs))
                }
            }
        } else preserved += TargetChange("teas", "*", "conservé (ensemble non sélectionné)")

        if ("configuration" in selected) {
            if (incomingConfig == null) blockers += "Configuration absente."
            else {
                val old = payloads.getValue("data/configuration.json")
                val targetTea = payloads.getValue("data/teas.json")
                val required = usedReferenceIds(targetTea)
                val incomingRefs = incomingConfig.array("references") + if ("teas" in selected) incomingTea?.array("references").orEmpty() else emptyList()
                val localRefs = old.array("references")
                val mergedRefs = replaceReferences(incomingRefs, localRefs, required, blockers, preserved)
                payloads["data/configuration.json"] = JSONObject(incomingConfig.toString()).put("references", JSONArray(mergedRefs))
                if (canonical(old.opt("preferences")) != canonical(incomingConfig.opt("preferences"))) changes += TargetChange("configuration", "preferences", "remplacé", listOf("business preferences"))
                reportRows("référence", localRefs, JSONArray(mergedRefs).objects(), changes)
            }
        } else preserved += TargetChange("configuration", "*", "conservé (ensemble non sélectionné)")
    }

    private fun replaceReferences(
        incoming: List<JSONObject>, local: List<JSONObject>, requiredIds: Set<String>, blockers: MutableList<String>, preserved: MutableList<TargetChange>,
    ): List<JSONObject> {
        val output = incoming.associateBy { it.getString("id") }.toMutableMap()
        val bySemantic = incoming.filter { !it.isNull("semanticKey") }.associateBy { it.getString("kind") + "|" + it.getString("semanticKey") }
        local.forEach { old ->
            val id = old.getString("id")
            val exact = output[id]
            val semantic = old.stringOrNull("semanticKey")?.let { bySemantic[old.getString("kind") + "|" + it] }
            if (exact != null && !sameIdentity(old, exact) || semantic != null && !sameIdentity(old, semantic)) {
                blockers += "Référence ${old.optString("label")} : collision d’identité ou définition incompatible pendant le remplacement."
            } else if (id in requiredIds && exact == null) {
                output[id] = old
                preserved += TargetChange("reference", id, "conservé car encore utilisé par des thés")
            }
        }
        return output.values.sortedBy { it.getString("id") }
    }

    private fun merge(
        request: BackupTargetRequest, plan: BackupArchiveMergePlan, incomingTea: JSONObject?, incomingConfig: JSONObject?,
        payloads: MutableMap<String, JSONObject>, changes: MutableList<TargetChange>, preserved: MutableList<TargetChange>, blockers: MutableList<String>,
    ) {
        val refMap = plan.referenceIdRemap
        val items = plan.items
        if ("teas" in request.selectedSets) {
            val local = payloads.getValue("data/teas.json")
            if (incomingTea == null) blockers += "Données de thés absentes."
            else {
                val targetRefs = payloads.getValue("data/configuration.json").array("references").associateBy { it.getString("id") }.toMutableMap()
                incomingTea.array("references").forEach { ref ->
                    val id = refMap[ref.getString("id")] ?: ref.getString("id")
                    if (id !in targetRefs) targetRefs[id] = remapReference(ref, refMap)
                }
                val localTeas = local.array("teas").associateBy { it.getString("id") }.toMutableMap()
                val localTastings = local.array("tastings").associateBy { it.getString("id") }.toMutableMap()
                val localMoves = local.array("stockMovements").associateBy { it.getString("id") }.toMutableMap()
                incomingTea.array("teas").forEach { raw ->
                    val id = raw.getString("id"); val old = localTeas[id]
                    val tea = remapTea(raw, refMap)
                    val item = items.singleOrNull { it.entityType == "Thé" && it.identity == id }
                    if (item == null) { blockers += "Plan incomplet : fiche de thé $id absente."; return@forEach }
                    if (old == null) { localTeas[id] = tea; changes += TargetChange("teas", id, "ajouté") }
                    else {
                        item.changedFields.forEach { field ->
                            val association = field in associations
                            val confirmed = request.confirmedChanges.any { it.entityType == "Thé" && it.identity == id && it.field == field }
                            if (association) {
                                val union = (old.arrayStrings(field) + tea.arrayStrings(field)).distinct().sorted()
                                if (union != old.arrayStrings(field).sorted()) changes += TargetChange("teas", id, "associations fusionnées", listOf(field))
                                old.put(field, JSONArray(union))
                            } else if (field in teaScalars && confirmed) {
                                old.put(field, tea.opt(field) ?: JSONObject.NULL)
                                changes += TargetChange("teas", id, "mis à jour après confirmation", listOf(field))
                            } else if (field in teaScalars) preserved += TargetChange("teas", id, "valeur locale conservée (confirmation absente)", listOf(field))
                            else blockers += "Plan incomplet ou champ inconnu pour le thé $id : $field."
                        }
                    }
                }
                addIncomingRows(incomingTea.array("tastings"), localTastings, "Dégustation", items, blockers, changes)
                addIncomingRows(incomingTea.array("stockMovements"), localMoves, "Mouvement de stock", items, blockers, changes)
                payloads["data/teas.json"] = JSONObject(local.toString())
                    .put("references", JSONArray(targetRefs.values.sortedBy { it.getString("id") }))
                    .put("teas", JSONArray(localTeas.values.sortedBy { it.getString("id") }))
                    .put("tastings", JSONArray(localTastings.values.sortedBy { it.getString("id") }))
                    .put("stockMovements", JSONArray(localMoves.values.sortedBy { it.getString("id") }))
                val configWithDependencies = payloads.getValue("data/configuration.json")
                configWithDependencies.put("references", JSONArray(targetRefs.values.sortedBy { it.getString("id") }))
            }
        } else preserved += TargetChange("teas", "*", "conservé (ensemble non sélectionné)")

        if ("configuration" in request.selectedSets) {
            if (incomingConfig == null) blockers += "Configuration absente."
            else {
                val config = payloads.getValue("data/configuration.json")
                val refs = config.array("references").associateBy { it.getString("id") }.toMutableMap()
                incomingConfig.array("references").forEach { raw ->
                    val id = refMap[raw.getString("id")] ?: raw.getString("id")
                    val old = refs[id]
                    val item = items.firstOrNull { it.identity == id && it.entityType.startsWith("Référence") }
                    if (old == null) { refs[id] = remapReference(raw, refMap); changes += TargetChange("configuration", id, "référence ajoutée") }
                    else if (item != null && item.disposition == MergeDisposition.UPDATE_PROPOSED) {
                        val incoming = remapReference(raw, refMap)
                        item.changedFields.forEach { field ->
                            if (request.confirmedChanges.any { it.identity == id && it.field == field }) {
                                old.put(field, incoming.opt(field) ?: JSONObject.NULL)
                                changes += TargetChange("configuration", id, "référence mise à jour après confirmation", listOf(field))
                            } else preserved += TargetChange("configuration", id, "référence locale conservée", listOf(field))
                        }
                    } else if (item?.disposition == MergeDisposition.PRESERVED) item.changedFields.forEach { field ->
                        preserved += TargetChange("configuration", id, "référence standard locale conservée par défaut", listOf(field))
                    }
                }
                config.put("references", JSONArray(refs.values.sortedBy { it.getString("id") }))
                val prefsItem = items.firstOrNull { it.entityType == "Préférences métier" }
                if (prefsItem != null) {
                    val prefs = JSONObject(config.getJSONObject("preferences").toString())
                    val next = incomingConfig.getJSONObject("preferences")
                    prefsItem.changedFields.forEach { field ->
                        if (request.confirmedChanges.any { it.entityType == "Préférences métier" && it.identity == "configuration" && it.field == field }) {
                            prefs.put(field, next.opt(field) ?: JSONObject.NULL)
                            changes += TargetChange("configuration", "preferences", "mis à jour après confirmation", listOf(field))
                        } else preserved += TargetChange("configuration", "preferences", "valeur locale conservée", listOf(field))
                    }
                    config.put("preferences", prefs)
                }
            }
        } else preserved += TargetChange("configuration", "*", "conservé (ensemble non sélectionné)")
    }

    private fun addIncomingRows(
        incoming: List<JSONObject>, local: MutableMap<String, JSONObject>, type: String, items: List<MergeItem>,
        blockers: MutableList<String>, changes: MutableList<TargetChange>,
    ) {
        incoming.forEach { row ->
            val id = row.getString("id"); val old = local[id]
            val item = items.singleOrNull { it.entityType == type && it.identity == id }
            if (item == null) blockers += "Plan incomplet : $type $id absent."
            else if (item.disposition == MergeDisposition.CONFLICT) blockers += item.details.ifEmpty { listOf("Conflit dans $type $id.") }
            else if (old == null) { local[id] = row; changes += TargetChange("teas", id, "$type ajouté") }
            else if (canonical(old) != canonical(row)) blockers += "$type $id a changé depuis l’établissement du plan."
        }
    }

    private fun reconcileAndCheck(teasPayload: JSONObject, configPayload: JSONObject, blockers: MutableList<String>) {
        val allReferences = configPayload.array("references") + teasPayload.array("references")
        allReferences.groupBy { it.getString("id") }.filterValues { rows -> rows.map(::canonical).distinct().size > 1 }
            .keys.forEach { blockers += "La référence $it possède deux définitions différentes dans l’état cible." }
        val refs = allReferences.associateBy { it.getString("id") }
        allReferences.groupBy { it.optString("kind") to it.stringOrNull("semanticKey") }.filter { it.key.second != null && it.value.map { row -> row.getString("id") }.distinct().size > 1 }
            .keys.forEach { blockers += "Clé sémantique ambiguë dans les références cibles (${it.first}/${it.second})." }
        allReferences.groupBy { Triple(it.optString("kind"), ReferenceLabelNormalizer.normalize(it.optString("label")), it.stringOrNull("parentCountryId")) }
            .filterValues { it.map { row -> row.getString("id") }.distinct().size > 1 }.keys
            .forEach { blockers += "Libellé de référence en collision dans l’état cible (${it.second})." }
        allReferences.forEach { ref ->
            val parent = ref.stringOrNull("parentCountryId")
            if (ref.optString("kind") == "REGION") {
                if (parent == null || refs[parent]?.optString("kind") != "COUNTRY") blockers += "La région ${ref.optString("label")} n’a pas de pays parent valide."
            } else if (parent != null) blockers += "Le référentiel ${ref.optString("label")} déclare un parent alors que son type n’est pas REGION."
        }
        val teas = teasPayload.array("teas")
        val ids = teas.map { it.getString("id") }.toSet()
        val legacy = teas.mapNotNull { it.longOrNull("legacyId") }
        if (legacy.size != legacy.toSet().size) blockers += "Deux thés partagent le même legacyId dans l’état cible."
        val byId = teas.associateBy { it.getString("id") }
        teas.forEach { tea ->
            listOf("categoryId" to "CATEGORY", "statusId" to "STATUS", "containerId" to "CONTAINER").forEach { (field, kind) ->
                val id = tea.stringOrNull(field)
                if (id != null && refs[id]?.optString("kind") != kind) blockers += "Le thé ${tea.optString("name")} référence un référentiel manquant ou de mauvais type ($field)."
            }
            val expectedKinds = mapOf("countryIds" to "COUNTRY", "regionIds" to "REGION", "aromaIds" to "AROMA", "aromaticProfileIds" to "AROMATIC_PROFILE", "momentIds" to "MOMENT", "badgeIds" to "BADGE")
            expectedKinds.forEach { (field, kind) -> tea.arrayStrings(field).forEach { id -> if (refs[id]?.optString("kind") != kind) blockers += "Le thé ${tea.optString("name")} a une association orpheline ou de mauvais type ($field)." } }
            tea.arrayStrings("regionIds").forEach { id ->
                val region = refs[id]
                val country = region?.stringOrNull("parentCountryId")
                if (region?.optString("kind") != "REGION" || country == null || country !in tea.arrayStrings("countryIds")) blockers += "Le thé ${tea.optString("name")} associe une région sans son pays parent."
            }
        }
        teasPayload.array("tastings").forEach { if (it.getString("teaId") !in ids) blockers += "Dégustation orpheline ${it.getString("id")}." }
        val tastings = teasPayload.array("tastings").associateBy { it.getString("id") }
        teasPayload.array("stockMovements").forEach { movement ->
            if (movement.getString("teaId") !in byId) blockers += "Mouvement de stock orphelin ${movement.getString("id")}."
            movement.stringOrNull("tastingId")?.let { tastingId -> if (tastings[tastingId]?.optString("teaId") != movement.optString("teaId")) blockers += "Le mouvement ${movement.getString("id")} pointe vers une dégustation d’un autre thé ou absente." }
        }
    }

    private fun combineReferences(a: List<JSONObject>, b: List<JSONObject>, blockers: MutableList<String>): List<JSONObject> {
        val result = a.associateBy { it.getString("id") }.toMutableMap()
        b.forEach { incoming ->
            val old = result[incoming.getString("id")]
            if (old != null && !sameIdentity(old, incoming)) blockers += "Collision de référence UUID ${incoming.getString("id")}."
            else result.putIfAbsent(incoming.getString("id"), incoming)
        }
        result.values.groupBy { it.optString("kind") to it.stringOrNull("semanticKey") }
            .filter { it.key.second != null && it.value.map { row -> row.getString("id") }.distinct().size > 1 }
            .keys.forEach { blockers += "Collision de clé sémantique de référence (${it.first}/${it.second})." }
        result.values.groupBy { Triple(it.optString("kind"), ReferenceLabelNormalizer.normalize(it.optString("label")), it.stringOrNull("parentCountryId")) }
            .filterValues { it.map { row -> row.getString("id") }.distinct().size > 1 }
            .keys.forEach { blockers += "Collision de libellé de référence (${it.second})." }
        return result.values.sortedBy { it.getString("id") }
    }

    private fun sameIdentity(a: JSONObject, b: JSONObject) = a.optString("kind") == b.optString("kind") && a.stringOrNull("semanticKey") == b.stringOrNull("semanticKey") && a.stringOrNull("parentCountryId") == b.stringOrNull("parentCountryId")
    private fun remapReference(raw: JSONObject, mapping: Map<String, String>) = JSONObject(raw.toString()).apply { put("id", mapping[optString("id")] ?: optString("id")); stringOrNull("parentCountryId")?.let { put("parentCountryId", mapping[it] ?: it) } }
    private fun remapTea(raw: JSONObject, mapping: Map<String, String>) = JSONObject(raw.toString()).apply {
        listOf("categoryId", "statusId", "containerId").forEach { f -> stringOrNull(f)?.let { put(f, mapping[it] ?: it) } }
        associations.forEach { f -> put(f, JSONArray(arrayStrings(f).map { mapping[it] ?: it }.distinct().sorted())) }
    }
    private fun reportRows(name: String, old: List<JSONObject>, next: List<JSONObject>, changes: MutableList<TargetChange>) {
        val oldIds = old.map { it.getString("id") }.toSet(); val nextIds = next.map { it.getString("id") }.toSet()
        (nextIds - oldIds).sorted().forEach { changes += TargetChange(name, it, "ajouté") }
        (oldIds - nextIds).sorted().forEach { changes += TargetChange(name, it, "retiré par remplacement") }
        next.filter { it.getString("id") in oldIds }.forEach { row -> if (canonical(row) != canonical(old.single { it.getString("id") == row.getString("id") })) changes += TargetChange(name, row.getString("id"), "remplacé") }
    }
    private fun usedReferenceIds(payload: JSONObject): Set<String> = buildSet {
        payload.array("teas").forEach { t ->
            listOf("categoryId", "statusId", "containerId").forEach { t.stringOrNull(it)?.let(::add) }
            associations.forEach { addAll(t.arrayStrings(it)) }
        }
        val refs = payload.array("references").associateBy { it.getString("id") }
        toList().forEach { id -> refs[id]?.stringOrNull("parentCountryId")?.let(::add) }
    }
    private fun failed(r: BackupTargetRequest, errors: List<String>, archiveHash: String = sha(r.archiveBytes), localHash: String = "") = BackupTargetResult(null, emptyList(), emptyList(), emptyList(), errors, BackupTargetFingerprints(archiveHash, localHash, "", ""))
    private fun canonicalRequest(r: BackupTargetRequest, a: String, l: String, p: BackupArchiveMergePlan?) = buildString {
        append(r.operation.name).append('|').append(a).append('|').append(l).append('|').append(r.selectedSets.sorted().joinToString(","))
        append('|').append(r.replacementConfirmed)
        r.confirmedChanges.sortedWith(compareBy<ConfirmedMergeChange> { it.entityType }.thenBy { it.identity }.thenBy { it.field }).forEach { append('|').append(it.entityType).append(':').append(it.identity).append(':').append(it.field) }
        p?.items?.sortedWith(compareBy<MergeItem> { it.entityType }.thenBy { it.identity })?.forEach { append('|').append(it.entityType).append(':').append(it.identity).append(':').append(it.disposition).append(':').append(it.changedFields.sorted()) }
        p?.blockingConflicts?.sorted()?.forEach { append("|conflict:").append(it) }
        p?.referenceIdRemap?.toSortedMap()?.forEach { (from, to) -> append("|reference:").append(from).append(':').append(to) }
    }
    private fun digestState(s: BackupTargetState) = sha(s.payloads.toSortedMap().entries.joinToString("\n") { "${it.key}\u0000${it.value}" } + "\n" + s.photosToKeep.joinToString("\n") { "${it.id}|${it.teaId}|${it.relativePath}|${it.position}" } + "\n" + s.supersededImportDatasetIds.sorted().joinToString("\n"))
    private fun payloadCanonical(a: JSONObject, b: JSONObject) = canonical(a) + canonical(b)
    private fun sha(text: String) = sha(text.toByteArray(StandardCharsets.UTF_8))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun obj(a: BackupArchiveValidator.ValidatedArchive, path: String) = a.entries[path]?.let { JSONObject(String(it, StandardCharsets.UTF_8)) }
    private fun canonicalJson(o: JSONObject) = canonical(o)
    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(separator = ",", prefix = "{", postfix = "}") { "${JSONObject.quote(it)}:${canonical(v.opt(it))}" }
        // Every array in the v1 tea/configuration payload is a set of rows or associations.
        is JSONArray -> (0 until v.length()).map { canonical(v.opt(it)) }.sorted().joinToString(separator = ",", prefix = "[", postfix = "]")
        JSONObject.NULL -> "null"
        is String -> JSONObject.quote(v)
        else -> v?.toString() ?: "null"
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONObject.array(k: String) = getJSONArray(k).objects()
    private fun JSONObject.arrayStrings(k: String) = getJSONArray(k).strings()
    private fun JSONObject.stringOrNull(k: String): String? = if (isNull(k)) null else optString(k)
    private fun JSONObject.longOrNull(k: String): Long? = if (isNull(k)) null else optLong(k)
}
