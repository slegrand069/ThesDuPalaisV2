# Générateur des articles « Savoirs »

Le dossier contient `generer.py`, son lanceur Windows `generer.bat`, les fichiers sources dans `sources/` et le fichier généré `articles.json`.

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

Le corps Markdown est exporté tel quel. Les fichiers `.md` sont parcourus par nom, dans un ordre déterministe. Le script ne produit pas de nouvel export si une source est invalide, si un UUID est dupliqué ou si l'enregistrement d'un UUID dans une source échoue; un `articles.json` existant reste alors en place.

Un dossier `sources/` vide produit un fichier valide avec un tableau `articles` vide.

## Tests

Depuis la racine du dépôt :

```powershell
py -3 -m unittest discover -s articles\tests -v
```
