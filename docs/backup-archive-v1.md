# Contrat d’archive de sauvegarde v1

Ce contrat définit le conteneur `thesdupalais.backup`, indépendant du contrat `thesdupalais.collection`. Il ne modifie ni ne remplace ce dernier. Le validateur de Lot 4A est en lecture seule : il ne restaure aucune donnée.

## ZIP et limites

Une sauvegarde est toujours un ZIP. L’archive compressée ne dépasse pas 50 MiB (52 428 800 octets), et le total décompressé ne dépasse pas 150 MiB (157 286 400 octets). `manifest.json` est requis à la racine et limité à 1 MiB. Aucun chemin absolu, séparateur antislash, composant vide, `.`/`..`, lettre de lecteur ni caractère de contrôle n’est admis. Les chemins sont sensibles à la casse et uniques après interprétation ZIP. Les entrées ZIP supplémentaires non répertoriées sont interdites.

Le validateur lit les entrées en flux et compte les octets décompressés avant de les conserver. Les fichiers images ne dépassent pas 10 MiB et leurs dimensions sont au plus 4096 × 4096 pixels. Types admis : JPEG (`image/jpeg`), PNG (`image/png`) et WebP (`image/webp`). Signature, type déclaré et dimensions doivent concorder.

## Manifeste

Le manifeste UTF-8 est un objet JSON strict, sans propriété inconnue :

```json
{
  "format": "thesdupalais.backup",
  "schemaVersion": 1,
  "backupId": "123e4567-e89b-12d3-a456-426614174000",
  "createdAt": "2026-11-11T12:00:00.000Z",
  "producer": { "application": "ThésDuPalais", "version": "2.0" },
  "includedSets": ["teas", "configuration", "articles"],
  "entries": [
    { "path": "data/teas.json", "mediaType": "application/json", "sizeBytes": 123, "sha256": "<64 minuscules hexadécimaux>" }
  ]
}
```

`includedSets` contient une ou plusieurs valeurs uniques de `teas`, `configuration`, `articles`. Un ensemble vide reste sélectionné et possède son fichier d’index ; un ensemble absent n’en possède pas. Chaque entrée non manifeste est déclarée exactement une fois avec son chemin, son media type, sa taille et SHA-256. Le manifeste n’est pas auto-haché. SHA-256 détecte corruption ou incohérence, mais ne garantit pas l’authenticité de l’émetteur.

Fichiers obligatoires par ensemble : `data/teas.json`, `data/configuration.json`, `articles/index.json`. Des articles ajoutent un Markdown par article sous `articles/content/` et les illustrations sous `articles/media/`. Il n’y a pas de répertoire ZIP implicite nécessaire.

## Ensemble thés

`data/teas.json` est un objet strict contenant `references`, `teas`, `tastings`, `stockMovements`. Il reprend la représentation métier v1 du contrat `thesdupalais.collection` pour ces champs, à l’exception que les références résident dans cet objet comme dépendances autonomes. Il contient les définitions complètes des seules références utilisées, y compris archivées : type, UUID, `semanticKey`, libellé, parent pays, `archivedAt`, `displayPriority`. Les associations se réfèrent à leurs UUID. Les UUID, `legacyId`, toutes les associations, dégustations et mouvements sont préservés. Les valeurs nulles explicites restent distinctes des zéros/vides. Pas de correspondance par le seul libellé.

Les contraintes métier existantes du parseur `thesdupalais.collection` s’appliquent par adaptation en mémoire, sans changement du contrat historique : région avec pays parent de type COUNTRY, références utilisées de type correct, UUID et `legacyId` non nuls uniques, dégustation appartenant à un thé inclus, mouvements de stock cohérents et dernier solde égal au stock du thé. Les associations régionales incluent effectivement le pays parent; si `countryIds` est fourni, il ne peut contredire le pays parent de la région. Quand thés et configuration sont présents ensemble, les références identiques sont admises; une même UUID, clé sémantique ou étiquette normalisée/portée pointant vers des définitions incompatibles est rejetée. Aucune dégustation ne crée implicitement de mouvement. Toute référence incluse en dépendance est clairement portée dans `data/teas.json`; elle ne signifie pas que l’export contient la configuration complète. Le validateur rejette aussi les références de dépendance inutilisées.

