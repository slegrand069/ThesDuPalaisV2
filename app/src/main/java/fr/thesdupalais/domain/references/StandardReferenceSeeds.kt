package fr.thesdupalais.domain.references

import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import java.nio.charset.StandardCharsets
import java.util.UUID

object StandardReferenceSeeds {
    // Keep the original namespace so the six Lot 1 color UUIDs remain stable after
    // the COLOR -> CATEGORY migration.
    private const val LEGACY_CATEGORY_ID_NAMESPACE = "fr.thesdupalais.standard-reference.color."

    val categories: List<ReferenceValue> = listOf(
        category("GREEN", "Thé vert", LEGACY_CATEGORY_ID_NAMESPACE),
        category("BLACK", "Thé noir", LEGACY_CATEGORY_ID_NAMESPACE),
        category("WHITE", "Thé blanc", LEGACY_CATEGORY_ID_NAMESPACE),
        category("OOLONG", "Oolong", LEGACY_CATEGORY_ID_NAMESPACE),
        category("PU_ERH", "Pu’Erh", LEGACY_CATEGORY_ID_NAMESPACE),
        category("YELLOW", "Thé jaune", LEGACY_CATEGORY_ID_NAMESPACE),
        category("INFUSION", "Infusion"),
        category("MATE", "Maté"),
        category("OTHER", "Autres"),
    )

    val statuses: List<ReferenceValue> = listOf(
        reference(ReferenceKind.STATUS, "IN_COLLECTION", "Dans ma collection"),
        reference(ReferenceKind.STATUS, "IN_TEST", "En test"),
        reference(ReferenceKind.STATUS, "TO_TEST", "À tester"),
        reference(ReferenceKind.STATUS, "TO_BUY", "À acheter"),
        reference(ReferenceKind.STATUS, "TO_REBUY", "À racheter"),
        reference(ReferenceKind.STATUS, "AVOID", "À éviter"),
    )

    val containers: List<ReferenceValue> = listOf(
        reference(ReferenceKind.CONTAINER, "BOX", "Boîte"),
        reference(ReferenceKind.CONTAINER, "BAG", "Sachet"),
        reference(ReferenceKind.CONTAINER, "JAR", "Bocal"),
        reference(ReferenceKind.CONTAINER, "SAMPLE", "Échantillon"),
        reference(ReferenceKind.CONTAINER, "OTHER", "Autre"),
    )

    val moments: List<ReferenceValue> = listOf(
        reference(ReferenceKind.MOMENT, "WAKE_UP", "Réveil"),
        reference(ReferenceKind.MOMENT, "MORNING", "Matin"),
        reference(ReferenceKind.MOMENT, "LUNCH", "Déjeuner"),
        reference(ReferenceKind.MOMENT, "AFTERNOON", "Après-midi"),
        reference(ReferenceKind.MOMENT, "EVENING", "Soir"),
        reference(ReferenceKind.MOMENT, "BEDTIME", "Coucher"),
        reference(ReferenceKind.MOMENT, "ANYTIME", "Tout moment"),
    )

    val all: List<ReferenceValue> = categories + statuses + containers + moments

    private fun category(semanticKey: String, label: String, namespace: String? = null) =
        reference(ReferenceKind.CATEGORY, semanticKey, label, namespace)

    private fun reference(
        kind: ReferenceKind,
        semanticKey: String,
        label: String,
        idNamespace: String? = null,
    ) = ReferenceValue(
        id = UUID.nameUUIDFromBytes(
            "${idNamespace ?: "fr.thesdupalais.standard-reference.${kind.name.lowercase()}."}$semanticKey"
                .toByteArray(StandardCharsets.UTF_8),
        ),
        kind = kind,
        label = label,
        normalizedLabel = ReferenceLabelNormalizer.normalize(label),
        semanticKey = semanticKey,
    )
}
