import json
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import generer


class ArticleGeneratorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.sources = self.root / "sources"
        self.sources.mkdir()
        self.output = self.root / "articles.json"

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def source(title="Article", category="Culture", body="Texte.", article_id=None, extra=""):
        metadata = [f"title: {json.dumps(title, ensure_ascii=False)}", f"category: {json.dumps(category, ensure_ascii=False)}"]
        if article_id is not None:
            metadata.append(f"uuid: {json.dumps(article_id)}")
        if extra:
            metadata.append(extra)
        return "---\n" + "\n".join(metadata) + "\n---\n\n" + body

    def write_source(self, name, text):
        path = self.sources / name
        path.write_bytes(text.encode("utf-8"))
        return path

    def read_export(self):
        return json.loads(self.output.read_text(encoding="utf-8"))

    def test_generates_and_persists_uuid_then_reuses_it_after_article_edit(self):
        path = self.write_source("note.md", self.source(body="# Départ"))
        first = generer.generate(self.sources, self.output)[0]
        persisted = path.read_text(encoding="utf-8")
        self.assertRegex(first["id"], r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        self.assertIn(f'uuid: "{first["id"]}"', persisted)

        path.write_bytes(persisted.replace("# Départ", "# Article modifié").encode("utf-8"))
        second = generer.generate(self.sources, self.output)[0]
        self.assertEqual(first["id"], second["id"])
        self.assertEqual("# Article modifié", second["markdown"])

    def test_existing_uuid_is_never_replaced(self):
        known_id = "123e4567-e89b-12d3-a456-426614174000"
        self.write_source("note.md", self.source(article_id=known_id))
        result = generer.generate(self.sources, self.output)
        self.assertEqual(known_id, result[0]["id"])

    def test_rejects_unknown_and_malformed_metadata_without_replacing_old_json(self):
        self.output.write_text('{"ancien":true}', encoding="utf-8")
        self.write_source("invalid.md", self.source(extra='unknown: "value"'))
        with self.assertRaises(generer.GenerationFailure) as raised:
            generer.generate(self.sources, self.output)
        self.assertTrue(any("inconnu" in error for error in raised.exception.errors))
        self.assertEqual('{"ancien":true}', self.output.read_text(encoding="utf-8"))

    def test_rejects_missing_required_metadata_and_non_json_metadata_values(self):
        self.write_source("missing.md", "---\ncategory: \"Culture\"\n---\n\nTexte")
        self.write_source("wrong-type.md", "---\ntitle: 12\ncategory: \"Culture\"\n---\n")
        with self.assertRaises(generer.GenerationFailure) as raised:
            generer.generate(self.sources, self.output)
        self.assertEqual(2, len(raised.exception.errors))

    def test_rejects_invalid_or_duplicate_uuids_without_export(self):
        self.output.write_text("ancien json", encoding="utf-8")
        self.write_source("invalid.md", self.source(article_id="not-a-uuid"))
        with self.assertRaises(generer.GenerationFailure):
            generer.generate(self.sources, self.output)
        self.assertEqual("ancien json", self.output.read_text(encoding="utf-8"))

        for path in self.sources.iterdir():
            path.unlink()
        duplicate_id = "123e4567-e89b-12d3-a456-426614174000"
        self.write_source("a.md", self.source(article_id=duplicate_id))
        self.write_source("b.md", self.source(article_id=duplicate_id))
        with self.assertRaises(generer.GenerationFailure) as raised:
            generer.generate(self.sources, self.output)
        self.assertEqual(2, len(raised.exception.errors))
        self.assertEqual("ancien json", self.output.read_text(encoding="utf-8"))

    def test_preserves_accents_quotes_empty_body_and_markdown_extensions(self):
        body = "# Thé « doux »\n\n<u>Cœur</u> & <color #FF7D00>chaud</color> **brûlant**."
        self.write_source("article.md", self.source(title='Thé « doux »', category="Dégustation", body=body))
        self.write_source("empty.md", self.source(title="Vide", body=""))
        articles = generer.generate(self.sources, self.output)
        self.assertEqual(["Thé « doux »", "Vide"], [article["title"] for article in articles])
        exported = self.read_export()
        self.assertEqual("Thé « doux »", exported["articles"][0]["title"])
        self.assertEqual(body, exported["articles"][0]["markdown"])
        self.assertEqual("", exported["articles"][1]["markdown"])
        self.assertIn("Dégustation", self.output.read_text(encoding="utf-8"))

    def test_keeps_markdown_body_exact_and_contract_has_only_expected_fields(self):
        body = "\n# Titre\r\n\r\n- <u>liste</u>\r\n<color #0088CC>bleu</color>\r\n"
        self.write_source("b.md", self.source(body=body))
        self.write_source("A.md", self.source(title="Premier", body=""))
        generer.generate(self.sources, self.output)
        document = self.read_export()
        self.assertEqual(["format", "schemaVersion", "articles"], list(document.keys()))
        self.assertEqual(["Premier", "Article"], [article["title"] for article in document["articles"]])
        self.assertEqual(["id", "title", "category", "markdown"], list(document["articles"][0].keys()))
        self.assertEqual("\n# Titre\r\n\r\n- <u>liste</u>\r\n<color #0088CC>bleu</color>\r\n", document["articles"][1]["markdown"])
        self.assertEqual("thesdupalais.articles", document["format"])
        self.assertEqual(1, document["schemaVersion"])

    def test_source_uuid_write_failure_does_not_replace_existing_json(self):
        self.output.write_text('{"ancien":true}', encoding="utf-8")
        path = self.write_source("new.md", self.source())
        original = path.read_text(encoding="utf-8")
        with mock.patch.object(generer, "atomic_write", side_effect=OSError("disque indisponible")):
            with self.assertRaises(generer.GenerationFailure):
                generer.generate(self.sources, self.output)
        self.assertEqual(original, path.read_text(encoding="utf-8"))
        self.assertEqual('{"ancien":true}', self.output.read_text(encoding="utf-8"))

    def test_output_replace_failure_keeps_previous_json(self):
        self.output.write_text('{"ancien":true}', encoding="utf-8")
        self.write_source("new.md", self.source())
        real_atomic_write = generer.atomic_write

        def fail_only_json(path, text):
            if Path(path) == self.output:
                raise OSError("écriture impossible")
            real_atomic_write(path, text)

        with mock.patch.object(generer, "atomic_write", side_effect=fail_only_json):
            with self.assertRaises(generer.GenerationFailure):
                generer.generate(self.sources, self.output)
        self.assertEqual('{"ancien":true}', self.output.read_text(encoding="utf-8"))

    def test_empty_sources_generates_empty_contract(self):
        result = generer.generate(self.sources, self.output)
        self.assertEqual([], result)
        self.assertEqual(
            {"format": "thesdupalais.articles", "schemaVersion": 1, "articles": []},
            self.read_export(),
        )


if __name__ == "__main__":
    unittest.main()
