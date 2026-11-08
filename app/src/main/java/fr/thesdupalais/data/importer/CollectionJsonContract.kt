package fr.thesdupalais.data.importer

import fr.thesdupalais.data.preferences.UserPreferences
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal const val COLLECTION_FORMAT = "thesdupalais.collection"
internal const val COLLECTION_SCHEMA_VERSION = 1

internal data class CollectionDocument(
    val datasetId: UUID,
    val createdAtEpochMillis: Long,
    val schemaVersion: Int,
    val references: List<CollectionReference>,
    val preferences: UserPreferences,
    val teas: List<CollectionTea>,
    val tastings: List<CollectionTasting>,
    val stockMovements: List<CollectionStockMovement>,
)

internal data class CollectionReference(
    val id: UUID,
    val kind: ReferenceKind,
    val semanticKey: String?,
    val label: String,
    val parentCountryId: UUID?,
    val archivedAtEpochMillis: Long?,
    val displayPriority: Int,
)

internal data class CollectionTea(
    val id: UUID,
    val legacyId: Long?,
    val name: String,
    val categoryId: UUID,
    val description: String?,
    val technicalInformation: String?,
    val personalNotes: String?,
    val olfactoryRating: Double?,
    val tasteRating: Double?,
    val brewTemperatureCelsius: Int?,
    val infusionDurationSeconds: Int?,
    val statusId: UUID?,
    val containerId: UUID?,
    val countryIds: List<UUID>,
    val regionIds: List<UUID>,
    val aromaIds: List<UUID>,
    val aromaticProfileIds: List<UUID>,
    val momentIds: List<UUID>,
    val badgeIds: List<UUID>,
    val stockMilliGrams: Long?,
    val archivedAtEpochMillis: Long?,
)

internal data class CollectionTasting(
    val id: UUID,
    val teaId: UUID,
    val occurredAtEpochMillis: Long,
    val recordedAtEpochMillis: Long,
    val consumedMilliGrams: Long?,
)

internal data class CollectionStockMovement(
    val id: UUID,
    val teaId: UUID,
    val kind: StockMovementKind,
    val deltaMilliGrams: Long?,
    val stockAfterMilliGrams: Long,
    val occurredAtEpochMillis: Long,
    val recordedAtEpochMillis: Long,
    val tastingId: UUID?,
    val note: String?,
)

internal enum class StockMovementKind {
    INVENTORY,
    CONSUMPTION,
    RESTOCK,
    CORRECTION,
}

class CollectionImportException(
    val code: Code,
    val jsonPath: String,
    detail: String,
    cause: Throwable? = null,
) : IllegalArgumentException("$jsonPath : $detail", cause) {
    enum class Code {
        INVALID_JSON,
        INVALID_FIELD,
        UNSUPPORTED_FORMAT,
        UNSUPPORTED_VERSION,
        INVALID_UUID,
        DUPLICATE_VALUE,
        MISSING_REFERENCE,
        INVALID_RELATION,
        DATASET_ALREADY_IMPORTED,
        COLLECTION_NOT_EMPTY,
        DATABASE_FAILURE,
        PREFERENCES_FAILURE,
    }
}

internal object CollectionJsonParser {
    fun parse(json: String): CollectionDocument {
        val root = try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (value !is JSONObject) fail("$", CollectionImportException.Code.INVALID_JSON, "Un objet JSON est attendu.")
            if (tokener.nextClean() != '\u0000') {
                fail("$", CollectionImportException.Code.INVALID_JSON, "Du contenu suit l'objet JSON.")
            }
            value
        } catch (error: CollectionImportException) {
            throw error
        } catch (error: JSONException) {
            throw CollectionImportException(
                CollectionImportException.Code.INVALID_JSON,
                "$",
                "Le document n'est pas un JSON valide : ${error.message ?: "syntaxe incorrecte"}",
                error,
            )
        }