## Ensemble configuration

`data/configuration.json` est un objet strict `{ "references": [...], "preferences": {...} }`. `references` contient toutes les références, y compris archivées et non utilisées. Les objets référence ont `id`, `kind`, `semanticKey`, `label`, `parentCountryId`, `archivedAt`, `displayPriority`. Les préférences métier sont `quantityPerCupMilliGrams` (entier strictement positif) et `lowStockThresholdMilliGrams` (entier positif ou nul). L’état technique `installation_initialized` n’est pas exporté. Les mêmes contrôles d’identité, de type, d’unicité, de portée et de parent pays s’appliquent.

## Ensemble articles

`articles/index.json` est un objet strict `{ "articles": [...] }`. Chaque article a `id`, `title`, `section`, `markdownPath`, `illustrations`. `id` est un identifiant textuel stable indépendant du titre, de 1 à 128 caractères ASCII dans `[A-Za-z0-9][A-Za-z0-9._:-]*`; il est unique dans l’archive et ne contient aucun séparateur de chemin. Le PC doit préserver cet identifiant lors des modifications. Changer le titre ne crée pas un article neuf. Au réimport futur, l’ID reconnaîtra l’article; ID identique signifie candidat à mise à jour, ID différent signifie candidat à ajout. Cette règle d’identité n’autorise pas à écraser sans prévisualisation/confirmation, selon l’arbitrage fonctionnel.

`title` est non vide; `section` est chaîne ou `null`. `markdownPath` est un chemin relatif unique sous `articles/content/` et se termine en `.md`. `illustrations` est un tableau d’objets stricts `{ "path": ..., "alt": chaîne-ou-null }`, avec chemin unique sous `articles/media/`. Le Markdown est UTF-8. Les métadonnées appartiennent à l’index du contrat; leur source éditoriale doit être définie dans le guide de rédaction Markdown associé avant l’import. Les entrées article doivent correspondre exactement aux fichiers indexés.

## Validation et compatibilité

Le format et la version sont contrôlés avant l’analyse des données. Version inconnue, propriétés inconnues, entrée non déclarée, empreinte incorrecte, champ obligatoire absent, relation invalide, chemin dangereux, archive corrompue ou limite dépassée entraîne le rejet intégral, sans écriture. Les validations métier thés/configuration réutilisent le parseur de collection v1 sur un objet JSON temporaire en mémoire; ce parseur et le contrat collection ne sont pas modifiés. Les fichiers JSON sont des objets UTF-8 sans contenu final supplémentaire. Aucun support de compatibilité v1.01 n’est revendiqué : il reste nécessaire d’obtenir son JSON réel, sa définition de champs et ses règles d’identifiants/migration.

Règles non définies par les contrats actuels et donc non ajoutées : une signature d’authenticité, l’équivalence entre dates d’occurrence et d’enregistrement, et un mécanisme de résolution interactive des collisions. Les collisions lors de validation d’une archive contenant les deux ensembles sont rejetées; la fusion ultérieure devra les prévisualiser et demander confirmation.

## Évolution photos de thé

La version 1 réserve l’évolution à un nouvel ensemble ou à une nouvelle version de schéma, par exemple `teaPhotos` avec médias sous `teas/media/` et références stables par UUID de photo/thé et position. Le schéma v1 strict n’accepte pas ces propriétés/ensembles. Pour préserver la compatibilité, les futures versions doivent augmenter `schemaVersion`, documenter une migration explicite ou fournir un lecteur v1; un ancien lecteur rejette proprement une version inconnue. Les photos ne sont pas incluses implicitement dans l’ensemble thés v1.

## Atomicité de restauration

Le contrat et son validateur ne garantissent aucune atomicité d’écriture. Une future restauration devra prévoir reprise/retour arrière coordonnés pour Room, DataStore et fichiers médias; une transaction Room seule ne suffit pas.
