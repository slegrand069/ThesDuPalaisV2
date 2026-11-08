# Contrat JSON de collection v1

Ce contrat est autonome et distinct du format historique de V1. Il décrit un instantané de collection pour l'import initial, et pourra servir de base à une sauvegarde/restauration ultérieure. La version 1 ne transporte pas les fichiers photo.

## Enveloppe

Les propriétés de premier niveau sont obligatoires et aucune propriété inconnue n'est acceptée :

```json
{
  "format": "thesdupalais.collection",
  "schemaVersion": 1,
  "datasetId": "UUID",
  "createdAt": "2026-01-02T10:30:00.000Z",
  "references": {
    "categories": [],
    "statuses": [],
    "containers": [],
    "countries": [],
    "regions": [],
    "aromas": [],
    "aromaticProfiles": [],
    "moments": [],
    "badges": []
  },
  "preferences": {
    "quantityPerCupMilliGrams": 7000,
    "lowStockThresholdMilliGrams": 25000
  },
  "teas": [],
  "tastings": [],
  "stockMovements": []
}
```

`datasetId` est un UUID canonique stable pour le jeu de données. `createdAt`, `archivedAt`, `occurredAt` et `recordedAt` sont des dates ISO 8601 comportant un fuseau et une précision maximale à la milliseconde. Les UUID sont des chaînes au format canonique `8-4-4-4-12`. Les dates et valeurs nullables sont présentes explicitement avec `null`. Les entiers et nombres sont des nombres JSON, jamais des chaînes.

## Référentiels

Chaque clé de `references` contient des objets avec les propriétés obligatoires :

```json
{
  "id": "UUID",
  "semanticKey": null,
  "label": "Libellé",
  "parentCountryId": null,
  "archivedAt": null,
  "displayPriority": 0
}
```

Le type est déterminé par la liste : `categories` → `CATEGORY`, `statuses` → `STATUS`, `containers` → `CONTAINER`, `countries` → `COUNTRY`, `regions` → `REGION`, `aromas` → `AROMA`, `aromaticProfiles` → `AROMATIC_PROFILE`, `moments` → `MOMENT`, `badges` → `BADGE`. Une région doit référencer un pays présent dans `references.countries`; les autres types ont `parentCountryId: null`.

Une `semanticKey` identifie une valeur standard. Lorsqu'elle correspond à un référentiel standard déjà présent, l'import la résout par couple type/clé sémantique et conserve l'identité et le libellé locaux. Ainsi un ancien référentiel de catégorie libellé « Vert » reste compatible avec `CATEGORY/GREEN`. L'égalité de libellé seule ne suffit jamais à fusionner deux référentiels.

Les associations de thé utilisent les UUID des objets déclarés dans ces listes. Associer une région ajoute automatiquement son pays à l'association effective du thé, même si son UUID n'est pas répété dans `countryIds`.

## Thés

Les propriétés requises sont :

```json
{
  "id": "UUID",
  "legacyId": null,
  "name": "Nom du thé",
  "categoryId": "UUID de CATEGORY",
  "description": null,
  "technicalInformation": null,
  "personalNotes": null,
  "olfactoryRating": null,
  "tasteRating": null,
  "brewTemperatureCelsius": null,
  "infusionDurationSeconds": null,
  "statusId": null,
  "containerId": null,
  "countryIds": [],
  "regionIds": [],
  "aromaIds": [],
  "aromaticProfileIds": [],
  "momentIds": [],
  "badgeIds": [],
  "stockMilliGrams": null,
  "archivedAt": null
}
```

`legacyId` est un entier positif ou `null`. Le nom n'est pas vide. Les notes sont nulles ou finies dans `[0, 10]`; la température est nulle ou un entier dans `[0, 100]`; la durée est nulle ou un entier positif en secondes. Le stock est nul (inconnu) ou un entier positif/nul en millièmes de gramme. Aucun stock initial n'est inventé.

## Dégustations

Chaque objet contient `id`, `teaId`, `occurredAt`, `recordedAt` et `consumedMilliGrams`. Les deux premiers sont des UUID; les dates sont ISO 8601; la quantité est un entier strictement positif en millièmes de gramme ou `null`. Une dégustation est un événement, sans note d'évaluation.

## Mouvements de stock

Chaque objet contient les propriétés obligatoires `id` (UUID), `teaId` (UUID), `kind` (chaîne), `deltaMilliGrams` (entier ou null), `stockAfterMilliGrams` (entier), `occurredAt` et `recordedAt` (dates ISO 8601), `tastingId` (UUID ou null) et `note` (chaîne ou null). Les types autorisés sont `INVENTORY`, `CONSUMPTION`, `RESTOCK` et `CORRECTION`. `INVENTORY` porte un delta nul et définit un solde de départ; une consommation porte un delta négatif; un réapprovisionnement un delta positif; une correction un delta non nul. Les soldes ne peuvent être négatifs et chaque solde doit correspondre au mouvement précédent. Le dernier solde doit être égal à `teas[].stockMilliGrams`.

Un thé au stock `null` ne peut avoir de mouvements; un thé au stock connu doit avoir une base `INVENTORY`. Un mouvement de consommation peut référencer une dégustation du même thé si sa quantité est connue et égale à l'opposé du delta. Aucun mouvement n'est créé implicitement à partir des dégustations.

## Préférences et idempotence

`quantityPerCupMilliGrams` doit être strictement positif; `lowStockThresholdMilliGrams` doit être positif ou nul. Les valeurs sont enregistrées dans DataStore après la transaction Room. Le registre Room conserve aussi ces valeurs pour rendre une nouvelle tentative récupérable si la synchronisation DataStore échoue.

Une empreinte SHA-256 du contenu validé et normalisé accompagne `datasetId` et `schemaVersion` dans le registre Room; les espaces, l'ordre des propriétés JSON et l'ordre des tableaux de valeurs ne modifient pas cette empreinte. Réimporter le même jeu de données avec le même `datasetId` est un succès sans réécriture. Réutiliser un `datasetId` avec un autre contenu est une erreur explicite. La fusion avec une collection déjà présente n'est pas prise en charge.

La validation précède l'écriture; référentiels, thés, associations, dégustations, mouvements et registre sont écrits dans une seule transaction Room. Une erreur de relation ou de contrainte annule les écritures de cette transaction. Aucun fichier réel du catalogue historique n'est inclus dans les fixtures de test.
