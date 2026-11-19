#!/usr/bin/env python3
"""Génère articles.json et les paquets ZIP individuels depuis les sources Markdown."""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import uuid
import zipfile
from io import BytesIO
from dataclasses import dataclass
from pathlib import Path


FORMAT = "thesdupalais.articles"
SCHEMA_VERSION = 1
ALLOWED_METADATA = {"title", "category", "uuid"}
UUID_PATTERN = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
METADATA_LINE = re.compile(r"^([A-Za-z][A-Za-z0-9_-]*):[ \t]*(.*)$")
IMAGE_REFERENCE = re.compile(r"!\[([^\]]*)]\(<?([^\s)>]+)>?(\s+\"[^\"]*\")?\)")
IMAGE_NAME = re.compile(r"[A-Za-z0-9._-]{1,100}\.(?:jpg|jpeg)", re.IGNORECASE)
MAX_ARCHIVE_BYTES = 50 * 1024 * 1024
MAX_EXPANDED_BYTES = 100 * 1024 * 1024
MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_MARKDOWN_BYTES = 2 * 1024 * 1024
MAX_FILES = 150


@dataclass
class SourceArticle:
    path: Path
    source_text: str
    closing_offset: int
    body: str
    title: str
    category: str
    article_id: str | None
    had_uuid: bool


class GenerationFailure(Exception):
    def __init__(self, errors: list[str]):
        self.errors = errors
        super().__init__("\n".join(errors))


def _line_text(line: str) -> str:
    if line.endswith("\r\n"):
        return line[:-2]
    if line.endswith("\n") or line.endswith("\r"):
        return line[:-1]
    return line


def _parse_metadata_value(raw: str, key: str, path: Path, line_number: int) -> str:
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ValueError(f"ligne {line_number} : {key} doit être une chaîne JSON sur une seule ligne ({error.msg}).") from error
    if not isinstance(value, str):
        raise ValueError(f"ligne {line_number} : {key} doit être une chaîne JSON.")
    return value


def parse_source(path: Path, text: str) -> SourceArticle:
    has_bom = text.startswith("\ufeff")
    content = text[1:] if has_bom else text
    lines = content.splitlines(keepends=True)
    if not lines or _line_text(lines[0]) != "---":
        raise ValueError("les métadonnées doivent commencer par --- en première ligne.")

    closing_line_index = next((i for i in range(1, len(lines)) if _line_text(lines[i]) == "---"), None)
    if closing_line_index is None:
        raise ValueError("séparateur final --- des métadonnées absent.")

    metadata: dict[str, str] = {}
    offset = 0
    closing_offset = 0
    for index, line in enumerate(lines):
        if index == closing_line_index:
            closing_offset = offset
            break
        if index > 0:
            raw_line = _line_text(line)
            if raw_line.strip():
                match = METADATA_LINE.fullmatch(raw_line)
                if not match:
                    raise ValueError(f"ligne {index + 1} : métadonnée invalide; format attendu : nom: chaîne JSON.")
                key, raw_value = match.groups()
                if key not in ALLOWED_METADATA:
                    raise ValueError(f"ligne {index + 1} : champ de métadonnées inconnu « {key} ».")
                if key in metadata:
                    raise ValueError(f"ligne {index + 1} : métadonnée « {key} » répétée.")
                metadata[key] = _parse_metadata_value(raw_value, key, path, index + 1)
        offset += len(line)

    missing = {"title", "category"} - metadata.keys()
    if missing:
        raise ValueError(f"métadonnée(s) obligatoire(s) absente(s) : {', '.join(sorted(missing))}.")
    title = metadata["title"].strip()
    category = metadata["category"].strip()
    if not title:
        raise ValueError("title ne peut pas être vide.")
    if not category:
        raise ValueError("category ne peut pas être vide.")

    article_id = metadata.get("uuid")
    if article_id is not None and not UUID_PATTERN.fullmatch(article_id):
        raise ValueError("uuid invalide; format canonique 8-4-4-4-12 attendu.")

    # The line ending after the closing delimiter and one optional blank separator line
    # belong to the metadata block. The remaining body is copied verbatim.
    body_offset = offset + len(lines[closing_line_index])
    body = content[body_offset:]
    if body.startswith("\r\n"):
        body = body[2:]
    elif body.startswith("\n") or body.startswith("\r"):
        body = body[1:]
    if has_bom:
        closing_offset += 1

    return SourceArticle(path, text, closing_offset, body, title, category, article_id, "uuid" in metadata)


