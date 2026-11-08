package fr.thesdupalais.domain.references

import java.text.Normalizer
import java.util.Locale

object ReferenceLabelNormalizer {
    fun normalize(label: String): String = Normalizer
        .normalize(label.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
}
