# Sauvegarde autonome du dictionnaire Madjo — format 1

Le fichier est un document JSON UTF-8 sélectionné avec les Storage Access Framework d’Android. Il n’emploie aucun chemin imposé et ne demande pas d’accès général au stockage.

La racine contient `format` (`thes-du-palais-madjo-dictionary`), `version` (`1`), puis les tableaux `concepts`, `expressions` et `relations`. Les propriétés et les objets sont sérialisés dans un ordre déterministe.

- Un concept contient son identifiant stable, son domaine, son libellé, ses trois champs de liaison métier, `active` et `deleted`.
- Une expression contient son identifiant stable, son concept, son texte, sa forme normalisée, `isPrimary` et `active`. Une expression de tombstone peut avoir un texte vide tout en conservant son identifiant et sa forme normalisée.
- Une relation contient son identifiant, la source générale, la cible spécialisée, le type `SPECIALIZATION` et son état actif.

L’export représente toutes les lignes logiques, pas un delta du dictionnaire intégré. L’import ne fusionne pas : après validation et confirmation, il remplace atomiquement uniquement les trois ensembles de lignes sémantiques. Les concepts initiaux indispensables doivent rester présents et valides. Les liaisons `ref:<UUID>` doivent désigner une référence métier existante et active du domaine attendu sur l’appareil destinataire. Le fichier n’inclut et ne modifie aucune fiche de thé, dégustation, donnée de stock, article ou définition de référence métier.

Un import échoué conserve le dictionnaire courant. Les versions futures sont rejetées par les applications qui ne les prennent pas en charge.
