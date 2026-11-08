package fr.thesdupalais.domain.references

import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.domain.model.ReferenceValue
import java.nio.charset.StandardCharsets
import java.util.UUID

object StandardReferenceSeeds {
    private const val ID_NAMESPACE = "fr.thesdupalais.standard-reference.color."

    val colors: List<ReferenceValue> = listOf(
        color("GREEN", "Vert"),
        color("BLACK", "Noir"),
        color("WHITE", "Blanc"),
        color("YELLOW", "Jaune"),
        color("OOLONG", "Oolong"),
        color("PU_ERH", "Pu'Erh"),
    )

    private fun color(semanticKey: String, label: String) = ReferenceValue(
        id = UUID.nameUUIDFromBytes("$ID_NAMESPACE$semanticKey".toByteArray(StandardCharsets.UTF_8)),
        kind = ReferenceKind.COLOR,
        label = label,
        normalizedLabel = ReferenceLabelNormalizer.normalize(label),
        semanticKey = semanticKey,
    )
}
