package fr.thesdupalais.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import fr.thesdupalais.data.importer.CollectionJsonParser
import fr.thesdupalais.domain.references.ReferenceLabelNormalizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Strict, side-effect-free validation for the v1 backup container. It never writes files or Room. */
object BackupArchiveValidator {
    const val FORMAT = "thesdupalais.backup"
    const val SCHEMA_VERSION = 1
    const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 150L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    const val MAX_IMAGE_DIMENSION = 4096
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private val sets = setOf("teas", "configuration", "articles")
    private val hex = Regex("[0-9a-f]{64}")

    data class ValidatedArchive(
        val backupId: String,
        val createdAt: String,
        val includedSets: Set<String>,
        val entries: Map<String, ByteArray>,
    )

    class ValidationException(val path: String, message: String, cause: Throwable? = null) :
        IllegalArgumentException("$path: $message", cause)

    private data class DeclaredEntry(val path: String, val mediaType: String, val size: Long, val sha256: String)

    fun validate(archiveBytes: ByteArray): ValidatedArchive {
        if (archiveBytes.size.toLong() > MAX_ARCHIVE_BYTES) fail("$", "Archive exceeds 50 MiB compressed limit.")
        val files = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        try {
            ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    validatePath(name, "ZIP entry")
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    if (files.containsKey(name)) fail(name, "Duplicate ZIP path.")
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expanded += count
                        if (expanded > MAX_EXPANDED_BYTES) fail(name, "Expanded archive exceeds 150 MiB.")
                        if (name == "manifest.json" && out.size().toLong() + count > MAX_MANIFEST_BYTES) fail(name, "Manifest is too large.")
                        out.write(buffer, 0, count)
                    }
                    files[name] = out.toByteArray()
                    zip.closeEntry()
                }
            }
        } catch (e: ValidationException) { throw e }
        catch (e: Exception) { throw ValidationException("ZIP", "Invalid or corrupt ZIP archive.", e) }
        val manifestBytes = files["manifest.json"] ?: fail("manifest.json", "Required manifest is missing.")
        val manifest = parseObject(manifestBytes, "manifest.json")
        strictKeys(manifest, setOf("format", "schemaVersion", "backupId", "createdAt", "producer", "includedSets", "entries"), "manifest.json")
        if (string(manifest, "format", "manifest.json") != FORMAT) fail("manifest.json.format", "Unsupported format.")
        if (integer(manifest, "schemaVersion", "manifest.json") != SCHEMA_VERSION.toLong()) fail("manifest.json.schemaVersion", "Unsupported schema version.")
        val backupId = string(manifest, "backupId", "manifest.json")
        if (!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(backupId)) fail("manifest.json.backupId", "Canonical UUID required.")
        val createdAt = string(manifest, "createdAt", "manifest.json")
        try { java.time.OffsetDateTime.parse(createdAt) } catch (e: Exception) { fail("manifest.json.createdAt", "ISO-8601 timestamp with offset required.") }
        val producer = obj(manifest, "producer", "manifest.json")
        strictKeys(producer, setOf("application", "version"), "manifest.json.producer")
        string(producer, "application", "manifest.json.producer"); string(producer, "version", "manifest.json.producer")
        val included = array(manifest, "includedSets", "manifest.json").mapIndexed { i, v ->
            val s = v as? String ?: fail("manifest.json.includedSets[$i]", "String required.")
            if (s !in sets) fail("manifest.json.includedSets[$i]", "Unknown data set.")
            s
        }
        if (included.isEmpty() || included.size != included.toSet().size) fail("manifest.json.includedSets", "At least one unique data set is required.")
        val declarations = array(manifest, "entries", "manifest.json").mapIndexed { i, v ->
            val path = "manifest.json.entries[$i]"
            val item = v as? JSONObject ?: fail(path, "Object required.")
            strictKeys(item, setOf("path", "mediaType", "sizeBytes", "sha256"), path)
            val p = string(item, "path", path); validatePath(p, path)
            if (p == "manifest.json") fail(path, "Manifest cannot list itself.")
            val size = integer(item, "sizeBytes", path)
            if (size < 0) fail(path, "Negative size.")
            val digest = string(item, "sha256", path)
            if (!hex.matches(digest)) fail(path, "Lowercase SHA-256 hex digest required.")
            DeclaredEntry(p, string(item, "mediaType", path), size, digest)
        }
        if (declarations.map { it.path }.toSet().size != declarations.size) fail("manifest.json.entries", "Duplicate declared path.")
        val expectedData = buildSet {
            if ("teas" in included) add("data/teas.json")
            if ("configuration" in included) add("data/configuration.json")
            if ("articles" in included) add("articles/index.json")
        }
        if (!declarations.map { it.path }.containsAll(expectedData)) fail("manifest.json.entries", "A selected set's index file is missing from the manifest.")
        val declared = declarations.associateBy { it.path }
        val actualFiles = files.keys - "manifest.json"
        if (actualFiles != declared.keys) fail("manifest.json.entries", "Archive entries and manifest declarations differ.")
        declarations.forEach { d ->
            val bytes = files.getValue(d.path)
            if (bytes.size.toLong() != d.size) fail(d.path, "Declared size does not match.")
            val actualDigest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
            if (actualDigest != d.sha256) fail(d.path, "SHA-256 mismatch.")
            if (d.path.startsWith("articles/media/")) {
                if ("articles" !in included) fail(d.path, "Article media included without the articles set.")
                validateImage(d.path, d.mediaType, bytes)
            } else if (d.path.endsWith(".json")) {
                if (d.mediaType != "application/json") fail(d.path, "Expected application/json media type.")
                val json = parseObject(bytes, d.path)
                when (d.path) {
                    "data/teas.json" -> validateTeasShape(json)
                    "data/configuration.json" -> validateConfigurationShape(json)
                }
            } else if (d.path.endsWith(".md")) {
                if ("articles" !in included || d.mediaType != "text/markdown; charset=utf-8") fail(d.path, "Unexpected Markdown entry.")
                validateUtf8(bytes, d.path)
            }
            else fail(d.path, "Unexpected file type.")
        }
        val teasPayload = files["data/teas.json"]?.let { parseObject(it, "data/teas.json") }
        val configPayload = files["data/configuration.json"]?.let { parseObject(it, "data/configuration.json") }
        if (teasPayload != null) validateWithCollectionRules(teasPayload, "teas")
        if (configPayload != null) validateWithCollectionRules(configPayload, "configuration")
        if (teasPayload != null && configPayload != null) validateReferenceSetCollisions(teasPayload, configPayload)
        if ("articles" in included) validateArticles(parseObject(files.getValue("articles/index.json"), "articles/index.json"), files)
        return ValidatedArchive(backupId, createdAt, included.toSet(), files.filterKeys { it != "manifest.json" })
    }

    /** Adapts the v1 backup subset to the established collection parser, reusing its business rules. */
    private fun validateWithCollectionRules(payload: JSONObject, set: String) {
        val backupPath = if (set == "teas") "data/teas.json" else "data/configuration.json"
        try {
            val refs = array(payload, "references", backupPath)
            val byKind = JSONObject()
            val names = mapOf(
                "CATEGORY" to "categories", "STATUS" to "statuses", "CONTAINER" to "containers",
                "COUNTRY" to "countries", "REGION" to "regions", "AROMA" to "aromas",
                "AROMATIC_PROFILE" to "aromaticProfiles", "MOMENT" to "moments", "BADGE" to "badges",
            )
            names.values.forEach { byKind.put(it, JSONArray()) }
            refs.forEachIndexed { i, raw ->
                val p = "$backupPath.references[$i]"
                val ref = raw as? JSONObject ?: fail(p, "Object required.")
                val name = names[string(ref, "kind", p)] ?: fail("$p.kind", "Unknown reference kind.")
                val legacyShape = JSONObject()
                listOf("id", "semanticKey", "label", "parentCountryId", "archivedAt", "displayPriority").forEach { key -> legacyShape.put(key, ref.opt(key)) }
                byKind.getJSONArray(name).put(legacyShape)
            }
            val prefs = if (set == "configuration") obj(payload, "preferences", backupPath) else
                JSONObject().put("quantityPerCupMilliGrams", 7000).put("lowStockThresholdMilliGrams", 25000)
            val legacy = JSONObject()
                .put("format", "thesdupalais.collection")
                .put("schemaVersion", 1)
                .put("datasetId", "123e4567-e89b-12d3-a456-426614174000")
                .put("createdAt", "2026-01-01T00:00:00Z")
                .put("references", byKind)
                .put("preferences", prefs)
                .put("teas", if (set == "teas") payload.getJSONArray("teas") else JSONArray())
                .put("tastings", if (set == "teas") payload.getJSONArray("tastings") else JSONArray())
                .put("stockMovements", if (set == "teas") payload.getJSONArray("stockMovements") else JSONArray())
            CollectionJsonParser.parse(legacy.toString())
            if (set == "teas") {
                validateEffectiveCountryAssociations(payload)
                validateTeaDependencyClosure(payload)
            }
        } catch (e: ValidationException) { throw e }
        catch (e: Exception) { throw ValidationException(backupPath, "Business validation failed: ${e.message ?: "invalid data"}", e) }
    }

    /** A region supplies its parent country implicitly; an explicit country list may not contradict it. */
    private fun validateEffectiveCountryAssociations(payload: JSONObject) {
        val parents = array(payload, "references", "data/teas.json").mapNotNull { raw ->
            val ref = raw as JSONObject
            if (ref.optString("kind") == "REGION") ref.optString("id") to ref.optString("parentCountryId") else null
        }.toMap()
        array(payload, "teas", "data/teas.json").forEachIndexed { i, raw ->
            val tea = raw as JSONObject
            val countries = array(tea, "countryIds", "data/teas.json.teas[$i]").map { it as String }.toSet()
            array(tea, "regionIds", "data/teas.json.teas[$i]").forEach { rawRegion ->
                val regionId = rawRegion as String
                val parentId = parents[regionId] ?: fail("data/teas.json.teas[$i].regionIds", "Region has no country parent.")
                if (countries.isNotEmpty() && parentId !in countries) {
                    fail("data/teas.json.teas[$i].countryIds", "Explicit country associations conflict with the associated region's parent country.")
                }
            }
        }
    }

    private fun validateTeaDependencyClosure(payload: JSONObject) {
        val refs = array(payload, "references", "data/teas.json").associateBy { (it as JSONObject).getString("id") }
        val used = mutableSetOf<String>()
        array(payload, "teas", "data/teas.json").forEach { raw ->
            val tea = raw as JSONObject
            listOf("categoryId", "statusId", "containerId").forEach { key ->
                val value = tea.opt(key) as? String
                if (value != null) used += value
            }
            listOf("countryIds", "regionIds", "aromaIds", "aromaticProfileIds", "momentIds", "badgeIds").forEach { key ->
                array(tea, key, "data/teas.json.teas").forEach { used += it as String }
            }
        }
        // Region parent countries are effective associations even when omitted from countryIds.
        used += array(payload, "references", "data/teas.json").mapNotNull { raw ->
            val ref = raw as JSONObject
            if (ref.optString("kind") == "REGION" && ref.getString("id") in used) ref.opt("parentCountryId") as? String else null
        }
        val unused = refs.keys - used
        if (unused.isNotEmpty()) fail("data/teas.json.references", "Dependency references must be limited to those used by included teas; unused: ${unused.sorted().joinToString()}.")
    }

    private data class RefSignature(
        val id: String, val kind: String, val semanticKey: String?, val label: String,
        val parentCountryId: String?, val archivedAt: String?, val displayPriority: Long,
    )

    private fun validateReferenceSetCollisions(teas: JSONObject, configuration: JSONObject) {
        val deps = readRefSignatures(teas, "data/teas.json")
        val all = readRefSignatures(configuration, "data/configuration.json")
        val allById = all.associateBy { it.id }
        deps.forEach { dep ->
            val sameId = allById[dep.id]
            if (sameId != null && sameId != dep) fail("data/configuration.json.references[${dep.id}]", "Reference dependency UUID collides with a different configuration reference.")
            if (dep.semanticKey != null && all.any { it.kind == dep.kind && it.semanticKey == dep.semanticKey && it.id != dep.id }) {
                fail("data/configuration.json.references", "Reference dependency semantic key collides with a different configuration UUID.")
            }
            val normalized = ReferenceLabelNormalizer.normalize(dep.label)
            if (all.any { it.kind == dep.kind && it.id != dep.id && it.parentCountryId == dep.parentCountryId && ReferenceLabelNormalizer.normalize(it.label) == normalized }) {
                fail("data/configuration.json.references", "Reference dependency label collides within the same type and scope.")
            }
        }
    }

    private fun readRefSignatures(payload: JSONObject, path: String): List<RefSignature> =
        array(payload, "references", path).mapIndexed { i, raw ->
            val p = "$path.references[$i]"; val r = raw as? JSONObject ?: fail(p, "Object required.")
            RefSignature(
                id = string(r, "id", p), kind = string(r, "kind", p),
                semanticKey = r.opt("semanticKey") as? String, label = string(r, "label", p),
                parentCountryId = r.opt("parentCountryId") as? String, archivedAt = r.opt("archivedAt") as? String,
                displayPriority = integer(r, "displayPriority", p),
            )
        }

    private fun validateArticles(index: JSONObject, files: Map<String, ByteArray>) {
        strictKeys(index, setOf("articles"), "articles/index.json")
        val ids = mutableSetOf<String>(); val referenced = mutableSetOf("articles/index.json")
        val rows = array(index, "articles", "articles/index.json")
        rows.forEachIndexed { i, raw ->
            val path = "articles/index.json.articles[$i]"; val row = raw as? JSONObject ?: fail(path, "Object required.")
            strictKeys(row, setOf("id", "title", "section", "markdownPath", "illustrations"), path)
            val id = string(row, "id", path)
            if (!Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$").matches(id)) fail("$path.id", "Stable id must be 1-128 ASCII letters, digits, dot, underscore, colon or hyphen; it cannot contain a slash or depend on the title.")
            if (!ids.add(id)) fail("$path.id", "Duplicate article id.")
            if (string(row, "title", path).isBlank()) fail("$path.title", "Title cannot be blank.")
            val section = row.opt("section")
            if (section !== JSONObject.NULL && section !is String) fail("$path.section", "String or null required.")
            val md = string(row, "markdownPath", path); validatePath(md, "$path.markdownPath")
            if (!md.startsWith("articles/content/") || !md.endsWith(".md")) fail("$path.markdownPath", "Markdown must be under articles/content and end in .md.")
            if (!referenced.add(md)) fail("$path.markdownPath", "Duplicate article content path.")
            val illustrations = array(row, "illustrations", path)
            illustrations.forEachIndexed { j, rawImage ->
                val ip = "$path.illustrations[$j]"; val image = rawImage as? JSONObject ?: fail(ip, "Object required.")
                strictKeys(image, setOf("path", "alt"), ip)
                val imagePath = string(image, "path", ip); validatePath(imagePath, "$ip.path")
                if (!imagePath.startsWith("articles/media/")) fail("$ip.path", "Illustration must be under articles/media.")
                val alt = image.opt("alt"); if (alt !== JSONObject.NULL && alt !is String) fail("$ip.alt", "String or null required.")
                if (!referenced.add(imagePath)) fail("$ip.path", "Duplicate illustration path.")
            }
        }
        val articleFiles = files.keys.filter { it.startsWith("articles/") }.toSet()
        if (articleFiles != referenced) fail("articles/index.json", "Article files do not exactly match indexed Markdown and illustrations.")
    }

    private fun validateTeasShape(root: JSONObject) {
        strictKeys(root, setOf("references", "teas", "tastings", "stockMovements"), "data/teas.json")
        array(root, "references", "data/teas.json").forEachIndexed { i, raw ->
            val p = "data/teas.json.references[$i]"; val r = raw as? JSONObject ?: fail(p, "Object required.")
            strictKeys(r, setOf("id", "kind", "semanticKey", "label", "parentCountryId", "archivedAt", "displayPriority"), p)
            string(r, "id", p); string(r, "kind", p); string(r, "label", p); integer(r, "displayPriority", p)
            nullableString(r, "semanticKey", p); nullableString(r, "parentCountryId", p); nullableString(r, "archivedAt", p)
        }
        array(root, "teas", "data/teas.json").forEachIndexed { i, raw ->
            val p = "data/teas.json.teas[$i]"; val t = raw as? JSONObject ?: fail(p, "Object required.")
            strictKeys(t, setOf("id", "legacyId", "name", "categoryId", "description", "technicalInformation", "personalNotes", "olfactoryRating", "tasteRating", "brewTemperatureCelsius", "infusionDurationSeconds", "statusId", "containerId", "countryIds", "regionIds", "aromaIds", "aromaticProfileIds", "momentIds", "badgeIds", "stockMilliGrams", "archivedAt"), p)
            string(t,"id",p); nullableInteger(t,"legacyId",p); if(string(t,"name",p).isBlank()) fail("$p.name","Name cannot be blank.")
            string(t,"categoryId",p); listOf("description","technicalInformation","personalNotes","statusId","containerId","archivedAt").forEach { nullableString(t,it,p) }
            listOf("olfactoryRating","tasteRating").forEach { nullableNumber(t,it,p) }
            listOf("brewTemperatureCelsius","infusionDurationSeconds","stockMilliGrams").forEach { nullableInteger(t,it,p) }
            listOf("countryIds","regionIds","aromaIds","aromaticProfileIds","momentIds","badgeIds").forEach { key ->
                array(t,key,p).forEachIndexed { j,v -> if(v !is String) fail("$p.$key[$j]","String reference id required.") }
            }
        }
        array(root,"tastings","data/teas.json").forEachIndexed { i, raw ->
            val p="data/teas.json.tastings[$i]"; val t=raw as? JSONObject ?: fail(p,"Object required.")
            strictKeys(t,setOf("id","teaId","occurredAt","recordedAt","consumedMilliGrams"),p)
            listOf("id","teaId","occurredAt","recordedAt").forEach { string(t,it,p) }; nullableInteger(t,"consumedMilliGrams",p)
        }
        array(root,"stockMovements","data/teas.json").forEachIndexed { i, raw ->
            val p="data/teas.json.stockMovements[$i]"; val m=raw as? JSONObject ?: fail(p,"Object required.")
            strictKeys(m,setOf("id","teaId","kind","deltaMilliGrams","stockAfterMilliGrams","occurredAt","recordedAt","tastingId","note"),p)
            listOf("id","teaId","kind","occurredAt","recordedAt").forEach { string(m,it,p) }
            nullableInteger(m,"deltaMilliGrams",p); integer(m,"stockAfterMilliGrams",p); nullableString(m,"tastingId",p); nullableString(m,"note",p)
        }
    }

    private fun validateConfigurationShape(root: JSONObject) {
        strictKeys(root,setOf("references","preferences"),"data/configuration.json")
        array(root,"references","data/configuration.json").forEachIndexed { i, raw ->
            val p="data/configuration.json.references[$i]"; val r=raw as? JSONObject ?: fail(p,"Object required.")
            strictKeys(r,setOf("id","kind","semanticKey","label","parentCountryId","archivedAt","displayPriority"),p)
            string(r,"id",p); string(r,"kind",p); string(r,"label",p); integer(r,"displayPriority",p)
            nullableString(r,"semanticKey",p); nullableString(r,"parentCountryId",p); nullableString(r,"archivedAt",p)
        }
        val p="data/configuration.json.preferences"; val prefs=obj(root,"preferences","data/configuration.json")
        strictKeys(prefs,setOf("quantityPerCupMilliGrams","lowStockThresholdMilliGrams"),p)
        if(integer(prefs,"quantityPerCupMilliGrams",p)<=0 || integer(prefs,"lowStockThresholdMilliGrams",p)<0) fail(p,"Preference values are outside valid ranges.")
    }

    private fun validateImage(path: String, mediaType: String, b: ByteArray) {
        if (b.size.toLong() > MAX_IMAGE_BYTES) fail(path, "Image exceeds 10 MiB.")
        val actual = when {
            b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) -> "image/png"
            b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            b.size >= 4 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[b.size-2] == 0xff.toByte() && b.last() == 0xd9.toByte() -> "image/jpeg"
            else -> fail(path, "JPEG, PNG or WebP image signature required.")
        }
        if (mediaType != actual) fail(path, "Media type does not match image signature.")
        val dimensions = imageDimensions(actual, b) ?: fail(path, "Image dimensions could not be safely validated.")
        if (dimensions.first !in 1..MAX_IMAGE_DIMENSION || dimensions.second !in 1..MAX_IMAGE_DIMENSION) fail(path, "Image dimensions must be at most 4096x4096.")
    }

    private fun imageDimensions(type: String, b: ByteArray): Pair<Int, Int>? = try {
        when (type) {
            "image/png" -> if (b.size >= 24) readInt(b, 16) to readInt(b, 20) else null
            "image/webp" -> webpDimensions(b)
            else -> jpegDimensions(b)
        }
    } catch (_: Exception) { null }

    private fun webpDimensions(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30) return null
        return when (String(b, 12, 4, Charsets.US_ASCII)) {
            "VP8X" -> ((b[24].toInt() and 255) or ((b[25].toInt() and 255) shl 8) or ((b[26].toInt() and 255) shl 16)) + 1 to
                ((b[27].toInt() and 255) or ((b[28].toInt() and 255) shl 8) or ((b[29].toInt() and 255) shl 16)) + 1
            else -> null
        }
    }

    private fun jpegDimensions(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xff.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 255; i += 2
            if (marker in 0xc0..0xc3 || marker in 0xc5..0xc7 || marker in 0xc9..0xcb || marker in 0xcd..0xcf) return readShort(b, i + 5) to readShort(b, i + 3)
            val len = readShort(b, i); if (len < 2) return null; i += len
        }
        return null
    }

    private fun validatePath(path: String, context: String) {
        if (path.isEmpty() || path.startsWith('/') || path.startsWith('\\') || path.contains('\\') || path.contains(':') || path.any { it.code < 0x20 }) fail(context, "Unsafe archive path.")
        val components = path.split('/')
        if (components.any { it.isEmpty() || it == "." || it == ".." }) fail(context, "Unsafe archive path component.")
    }
    private fun parseObject(bytes: ByteArray, path: String): JSONObject {
        val text = validateUtf8(bytes, path)
        try { val t = JSONTokener(text); val v = t.nextValue(); if (v !is JSONObject || t.nextClean() != '\u0000') fail(path, "One JSON object with no trailing data required."); return v }
        catch (e: ValidationException) { throw e } catch (e: Exception) { throw ValidationException(path, "Invalid JSON.", e) }
    }
    private fun validateUtf8(bytes: ByteArray, path: String): String = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: Exception) { throw ValidationException(path, "Invalid UTF-8.", e) }
    private fun strictKeys(o: JSONObject, allowed: Set<String>, path: String) { val keys=o.keys().asSequence().toSet(); val extra=keys-allowed; val missing=allowed-keys; if(extra.isNotEmpty()) fail(path,"Unknown properties: ${extra.sorted().joinToString()}."); if(missing.isNotEmpty()) fail(path,"Missing properties: ${missing.sorted().joinToString()}.") }
    private fun string(o: JSONObject, k: String, p: String): String = o.opt(k) as? String ?: fail("$p.$k","String required.")
    private fun nullableString(o: JSONObject,k:String,p:String) { val v=o.opt(k); if(v!==JSONObject.NULL && v !is String) fail("$p.$k","String or null required.") }
    private fun nullableInteger(o:JSONObject,k:String,p:String) { val v=o.opt(k); if(v!==JSONObject.NULL && (v !is Number || v.toDouble()!=v.toLong().toDouble())) fail("$p.$k","Integer or null required.") }
    private fun nullableNumber(o:JSONObject,k:String,p:String) { val v=o.opt(k); if(v!==JSONObject.NULL && (v !is Number || !v.toDouble().isFinite())) fail("$p.$k","Finite number or null required.") }
    private fun integer(o: JSONObject, k: String, p: String): Long = (o.opt(k) as? Number)?.toLong()?.takeIf { it.toDouble() == (o.opt(k) as Number).toDouble() } ?: fail("$p.$k","Integer required.")
    private fun obj(o: JSONObject,k:String,p:String)=o.opt(k) as? JSONObject ?: fail("$p.$k","Object required.")
    private fun array(o: JSONObject,k:String,p:String): List<Any?> {
        val value = o.opt(k) as? JSONArray ?: fail("$p.$k","Array required.")
        return (0 until value.length()).map(value::opt)
    }
    private fun readInt(b:ByteArray,i:Int)=((b[i].toInt() and 255) shl 24) or ((b[i+1].toInt() and 255) shl 16) or ((b[i+2].toInt() and 255) shl 8) or (b[i+3].toInt() and 255)
    private fun readShort(b:ByteArray,i:Int)=((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
    private fun fail(path:String,message:String):Nothing=throw ValidationException(path,message)
}
