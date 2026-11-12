package fr.thesdupalais.data.importer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.io.IOException
import java.io.InputStream

/** Decodes document-provider bytes without silently replacing malformed UTF-8. */
fun decodeArticleJsonUtf8(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
}

fun readArticleJsonDocument(open: () -> InputStream?): String {
    val stream = open() ?: throw IOException("Le fournisseur de documents n'a pas ouvert le fichier sélectionné.")
    return stream.use { decodeArticleJsonUtf8(it.readBytes()) }
}
