package fr.thesdupalais.data.importer

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArticleJsonTextDecoderTest {
    @Test fun readsUtf8AndStripsOptionalBom() {
        assertEquals("{\"articles\":[]}", readArticleJsonDocument {
            ByteArrayInputStream(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{\"articles\":[]}".toByteArray())
        })
    }

    @Test fun reportsMissingDocumentStreamsAndReadFailures() {
        assertThrows(IOException::class.java) { readArticleJsonDocument { null } }
        assertThrows(IOException::class.java) { readArticleJsonDocument { throw IOException("provider unavailable") } }
    }

    @Test fun rejectsMalformedUtf8InsteadOfSilentlyReplacingBytes() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            decodeArticleJsonUtf8(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }
}
