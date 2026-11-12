# Lot 4D.5 — Résultats de validation

- Tests JVM : 89 réussis.
- Tests instrumentés ciblés sur le Xiaomi `24115RA8EG` sous Android 16 : 9/9 réussis.
- Suite instrumentée complète sur le même appareil : 96/97 réussis.
- Compilation de l’application et `assembleDebug` : réussies.

## Échec instrumenté connu

`BackupMergePreviewSectionTest.selectingSetThroughCheckboxRecalculatesMatchingPlan` échoue sur le tag Compose `merge-preview-blocked`. Le test attend l’absence de ce blocage après sélection, mais il est présent. Le parcours correspondant a été validé manuellement dans l’application lors de la clôture de 4C.2b. Cet échec est conservé et n’a pas été masqué ni désactivé.

Les 9 tests ciblés 4D.5 couvrent la finalisation Room/DataStore/médias, la reprise après interruption et la conservation des fichiers médias encore référencés.
