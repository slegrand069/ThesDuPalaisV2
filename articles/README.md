# Générateur des articles « Savoirs »

Le dossier contient `generer.py`, son lanceur Windows `generer.bat`, les fichiers sources dans `sources/`, le JSON historique `articles.json` et les paquets individuels dans `packages/`.

## Lancer sous Windows

Double-cliquez sur `generer.bat`. Python 3 doit être installé et la commande `py -3` disponible. La fenêtre reste ouverte jusqu'à une pression sur une touche. Depuis un terminal, on peut aussi exécuter :

```powershell
py -3 .\articles\generer.py
```

Le script utilise le dossier qui contient `generer.py`; le répertoire courant du terminal n'a pas d'importance.

## Écrire un article

Placez un fichier `.md` directement dans `sources/`, enregistré en UTF-8 :

```markdown
---
title: "La température d'infusion"
category: "Préparation"
---

# La température d'infusion

La température est **importante**.

<color #FF7D00>Attention à l'eau trop chaude.</color>
```

`title` et `category` sont requis. Chaque valeur de métadonnée est une chaîne JSON sur une seule ligne. `uuid` est facultatif; lors du premier export, le générateur le crée et l'écrit dans le fichier Markdown. Gardez cette métadonnée lorsque vous modifiez l'article afin que les prochains exports mettent à jour le même article.

Le corps Markdown est exporté tel quel. Les fichiers `.md` placés directement dans `sources/` sont parcourus par nom, dans un ordre déterministe; les sous-dossiers comme `sources/Save/` restent ignorés. Pour un article illustré, placez ses fichiers dans le dossier `images/` à côté de son Markdown et référencez-les ainsi : `![Feuilles](images/feuilles.jpg "Légende")`. Seuls les JPEG `.jpg` et `.jpeg` référencés sont ajoutés. Les chemins avec sous-dossiers, les images absentes, les fichiers non JPEG ou trop volumineux sont signalés; aucun paquet incomplet n'est généré.

Pour chaque source, le générateur produit `packages/<UUID>.zip` avec le manifeste Android `article.json`, le contenu `article.md` et les images référencées sous `images/`. L'UUID est celui du front matter et reste donc stable entre les générations. Les ZIP utilisent exactement le format `thesdupalais.article` version 1 attendu par l'import Android. Le JSON historique `articles.json` continue d'être produit pour préserver le flux existant; il n'embarque pas les fichiers images et ne remplace pas les ZIP pour sauvegarder les illustrations.

Le script ne produit pas de nouvel export si une source est invalide, si un UUID est dupliqué, si une image est manquante ou si l'enregistrement d'un UUID dans une source échoue. Un `articles.json` existant reste alors en place et les erreurs sont indiquées dans la console.

Exemple : avec `sources/mon-article.md` et `sources/images/feuilles.jpg`, contenant la référence `![Feuilles](images/feuilles.jpg)`, le résultat est `packages/<UUID de l'article>.zip`.

Un dossier `sources/` vide produit un fichier valide avec un tableau `articles` vide.

## Tests

Depuis la racine du dépôt :

```powershell
py -3 -m unittest discover -s articles\tests -v
```
