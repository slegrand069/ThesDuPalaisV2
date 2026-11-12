package fr.thesdupalais.data.importer

import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal const val ARTICLES_FORMAT = "thesdupalais.articles"
internal const val ARTICLES_SCHEMA_VERSION = 1

internal data class ArticleImportCandidate(
    val index: Int,
    val id: UUID?,
    val title: String,
    val category: String,
    val markdown: String,
)

data class ArticleImportRejection(
    /** Zero-based index in the JSON articles array. */
    val index: Int,
    val jsonPath: String,
    val reason: String,
)

internal data class ParsedArticles(
    val candidates: List<ArticleImportCandidate>,
    val rejections: List<ArticleImportRejection>,
)

class ArticleImportException(
    val jsonPath: String,
    detail: String,
    cause: Throwable? = null,
) : IllegalArgumentException("$jsonPath : $detail", cause)

internal object ArticleJsonParser {
    private val envelopeKeys = setOf("format", "schemaVersion", "articles")
    private val articleKeys = setOf("id", "title", "category", "markdown")

    fun parse(json: String): ParsedArticles {
        val root = try {
            StrictJsonSyntax(json).validate()
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (value !is JSONObject) fail("$", "Un objet JSON est attendu à la racine.")
            if (tokener.nextClean() != '\u0000') fail("$", "Du contenu suit l'objet JSON.")
            value
        } catch (error: ArticleImportException) {
            throw error
        } catch (error: JSONException) {
            throw ArticleImportException("$", "Le fichier JSON est illisible ou mal formé.", error)
        }

        checkKeys(root, envelopeKeys, "$")
        if (root.get("format") !is String || root.getString("format") != ARTICLES_FORMAT) {
            fail("$.format", "Le format doit être '$ARTICLES_FORMAT'.")
        }
        val version = root.get("schemaVersion") as? Number
        if (version == null || version.toDouble() != ARTICLES_SCHEMA_VERSION.toDouble()) {
            fail("$.schemaVersion", "La version de schéma doit être $ARTICLES_SCHEMA_VERSION.")
        }
        val articles = root.get("articles") as? JSONArray
            ?: fail("$.articles", "Un tableau d'articles est attendu.")

        val duplicateIds = duplicateIds(articles)
        val candidates = mutableListOf<ArticleImportCandidate>()
        val rejections = mutableListOf<ArticleImportRejection>()
        for (index in 0 until articles.length()) {
            val path = "$.articles[$index]"
            val raw = articles.opt(index)
            try {
                val obj = raw as? JSONObject ?: fail(path, "Un objet article est attendu.")
                val id = readOptionalId(obj, path)
                if (id != null && id in duplicateIds) {
                    fail("$path.id", "Cet UUID apparaît plusieurs fois dans le fichier; toutes ses occurrences sont rejetées.")
                }
                checkKeys(obj, articleKeys, path, required = articleKeys - "id")
                val title = requiredString(obj, "title", path).trim()
                if (title.isEmpty()) fail("$path.title", "Le titre ne peut pas être vide.")
                val category = requiredString(obj, "category", path).trim()
                if (category.isEmpty()) fail("$path.category", "La catégorie ne peut pas être vide.")
                val markdown = requiredString(obj, "markdown", path)
                candidates += ArticleImportCandidate(index, id, title, category, markdown)
            } catch (error: ArticleImportException) {
                rejections += ArticleImportRejection(index, error.jsonPath, error.message ?: "Article invalide.")
            } catch (error: JSONException) {
                rejections += ArticleImportRejection(index, path, "Structure JSON de l'article invalide.")
            }
        }
        return ParsedArticles(candidates, rejections)
    }

    private fun duplicateIds(articles: JSONArray): Set<UUID> {
        val ids = (0 until articles.length()).mapNotNull { index ->
            val obj = articles.opt(index) as? JSONObject ?: return@mapNotNull null
            val raw = obj.opt("id") as? String ?: return@mapNotNull null
            runCatching { parseUuid(raw) }.getOrNull()
        }
        return ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }

    private fun readOptionalId(obj: JSONObject, path: String): UUID? {
        if (!obj.has("id")) return null
        val raw = obj.opt("id") as? String ?: fail("$path.id", "Un UUID sous forme de chaîne est attendu.")
        if (raw.isBlank()) fail("$path.id", "L'UUID ne peut pas être vide.")
        return try {
            parseUuid(raw)
        } catch (error: IllegalArgumentException) {
            throw ArticleImportException("$path.id", "L'UUID est invalide.", error)
        }
    }

