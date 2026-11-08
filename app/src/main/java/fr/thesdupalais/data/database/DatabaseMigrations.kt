package fr.thesdupalais.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE reference_values_new (
                id TEXT NOT NULL,
                kind TEXT NOT NULL,
                label TEXT NOT NULL,
                normalizedLabel TEXT NOT NULL,
                semanticKey TEXT,
                parentCountryId TEXT,
                scopeKey TEXT NOT NULL,
                archivedAt INTEGER,
                displayPriority INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id),
                FOREIGN KEY(parentCountryId) REFERENCES reference_values_new(id)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO reference_values_new (
                id, kind, label, normalizedLabel, semanticKey, parentCountryId,
                scopeKey, archivedAt, displayPriority
            )
            SELECT id,
                CASE WHEN kind = 'COLOR' THEN 'CATEGORY' ELSE kind END,
                label, normalizedLabel, semanticKey, parentCountryId,
                scopeKey, archivedAt, 0
            FROM reference_values
            ORDER BY CASE WHEN kind = 'COUNTRY' THEN 0 ELSE 1 END
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE reference_values")
        db.execSQL("ALTER TABLE reference_values_new RENAME TO reference_values")

        listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_reference_values_kind_normalizedLabel_scopeKey` ON `reference_values` (`kind`, `normalizedLabel`, `scopeKey`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_reference_values_kind_semanticKey` ON `reference_values` (`kind`, `semanticKey`)",
            "CREATE INDEX IF NOT EXISTS `index_reference_values_parentCountryId` ON `reference_values` (`parentCountryId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_reference_values_id_parentCountryId` ON `reference_values` (`id`, `parentCountryId`)",
            "CREATE INDEX IF NOT EXISTS `index_reference_values_kind_displayPriority` ON `reference_values` (`kind`, `displayPriority`)",
        ).forEach(db::execSQL)

        listOf(
            """CREATE TABLE IF NOT EXISTS `teas` (`id` TEXT NOT NULL, `legacyId` INTEGER, `name` TEXT NOT NULL, `categoryRefId` TEXT NOT NULL, `description` TEXT, `technicalInformation` TEXT, `personalNotes` TEXT, `olfactoryRating` REAL, `tasteRating` REAL, `brewTemperatureCelsius` INTEGER, `infusionDurationSeconds` INTEGER, `statusRefId` TEXT, `containerRefId` TEXT, `stockMilliGrams` INTEGER, `archivedAtEpochMillis` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`categoryRefId`) REFERENCES `reference_values`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`statusRefId`) REFERENCES `reference_values`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`containerRefId`) REFERENCES `reference_values`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
            """CREATE TABLE IF NOT EXISTS `tea_countries` (`teaId` TEXT NOT NULL, `countryRefId` TEXT NOT NULL, PRIMARY KEY(`teaId`, `countryRefId`), FOREIGN KEY(`teaId`) REFERENCES `teas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`countryRefId`) REFERENCES `reference_values`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
            """CREATE TABLE IF NOT EXISTS `tea_regions` (`teaId` TEXT NOT NULL, `regionRefId` TEXT NOT NULL, `countryRefId` TEXT NOT NULL, PRIMARY KEY(`teaId`, `regionRefId`), FOREIGN KEY(`teaId`, `countryRefId`) REFERENCES `tea_countries`(`teaId`, `countryRefId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`regionRefId`, `countryRefId`) REFERENCES `reference_values`(`id`, `parentCountryId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
            """CREATE TABLE IF NOT EXISTS `tea_aromas` (`teaId` TEXT NOT NULL, `aromaRefId` TEXT NOT NULL, PRIMARY KEY(`teaId`, `aromaRefId`), FOREIGN KEY(`teaId`) REFERENCES `teas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`aromaRefId`) REFERENCES `reference_values`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
            """CREATE TABLE IF NOT EXISTS `tea_aromatic_profiles` (`teaId` TEXT NOT NULL, `profileRefId` TEXT NOT NULL, PRIMARY KEY(`teaId`, `profileRefId`), FOREIGN KEY(`teaId`) REFERENCES `teas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`profileRefId`) REFERENCES `reference_values`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
            """CREATE TABLE IF NOT EXISTS `tea_moments` (`teaId` TEXT NOT NULL, `momentRefId` TEXT NOT NULL, PRIMARY KEY(`teaId`, `momentRefId`), FOREIGN KEY(`teaId`) REFERENCES `teas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`momentRefId`) REFERENCES `reference_values`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
            """CREATE TABLE IF NOT EXISTS `tea_badges` (`teaId` TEXT NOT NULL, `badgeRefId` TEXT NOT NULL, PRIMARY KEY(`teaId`, `badgeRefId`), FOREIGN KEY(`teaId`) REFERENCES `teas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`badgeRefId`) REFERENCES `reference_values`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
            """CREATE TABLE IF NOT EXISTS `tastings` (`id` TEXT NOT NULL, `teaId` TEXT NOT NULL, `occurredAtEpochMillis` INTEGER NOT NULL, `recordedAtEpochMillis` INTEGER NOT NULL, `consumedMilliGrams` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`teaId`) REFERENCES `teas`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""",
            """CREATE TABLE IF NOT EXISTS `stock_movements` (`id` TEXT NOT NULL, `teaId` TEXT NOT NULL, `kind` TEXT NOT NULL, `deltaMilliGrams` INTEGER, `stockAfterMilliGrams` INTEGER NOT NULL, `occurredAtEpochMillis` INTEGER NOT NULL, `recordedAtEpochMillis` INTEGER NOT NULL, `tastingId` TEXT, `note` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`teaId`) REFERENCES `teas`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`tastingId`, `teaId`) REFERENCES `tastings`(`id`, `teaId`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            """CREATE TABLE IF NOT EXISTS `tea_photos` (`id` TEXT NOT NULL, `teaId` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`teaId`) REFERENCES `teas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        ).forEach(db::execSQL)

        listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_teas_legacyId` ON `teas` (`legacyId`)",
            "CREATE INDEX IF NOT EXISTS `index_teas_categoryRefId` ON `teas` (`categoryRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_teas_statusRefId` ON `teas` (`statusRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_teas_containerRefId` ON `teas` (`containerRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_teas_name` ON `teas` (`name`)",
            "CREATE INDEX IF NOT EXISTS `index_tea_countries_countryRefId` ON `tea_countries` (`countryRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_tea_regions_teaId_countryRefId` ON `tea_regions` (`teaId`, `countryRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_tea_regions_regionRefId_countryRefId` ON `tea_regions` (`regionRefId`, `countryRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_tea_aromas_aromaRefId` ON `tea_aromas` (`aromaRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_tea_aromatic_profiles_profileRefId` ON `tea_aromatic_profiles` (`profileRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_tea_moments_momentRefId` ON `tea_moments` (`momentRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_tea_badges_badgeRefId` ON `tea_badges` (`badgeRefId`)",
            "CREATE INDEX IF NOT EXISTS `index_tastings_teaId_occurredAtEpochMillis` ON `tastings` (`teaId`, `occurredAtEpochMillis`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tastings_id_teaId` ON `tastings` (`id`, `teaId`)",
            "CREATE INDEX IF NOT EXISTS `index_stock_movements_teaId_occurredAtEpochMillis` ON `stock_movements` (`teaId`, `occurredAtEpochMillis`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_stock_movements_tastingId_teaId` ON `stock_movements` (`tastingId`, `teaId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tea_photos_teaId_position` ON `tea_photos` (`teaId`, `position`)",
        ).forEach(db::execSQL)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `imported_datasets` (
                `datasetId` TEXT NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `contentSha256` TEXT NOT NULL,
                `sourceCreatedAtEpochMillis` INTEGER NOT NULL,
                `importedAtEpochMillis` INTEGER NOT NULL,
                `referenceCount` INTEGER NOT NULL,
                `teaCount` INTEGER NOT NULL,
                `tastingCount` INTEGER NOT NULL,
                `stockMovementCount` INTEGER NOT NULL,
                `gramsPerCupMilli` INTEGER NOT NULL,
                `lowStockThresholdMilli` INTEGER NOT NULL,
                PRIMARY KEY(`datasetId`)
            )""",
        )
    }
}