        return try {
            root.checkKeys("$", setOf("format", "schemaVersion", "datasetId", "createdAt", "references", "preferences", "teas", "tastings", "stockMovements"))
            val format = root.requiredString("format", "$")
            if (format != COLLECTION_FORMAT) {
                fail("$.format", CollectionImportException.Code.UNSUPPORTED_FORMAT, "Format attendu : $COLLECTION_FORMAT.")
            }
            val version = root.requiredInt("schemaVersion", "$")
            if (version != COLLECTION_SCHEMA_VERSION) {
                fail("$.schemaVersion", CollectionImportException.Code.UNSUPPORTED_VERSION, "Version $version non prise en charge (version attendue : $COLLECTION_SCHEMA_VERSION).")
            }
            val datasetId = root.requiredUuid("datasetId", "$")
            val createdAt = root.requiredInstant("createdAt", "$")
            val references = root.requiredObject("references", "$").readReferences()
            val preferences = root.requiredObject("preferences", "$").readPreferences()
            val teas = root.requiredArray("teas", "$").readTeas()
            val tastings = root.requiredArray("tastings", "$").readTastings()
            val movements = root.requiredArray("stockMovements", "$").readStockMovements()

            val document = CollectionDocument(datasetId, createdAt, version, references, preferences, teas, tastings, movements)
            validate(document)
            document
        } catch (error: CollectionImportException) {
            throw error
        } catch (error: JSONException) {
            throw CollectionImportException(
                CollectionImportException.Code.INVALID_JSON,
                "$",
                "Impossible de lire le document JSON : ${error.message ?: "structure incorrecte"}",
                error,
            )
        }
    }

    private fun JSONObject.readReferences(): List<CollectionReference> {
        val path = "$.references"
        val kinds = linkedMapOf(
            "categories" to ReferenceKind.CATEGORY,
            "statuses" to ReferenceKind.STATUS,
            "containers" to ReferenceKind.CONTAINER,
            "countries" to ReferenceKind.COUNTRY,
            "regions" to ReferenceKind.REGION,
            "aromas" to ReferenceKind.AROMA,
            "aromaticProfiles" to ReferenceKind.AROMATIC_PROFILE,
            "moments" to ReferenceKind.MOMENT,
            "badges" to ReferenceKind.BADGE,
        )
        checkKeys(path, kinds.keys)
        return kinds.flatMap { (field, kind) ->
            requiredArray(field, path).objects("$path.$field").mapIndexed { index, item ->
                val itemPath = "$path.$field[$index]"
                item.checkKeys(itemPath, setOf("id", "semanticKey", "label", "parentCountryId", "archivedAt", "displayPriority"))
                val semanticKey = item.nullableString("semanticKey", itemPath)
                    ?.also { it.requireTrimmedNonEmpty("$itemPath.semanticKey") }
                val label = item.requiredString("label", itemPath).also { it.requireTrimmedNonEmpty("$itemPath.label") }
                val parentCountryId = item.nullableUuid("parentCountryId", itemPath)
                val archivedAt = item.nullableInstant("archivedAt", itemPath)
                val displayPriority = item.requiredInt("displayPriority", itemPath)
                if (displayPriority < 0) fail("$itemPath.displayPriority", CollectionImportException.Code.INVALID_FIELD, "La priorité ne peut pas être négative.")
                CollectionReference(
                    id = item.requiredUuid("id", itemPath),
                    kind = kind,
                    semanticKey = semanticKey,
                    label = label,
                    parentCountryId = parentCountryId,
                    archivedAtEpochMillis = archivedAt,
                    displayPriority = displayPriority,
                )
            }
        }
    }

    private fun JSONObject.readPreferences(): UserPreferences {
        val path = "$.preferences"
        checkKeys(path, setOf("quantityPerCupMilliGrams", "lowStockThresholdMilliGrams"))
        val cup = requiredLong("quantityPerCupMilliGrams", path)
        val threshold = requiredLong("lowStockThresholdMilliGrams", path)
        if (cup <= 0) fail("$path.quantityPerCupMilliGrams", CollectionImportException.Code.INVALID_FIELD, "La quantité par tasse doit être supérieure à zéro.")
        if (threshold < 0) fail("$path.lowStockThresholdMilliGrams", CollectionImportException.Code.INVALID_FIELD, "Le seuil de stock faible ne peut pas être négatif.")
        return UserPreferences(cup, threshold)
    }

    private fun JSONArray.readTeas(): List<CollectionTea> = objects("$.teas").mapIndexed { index, item ->
        val path = "$.teas[$index]"
        item.checkKeys(
            path,
            setOf(
                "id", "legacyId", "name", "categoryId", "description", "technicalInformation", "personalNotes",
                "olfactoryRating", "tasteRating", "brewTemperatureCelsius", "infusionDurationSeconds", "statusId",
                "containerId", "countryIds", "regionIds", "aromaIds", "aromaticProfileIds", "momentIds", "badgeIds",
                "stockMilliGrams", "archivedAt",
            ),
        )
        val name = item.requiredString("name", path).also { it.requireTrimmedNonEmpty("$path.name") }
        val legacyId = item.nullableLong("legacyId", path)
        if (legacyId != null && legacyId <= 0) fail("$path.legacyId", CollectionImportException.Code.INVALID_FIELD, "L'ancien identifiant doit être positif.")
        val olfactory = item.nullableDouble("olfactoryRating", path)
        val taste = item.nullableDouble("tasteRating", path)
        if (olfactory != null && olfactory !in 0.0..10.0) fail("$path.olfactoryRating", CollectionImportException.Code.INVALID_FIELD, "La note doit être comprise entre 0 et 10.")
        if (taste != null && taste !in 0.0..10.0) fail("$path.tasteRating", CollectionImportException.Code.INVALID_FIELD, "La note doit être comprise entre 0 et 10.")
        val temperature = item.nullableInt("brewTemperatureCelsius", path)
        if (temperature != null && temperature !in 0..100) fail("$path.brewTemperatureCelsius", CollectionImportException.Code.INVALID_FIELD, "La température doit être comprise entre 0 et 100 °C.")
        val duration = item.nullableInt("infusionDurationSeconds", path)
        if (duration != null && duration <= 0) fail("$path.infusionDurationSeconds", CollectionImportException.Code.INVALID_FIELD, "La durée doit être supérieure à zéro seconde.")
        val stock = item.nullableLong("stockMilliGrams", path)
        if (stock != null && stock < 0) fail("$path.stockMilliGrams", CollectionImportException.Code.INVALID_FIELD, "Le stock ne peut pas être négatif.")
        CollectionTea(
            id = item.requiredUuid("id", path),
            legacyId = legacyId,
            name = name,
            categoryId = item.requiredUuid("categoryId", path),
            description = item.nullableString("description", path),
            technicalInformation = item.nullableString("technicalInformation", path),
            personalNotes = item.nullableString("personalNotes", path),
            olfactoryRating = olfactory,
            tasteRating = taste,
            brewTemperatureCelsius = temperature,
            infusionDurationSeconds = duration,
            statusId = item.nullableUuid("statusId", path),
            containerId = item.nullableUuid("containerId", path),
            countryIds = item.requiredUuidArray("countryIds", path),
            regionIds = item.requiredUuidArray("regionIds", path),
            aromaIds = item.requiredUuidArray("aromaIds", path),
            aromaticProfileIds = item.requiredUuidArray("aromaticProfileIds", path),
            momentIds = item.requiredUuidArray("momentIds", path),
            badgeIds = item.requiredUuidArray("badgeIds", path),
            stockMilliGrams = stock,
            archivedAtEpochMillis = item.nullableInstant("archivedAt", path),
        )
    }

    private fun JSONArray.readTastings(): List<CollectionTasting> = objects("$.tastings").mapIndexed { index, item ->
        val path = "$.tastings[$index]"
        item.checkKeys(path, setOf("id", "teaId", "occurredAt", "recordedAt", "consumedMilliGrams"))
        val consumed = item.nullableLong("consumedMilliGrams", path)
        if (consumed != null && consumed <= 0) fail("$path.consumedMilliGrams", CollectionImportException.Code.INVALID_FIELD, "La quantité consommée doit être positive ou inconnue (null).")
        CollectionTasting(
            id = item.requiredUuid("id", path),
            teaId = item.requiredUuid("teaId", path),
            occurredAtEpochMillis = item.requiredInstant("occurredAt", path),
            recordedAtEpochMillis = item.requiredInstant("recordedAt", path),
            consumedMilliGrams = consumed,
        )
    }

    private fun JSONArray.readStockMovements(): List<CollectionStockMovement> = objects("$.stockMovements").mapIndexed { index, item ->
        val path = "$.stockMovements[$index]"
        item.checkKeys(path, setOf("id", "teaId", "kind", "deltaMilliGrams", "stockAfterMilliGrams", "occurredAt", "recordedAt", "tastingId", "note"))
        val kind = try {
            StockMovementKind.valueOf(item.requiredString("kind", path))
        } catch (_: IllegalArgumentException) {
            fail("$path.kind", CollectionImportException.Code.INVALID_FIELD, "Type attendu : INVENTORY, CONSUMPTION, RESTOCK ou CORRECTION.")
        }
        val delta = item.nullableLong("deltaMilliGrams", path)
        val stockAfter = item.requiredLong("stockAfterMilliGrams", path)
        if (stockAfter < 0) fail("$path.stockAfterMilliGrams", CollectionImportException.Code.INVALID_FIELD, "Le stock après mouvement ne peut pas être négatif.")
        CollectionStockMovement(
            id = item.requiredUuid("id", path),
            teaId = item.requiredUuid("teaId", path),
            kind = kind,
            deltaMilliGrams = delta,
            stockAfterMilliGrams = stockAfter,
            occurredAtEpochMillis = item.requiredInstant("occurredAt", path),
            recordedAtEpochMillis = item.requiredInstant("recordedAt", path),
            tastingId = item.nullableUuid("tastingId", path),
            note = item.nullableString("note", path),
        )
    }

    private fun validate(document: CollectionDocument) {
        val referencesById = document.references.associateBy { it.id }
        ensureUnique(document.references.map { it.id }, "$.references", "identifiant de référentiel")
        document.references.groupBy { it.kind to it.semanticKey }.forEach { (key, group) ->
            if (key.second != null && group.size > 1) fail("$.references", CollectionImportException.Code.DUPLICATE_VALUE, "La clé sémantique ${key.second} est répétée pour ${key.first}.")
        }
        document.references.groupBy {
            Triple(it.kind, ReferenceLabelNormalizer.normalize(it.label), it.parentCountryId)
        }.forEach { (key, group) ->
            if (group.size > 1) fail("$.references", CollectionImportException.Code.DUPLICATE_VALUE, "Le libellé '${key.second}' est répété pour ${key.first} dans le même périmètre.")
        }
        document.references.forEach { reference ->
            val path = "$.references.${reference.kind.name.lowercase()}[${reference.id}]"
            if (reference.kind == ReferenceKind.REGION) {
                val parentId = reference.parentCountryId
                    ?: fail("$path.parentCountryId", CollectionImportException.Code.MISSING_REFERENCE, "Une région doit référencer un pays.")
                val parent = referencesById[parentId]
                    ?: fail("$path.parentCountryId", CollectionImportException.Code.MISSING_REFERENCE, "Pays $parentId absent de references.countries.")
                if (parent.kind != ReferenceKind.COUNTRY) fail("$path.parentCountryId", CollectionImportException.Code.INVALID_RELATION, "La région doit dépendre d'un référentiel COUNTRY.")
            } else if (reference.parentCountryId != null) {
                fail("$path.parentCountryId", CollectionImportException.Code.INVALID_RELATION, "Seules les régions peuvent déclarer un pays parent.")
            }
        }

        ensureUnique(document.teas.map { it.id }, "$.teas", "UUID de thé")
        ensureUniqueLongs(document.teas.mapNotNull { it.legacyId }, "$.teas", "legacyId")
        val teasById = document.teas.associateBy { it.id }
        document.teas.forEach { tea ->
            val path = "$.teas[${tea.id}]"
            requireReference(referencesById, tea.categoryId, ReferenceKind.CATEGORY, "$path.categoryId")
            tea.statusId?.let { requireReference(referencesById, it, ReferenceKind.STATUS, "$path.statusId") }
            tea.containerId?.let { requireReference(referencesById, it, ReferenceKind.CONTAINER, "$path.containerId") }
            tea.regionIds.forEach { requireReference(referencesById, it, ReferenceKind.REGION, "$path.regionIds") }
            tea.countryIds.forEach { requireReference(referencesById, it, ReferenceKind.COUNTRY, "$path.countryIds") }
            tea.aromaIds.forEach { requireReference(referencesById, it, ReferenceKind.AROMA, "$path.aromaIds") }
            tea.aromaticProfileIds.forEach { requireReference(referencesById, it, ReferenceKind.AROMATIC_PROFILE, "$path.aromaticProfileIds") }
            tea.momentIds.forEach { requireReference(referencesById, it, ReferenceKind.MOMENT, "$path.momentIds") }
            tea.badgeIds.forEach { requireReference(referencesById, it, ReferenceKind.BADGE, "$path.badgeIds") }
            listOf(tea.countryIds, tea.regionIds, tea.aromaIds, tea.aromaticProfileIds, tea.momentIds, tea.badgeIds)
                .forEach { ensureUnique(it, path, "association") }
        }

        ensureUnique(document.tastings.map { it.id }, "$.tastings", "UUID de dégustation")
        val tastingsById = document.tastings.associateBy { it.id }
        document.tastings.forEach { tasting ->
            if (tasting.teaId !in teasById) fail("$.tastings[${tasting.id}].teaId", CollectionImportException.Code.MISSING_REFERENCE, "Thé ${tasting.teaId} absent de teas.")
        }

        ensureUnique(document.stockMovements.map { it.id }, "$.stockMovements", "UUID de mouvement")
        ensureUnique(document.stockMovements.mapNotNull { it.tastingId }, "$.stockMovements", "tastingId associé")
        val movementsByTea = document.stockMovements.groupBy { it.teaId }
        document.stockMovements.forEach { movement ->
            val path = "$.stockMovements[${movement.id}]"
            val tea = teasById[movement.teaId]
                ?: fail("$path.teaId", CollectionImportException.Code.MISSING_REFERENCE, "Thé ${movement.teaId} absent de teas.")
            when (movement.kind) {
                StockMovementKind.INVENTORY -> if (movement.deltaMilliGrams != null || movement.tastingId != null) {
                    fail(path, CollectionImportException.Code.INVALID_RELATION, "Un mouvement INVENTORY est un solde initial : deltaMilliGrams et tastingId doivent être null.")
                }
                StockMovementKind.CONSUMPTION -> if (movement.deltaMilliGrams == null || movement.deltaMilliGrams >= 0) {
                    fail("$path.deltaMilliGrams", CollectionImportException.Code.INVALID_FIELD, "Une consommation doit avoir un delta négatif.")
                }
                StockMovementKind.RESTOCK -> if (movement.deltaMilliGrams == null || movement.deltaMilliGrams <= 0 || movement.tastingId != null) {
                    fail(path, CollectionImportException.Code.INVALID_RELATION, "Un réapprovisionnement doit avoir un delta positif et aucun tastingId.")
                }
                StockMovementKind.CORRECTION -> if (movement.deltaMilliGrams == null || movement.deltaMilliGrams == 0L || movement.tastingId != null) {
                    fail(path, CollectionImportException.Code.INVALID_RELATION, "Une correction doit avoir un delta non nul et aucun tastingId.")
                }
            }
            movement.tastingId?.let { tastingId ->
                val tasting = tastingsById[tastingId]
                    ?: fail("$path.tastingId", CollectionImportException.Code.MISSING_REFERENCE, "Dégustation $tastingId absente de tastings.")
                if (tasting.teaId != movement.teaId) fail("$path.tastingId", CollectionImportException.Code.INVALID_RELATION, "La dégustation associée appartient à un autre thé.")
                val amount = tasting.consumedMilliGrams
                    ?: fail("$path.tastingId", CollectionImportException.Code.INVALID_RELATION, "Une dégustation liée à un mouvement doit avoir une quantité connue.")
                if (movement.kind != StockMovementKind.CONSUMPTION || movement.deltaMilliGrams != -amount) {
                    fail("$path.tastingId", CollectionImportException.Code.INVALID_RELATION, "Le mouvement doit être une consommation égale à la quantité de la dégustation.")
                }
            }
            if (tea.stockMilliGrams == null) fail(path, CollectionImportException.Code.INVALID_RELATION, "Un thé au stock inconnu ne peut pas avoir de mouvements de stock.")
        }
        document.teas.forEach { tea ->
            val movements = movementsByTea[tea.id].orEmpty()
            if (tea.stockMilliGrams == null) {
                if (movements.isNotEmpty()) fail("$.teas[${tea.id}].stockMilliGrams", CollectionImportException.Code.INVALID_RELATION, "Un stock inconnu (null) ne peut pas avoir de mouvements.")
                return@forEach
            }
            if (movements.isEmpty()) fail("$.teas[${tea.id}].stockMilliGrams", CollectionImportException.Code.INVALID_RELATION, "Un stock connu doit avoir au moins un mouvement INVENTORY de référence.")
            var balance: Long? = null
            movements.sortedWith(compareBy<CollectionStockMovement> { it.occurredAtEpochMillis }.thenBy { it.recordedAtEpochMillis }.thenBy { it.id.toString() })
                .forEach { movement ->
                    if (movement.kind == StockMovementKind.INVENTORY) {
                        balance = movement.stockAfterMilliGrams
                    } else {
                        val previous = balance
                            ?: fail("$.stockMovements[${movement.id}]", CollectionImportException.Code.INVALID_RELATION, "Le premier mouvement d'un stock connu doit être INVENTORY.")
                        val next = try {
                            Math.addExact(previous, requireNotNull(movement.deltaMilliGrams))
                        } catch (_: ArithmeticException) {
                            fail("$.stockMovements[${movement.id}].deltaMilliGrams", CollectionImportException.Code.INVALID_FIELD, "Le calcul du stock dépasse la capacité numérique.")
                        }
                        if (next < 0 || next != movement.stockAfterMilliGrams) {
                            fail("$.stockMovements[${movement.id}].stockAfterMilliGrams", CollectionImportException.Code.INVALID_RELATION, "Le solde après mouvement ne correspond pas au solde précédent et au delta.")
                        }
                        balance = next
                    }
                }
            if (balance != tea.stockMilliGrams) fail("$.teas[${tea.id}].stockMilliGrams", CollectionImportException.Code.INVALID_RELATION, "Le stock du thé doit correspondre au solde de son dernier mouvement.")
        }
    }

    private fun requireReference(
        refs: Map<UUID, CollectionReference>,
        id: UUID,
        kind: ReferenceKind,
        path: String,
    ): CollectionReference {
        val reference = refs[id] ?: fail(path, CollectionImportException.Code.MISSING_REFERENCE, "Référence $id absente du bloc references.")
        if (reference.kind != kind) fail(path, CollectionImportException.Code.INVALID_RELATION, "La référence $id est de type ${reference.kind}, type attendu : $kind.")
        return reference
    }

    private fun ensureUnique(values: List<UUID>, path: String, description: String) {
        if (values.size != values.toSet().size) fail(path, CollectionImportException.Code.DUPLICATE_VALUE, "$description présent plusieurs fois.")
    }

    private fun ensureUniqueLongs(values: List<Long>, path: String, description: String) {
        if (values.size != values.toSet().size) fail(path, CollectionImportException.Code.DUPLICATE_VALUE, "$description présent plusieurs fois.")
    }

    private fun JSONObject.checkKeys(path: String, allowed: Set<String>) {
        val keys = keys().asSequence().toSet()
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) fail(path, CollectionImportException.Code.INVALID_FIELD, "Champ(s) inconnu(s) : ${unknown.sorted().joinToString()}.")
        val missing = allowed - keys
        if (missing.isNotEmpty()) fail(path, CollectionImportException.Code.INVALID_FIELD, "Champ(s) obligatoire(s) absent(s) : ${missing.sorted().joinToString()}.")
    }

    private fun JSONObject.requiredObject(name: String, path: String): JSONObject =
        get(name) as? JSONObject ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Un objet JSON est attendu.")

    private fun JSONObject.requiredArray(name: String, path: String): JSONArray =
        get(name) as? JSONArray ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Un tableau JSON est attendu.")

    private fun JSONArray.objects(path: String): List<JSONObject> = (0 until length()).map { index ->
        opt(index) as? JSONObject ?: fail("$path[$index]", CollectionImportException.Code.INVALID_FIELD, "Un objet JSON est attendu.")
    }

    private fun JSONObject.requiredString(name: String, path: String): String =
        get(name) as? String ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Une chaîne de caractères est attendue.")

    private fun JSONObject.nullableString(name: String, path: String): String? = when (val value = get(name)) {
        JSONObject.NULL -> null
        is String -> value
        else -> fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Une chaîne ou null est attendu.")
    }

    private fun JSONObject.requiredUuid(name: String, path: String): UUID = parseUuid(requiredString(name, path), "$path.$name")

    private fun JSONObject.nullableUuid(name: String, path: String): UUID? = nullableString(name, path)?.let { parseUuid(it, "$path.$name") }

    private fun JSONObject.requiredUuidArray(name: String, path: String): List<UUID> =
        requiredArray(name, path).let { values ->
            (0 until values.length()).map { index ->
                val value = values.opt(index) as? String
                    ?: fail("$path.$name[$index]", CollectionImportException.Code.INVALID_FIELD, "Un UUID sous forme de chaîne est attendu.")
                parseUuid(value, "$path.$name[$index]")
            }.also { ensureUnique(it, "$path.$name", "UUID d'association") }
        }

    private fun parseUuid(value: String, path: String): UUID {
        if (!UUID_PATTERN.matches(value)) fail(path, CollectionImportException.Code.INVALID_UUID, "UUID invalide : format canonique 8-4-4-4-12 attendu.")
        return try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw CollectionImportException(CollectionImportException.Code.INVALID_UUID, path, "UUID invalide.", error)
        }
    }

    private fun JSONObject.requiredInt(name: String, path: String): Int =
        integerNumber(name, path)?.let { value ->
            val long = value.longValueExactOrNull()
                ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Un entier valide est attendu.")
            if (long !in Int.MIN_VALUE..Int.MAX_VALUE) fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Entier hors limites.")
            long.toInt()
        } ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Un entier est attendu.")

    private fun JSONObject.nullableInt(name: String, path: String): Int? = integerNumber(name, path)?.let { value ->
        val long = value.longValueExactOrNull()
            ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Un entier ou null est attendu.")
        if (long !in Int.MIN_VALUE..Int.MAX_VALUE) fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Entier hors limites.")
        long.toInt()
    }

    private fun JSONObject.requiredLong(name: String, path: String): Long =
        integerNumber(name, path)?.longValueExactOrNull()
            ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Un entier sur 64 bits est attendu.")

    private fun JSONObject.nullableLong(name: String, path: String): Long? = integerNumber(name, path)?.let { value ->
        value.longValueExactOrNull()
            ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Un entier sur 64 bits ou null est attendu.")
    }

    private fun JSONObject.nullableDouble(name: String, path: String): Double? = nullableNumber(name, path)?.let { value ->
        val double = value.toDouble()
        if (!double.isFinite()) fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Nombre fini ou null attendu.")
        double
    }

    private fun JSONObject.integerNumber(name: String, path: String): BigDecimal? = when (val value = get(name)) {
        JSONObject.NULL -> null
        is Byte, is Short, is Int, is Long -> value.toString().toBigDecimal()
        else -> fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Un entier JSON ou null est attendu.")
    }

    private fun JSONObject.nullableNumber(name: String, path: String): BigDecimal? = when (val value = get(name)) {
        JSONObject.NULL -> null
        is Number -> value.toString().toBigDecimalOrNull()
            ?: fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Valeur numérique invalide.")
        else -> fail("$path.$name", CollectionImportException.Code.INVALID_FIELD, "Une valeur numérique ou null est attendue.")
    }

    private fun JSONObject.requiredInstant(name: String, path: String): Long = parseInstant(requiredString(name, path), "$path.$name")

    private fun JSONObject.nullableInstant(name: String, path: String): Long? = nullableString(name, path)?.let { parseInstant(it, "$path.$name") }

    private fun parseInstant(value: String, path: String): Long {
        val dateTime = try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (error: Exception) {
            throw CollectionImportException(CollectionImportException.Code.INVALID_FIELD, path, "Date ISO 8601 avec fuseau horaire attendue.", error)
        }
        if (dateTime.nano % 1_000_000 != 0) fail(path, CollectionImportException.Code.INVALID_FIELD, "La précision des dates est limitée à la milliseconde.")
        return try {
            dateTime.toInstant().toEpochMilli()
        } catch (error: ArithmeticException) {
            throw CollectionImportException(CollectionImportException.Code.INVALID_FIELD, path, "Date hors limites.", error)
        }
    }

    private fun String.requireTrimmedNonEmpty(path: String) {
        if (isBlank() || this != trim()) fail(path, CollectionImportException.Code.INVALID_FIELD, "Valeur obligatoire, non vide et sans espaces extérieurs.")
    }

    private fun BigDecimal.longValueExactOrNull(): Long? = try {
        longValueExact()
    } catch (_: ArithmeticException) {
        null
    }

    private fun fail(path: String, code: CollectionImportException.Code, detail: String): Nothing =
        throw CollectionImportException(code, path, detail)

    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