    private fun parseUuid(raw: String): UUID {
        require(UUID_PATTERN.matches(raw)) { "UUID au format canonique attendu." }
        return UUID.fromString(raw)
    }

    private fun requiredString(obj: JSONObject, key: String, path: String): String =
        obj.opt(key) as? String ?: fail("$path.$key", "Une chaîne de caractères est attendue.")

    private fun checkKeys(obj: JSONObject, allowed: Set<String>, path: String, required: Set<String> = allowed) {
        val unknown = obj.keys().asSequence().toSet() - allowed
        if (unknown.isNotEmpty()) fail(path, "Champ(s) inconnu(s) : ${unknown.sorted().joinToString()}.")
        val missing = required - obj.keys().asSequence().toSet()
        if (missing.isNotEmpty()) fail(path, "Champ(s) obligatoire(s) absent(s) : ${missing.sorted().joinToString()}.")
    }

    private fun fail(path: String, message: String): Nothing = throw ArticleImportException(path, message)

    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}

/** JSONTokener accepts JavaScript-like extensions, so validate RFC-style JSON syntax first. */
private class StrictJsonSyntax(private val input: String) {
    private var position = 0

    fun validate() {
        whitespace()
        value(0)
        whitespace()
        if (position != input.length) invalid("Des caractères inattendus suivent la valeur JSON.")
    }

    private fun value(depth: Int) {
        if (depth > MAX_DEPTH) invalid("La structure JSON est trop imbriquée.")
        whitespace()
        when (peek()) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> invalid("Valeur JSON attendue.")
        }
    }

    private fun objectValue(depth: Int) {
        position++
        whitespace()
        if (consume('}')) return
        val keys = mutableSetOf<String>()
        while (true) {
            whitespace()
            if (peek() != '"') invalid("Une clé d'objet doit être une chaîne JSON entre guillemets.")
            val key = string()
            if (!keys.add(key)) invalid("La propriété '$key' apparaît plusieurs fois.")
            whitespace()
            expect(':')
            value(depth)
            whitespace()
            if (consume('}')) return
            expect(',')
        }
    }

    private fun arrayValue(depth: Int) {
        position++
        whitespace()
        if (consume(']')) return
        while (true) {
            value(depth)
            whitespace()
            if (consume(']')) return
            expect(',')
        }
    }

    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (position < input.length) {
            val char = input[position++]
            when {
                char == '"' -> return result.toString()
                char == '\\' -> {
                    if (position >= input.length) invalid("Séquence d'échappement incomplète.")
                    when (val escaped = input[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            if (position + 4 > input.length || input.substring(position, position + 4).any { it.digitToIntOrNull(16) == null }) {
                                invalid("Séquence Unicode invalide.")
                            }
                            result.append(input.substring(position, position + 4).toInt(16).toChar())
                            position += 4
                        }
                        else -> invalid("Séquence d'échappement invalide.")
                    }
                }
                char.code < 0x20 -> invalid("Caractère de contrôle non échappé dans une chaîne.")
                else -> result.append(char)
            }
        }
        invalid("Chaîne JSON non terminée.")
    }

    private fun number() {
        consume('-')
        when {
            consume('0') -> if (peek() in '0'..'9') invalid("Nombre JSON invalide.")
            peek() in '1'..'9' -> while (peek() in '0'..'9') position++
            else -> invalid("Nombre JSON invalide.")
        }
        if (consume('.')) {
            if (peek() !in '0'..'9') invalid("Nombre JSON invalide.")
            while (peek() in '0'..'9') position++
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-')
            if (peek() !in '0'..'9') invalid("Nombre JSON invalide.")
            while (peek() in '0'..'9') position++
        }
    }

    private fun literal(expected: String) {
        if (!input.startsWith(expected, position)) invalid("Valeur JSON invalide.")
        position += expected.length
    }

    private fun whitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') position++
    }

    private fun expect(char: Char) {
        if (!consume(char)) invalid("Caractère '$char' attendu.")
    }

    private fun consume(char: Char): Boolean {
        if (peek() != char) return false
        position++
        return true
    }

    private fun peek(): Char = input.getOrNull(position) ?: '\u0000'

    private fun invalid(message: String): Nothing =
        throw ArticleImportException("$", "Le fichier JSON est mal formé : $message")

    private companion object { const val MAX_DEPTH = 128 }
}