def atomic_write(path: Path, text: str) -> None:
    """Write UTF-8 in the destination directory and replace the target atomically."""
    path = Path(path)
    temporary_path: Path | None = None
    try:
        fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
        temporary_path = Path(temporary_name)
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, path)
    except OSError:
        if temporary_path is not None:
            try:
                if temporary_path.exists():
                    temporary_path.unlink()
            except OSError:
                pass
        raise


def atomic_write_bytes(path: Path, data: bytes) -> None:
    """Write bytes beside the destination and replace it atomically."""
    path = Path(path)
    temporary_path: Path | None = None
    try:
        fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
        temporary_path = Path(temporary_name)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, path)
    except OSError:
        if temporary_path is not None:
            try:
                temporary_path.unlink(missing_ok=True)
            except OSError:
                pass
        raise


def read_referenced_images(article: SourceArticle) -> dict[str, bytes]:
    """Read only referenced JPEGs from the article's adjacent images/ folder."""
    refs = [match.group(2) for match in IMAGE_REFERENCE.finditer(article.body)]
    images: dict[str, bytes] = {}
    normalized: dict[str, str] = {}
    images_dir = (article.path.parent / "images").resolve()
    for ref in refs:
        if not ref.startswith("images/") or ref.count("/") != 1 or "\\" in ref or ":" in ref:
            raise ValueError(f"référence d’image non prise en charge : {ref!r}; utiliser images/nom.jpg.")
        name = ref.removeprefix("images/")
        if not IMAGE_NAME.fullmatch(name):
            raise ValueError(f"nom d’image invalide : {name!r}; seuls les fichiers JPEG .jpg/.jpeg sont acceptés.")
        previous = normalized.get(name.casefold())
        if previous is not None and previous != name:
            raise ValueError(f"références d’image ambiguës à cause de la casse : {previous!r} et {name!r}.")
        normalized[name.casefold()] = name
        if ref in images:
            continue
        path = article.path.parent / "images" / name
        try:
            resolved = path.resolve(strict=True)
            resolved.relative_to(images_dir)
        except (OSError, ValueError) as error:
            raise ValueError(f"image absente ou chemin non sûr : {ref}.") from error
        if not resolved.is_file():
            raise ValueError(f"image absente : {ref}.")
        data = resolved.read_bytes()
        if len(data) > MAX_IMAGE_BYTES:
            raise ValueError(f"image trop volumineuse (limite 10 MiB) : {ref}.")
        if len(data) < 4 or data[:2] != b"\xff\xd8" or data[-2:] != b"\xff\xd9":
            raise ValueError(f"fichier JPEG illisible ou invalide : {ref}.")
        images[ref] = data
    return images


def build_article_zip(article: SourceArticle, images: dict[str, bytes]) -> bytes:
    markdown = article.body.encode("utf-8")
    manifest = {
        "format": "thesdupalais.article",
        "schemaVersion": 1,
        "id": article.article_id,
        "title": article.title,
        "category": article.category,
        "markdownPath": "article.md",
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    if len(markdown) > MAX_MARKDOWN_BYTES:
        raise ValueError("article.md dépasse la limite Android de 2 MiB.")
    if len(images) + 2 > MAX_FILES:
        raise ValueError("le paquet dépasse la limite Android de 150 fichiers.")
    if len(manifest_bytes) + len(markdown) + sum(map(len, images.values())) > MAX_EXPANDED_BYTES:
        raise ValueError("le contenu décompressé dépasse la limite Android de 100 MiB.")
    output = BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in [("article.json", manifest_bytes), ("article.md", markdown), *images.items()]:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    result = output.getvalue()
    if len(result) > MAX_ARCHIVE_BYTES:
        raise ValueError("l’archive ZIP dépasse la limite Android de 50 MiB.")
    return result


def _newline_for(text: str) -> str:
    match = re.search(r"\r\n|\n|\r", text)
    return match.group(0) if match else "\r\n"


def generate(sources_dir: Path, output_path: Path, packages_dir: Path | None = None) -> list[dict[str, str]]:
    sources_dir = Path(sources_dir)
    output_path = Path(output_path)
    packages_dir = Path(packages_dir) if packages_dir is not None else output_path.parent / "packages"
    if not sources_dir.is_dir():
        raise GenerationFailure([f"{sources_dir} : dossier de sources introuvable."])

    files = sorted(
        (path for path in sources_dir.iterdir() if path.is_file() and path.suffix.lower() == ".md"),
        key=lambda path: (path.name.casefold(), path.name),
    )
    parsed: list[SourceArticle] = []
    errors: list[str] = []
    for path in files:
        try:
            text = path.read_bytes().decode("utf-8")
            parsed.append(parse_source(path, text))
        except (OSError, UnicodeDecodeError, ValueError) as error:
            errors.append(f"{path} : {error}")

    id_paths: dict[str, list[Path]] = {}
    for article in parsed:
        if article.article_id is not None:
            normalized_id = str(uuid.UUID(article.article_id))
            id_paths.setdefault(normalized_id, []).append(article.path)
    for article_id, paths in id_paths.items():
        if len(paths) > 1:
            errors.extend(f"{path} : UUID dupliqué {article_id} (également présent dans {', '.join(str(other) for other in paths if other != path)})." for path in paths)

    if errors:
        raise GenerationFailure(errors)

    article_images: dict[Path, dict[str, bytes]] = {}
    for article in parsed:
        try:
            article_images[article.path] = read_referenced_images(article)
        except (OSError, ValueError) as error:
            errors.append(f"{article.path} : {error}")
    if errors:
        raise GenerationFailure(errors)

    for article in parsed:
        if article.article_id is None:
            article.article_id = str(uuid.uuid4())

    # Persist every generated identity before publishing JSON. A failed source write
    # aborts export, so no JSON claims an identity that was not saved in its Markdown.
    for article in parsed:
        if article.had_uuid:
            continue
        newline = _newline_for(article.source_text)
        source_without_bom = article.source_text[1:] if article.source_text.startswith("\ufeff") else article.source_text
        closing_offset = article.closing_offset - (1 if article.source_text.startswith("\ufeff") else 0)
        updated = source_without_bom[:closing_offset] + f'uuid: {json.dumps(article.article_id, ensure_ascii=False)}{newline}' + source_without_bom[closing_offset:]
        if article.source_text.startswith("\ufeff"):
            updated = "\ufeff" + updated
        try:
            atomic_write(article.path, updated)
        except OSError as error:
            raise GenerationFailure([f"{article.path} : impossible d'enregistrer durablement l'UUID ({error}). Aucun JSON n'a été produit."]) from error

    result = [
        {"id": article.article_id, "title": article.title, "category": article.category, "markdown": article.body}
        for article in parsed
    ]
    document = {"format": FORMAT, "schemaVersion": SCHEMA_VERSION, "articles": result}
    json_text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    packages: list[tuple[Path, bytes]] = []
    for article in parsed:
        try:
            package = build_article_zip(article, article_images[article.path])
            packages.append((packages_dir / f"{article.article_id}.zip", package))
        except (OSError, ValueError) as error:
            raise GenerationFailure([f"{article.path} : {error}. Aucun ZIP incomplet n’a été produit."]) from error
    try:
        packages_dir.mkdir(parents=True, exist_ok=True)
        for package_path, package_bytes in packages:
            atomic_write_bytes(package_path, package_bytes)
    except OSError as error:
        raise GenerationFailure([f"{packages_dir} : impossible d’écrire les ZIP ({error})."]) from error
    try:
        atomic_write(output_path, json_text)
    except OSError as error:
        raise GenerationFailure([f"{output_path} : impossible d'écrire le JSON ({error}); l'ancien fichier est conservé si présent."]) from error
    return result


def main() -> int:
    base_dir = Path(__file__).resolve().parent
    sources_dir = base_dir / "sources"
    output_path = base_dir / "articles.json"
    packages_dir = base_dir / "packages"
    try:
        articles = generate(sources_dir, output_path, packages_dir)
    except GenerationFailure as error:
        for message in error.errors:
            print(f"Erreur : {message}", file=sys.stderr)
        print(f"Génération interrompue : {len(error.errors)} erreur(s), aucun paquet incomplet publié.", file=sys.stderr)
        print(f"Dossier de sortie prévu : {packages_dir}", file=sys.stderr)
        return 1
    print(f"Génération terminée : {len(articles)} article(s) traité(s), {len(articles)} ZIP généré(s), 0 erreur.")
    print(f"JSON historique : {output_path}")
    print(f"Paquets Android : {packages_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
