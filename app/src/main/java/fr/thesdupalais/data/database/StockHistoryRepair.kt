package fr.thesdupalais.data.database

import java.util.UUID

/** Safe, narrowly-scoped repair rules for balances written before all stock edits were journaled. */
internal object StockHistoryRepair {
    data class Plan(val additions: List<StockMovementEntity>, val problem: String? = null)

    fun plan(
        teaId: UUID,
        stockMilliGrams: Long?,
        movements: List<StockMovementEntity>,
        now: Long,
        newId: () -> UUID = UUID::randomUUID,
    ): Plan {
        val ordered = movements.sortedWith(compareBy<StockMovementEntity> { it.occurredAtEpochMillis }
            .thenBy { it.recordedAtEpochMillis }.thenBy { it.id.toString() })
        if (stockMilliGrams == null) {
            return if (ordered.isEmpty()) Plan(emptyList()) else Plan(emptyList(), "Stock inconnu avec mouvements existants.")
        }
        if (stockMilliGrams < 0) return Plan(emptyList(), "Stock négatif.")
        if (ordered.isEmpty()) return Plan(listOf(
            StockMovementEntity(newId(), teaId, "INVENTORY", null, stockMilliGrams, now, now, null,
                "Régularisation de l’état de stock préexistant"),
        ))

        val baselines = ordered.filter { it.kind == "INVENTORY" }
        if (baselines.size != 1 || ordered.first().kind != "INVENTORY") {
            return Plan(emptyList(), "Historique sans solde INVENTORY de référence unique; réparation automatique non sûre.")
        }
        var balance = baselines.single().stockAfterMilliGrams
        if (baselines.single().deltaMilliGrams != null || baselines.single().tastingId != null || balance < 0) {
            return Plan(emptyList(), "Mouvement INVENTORY de référence incohérent.")
        }
        ordered.drop(1).forEach { movement ->
            val delta = movement.deltaMilliGrams ?: return Plan(emptyList(), "Delta de mouvement absent.")
            if (movement.kind !in setOf("CONSUMPTION", "RESTOCK", "CORRECTION") || delta == 0L) {
                return Plan(emptyList(), "Mouvement de stock incohérent.")
            }
            if ((movement.kind == "CONSUMPTION" && delta >= 0L) ||
                (movement.kind == "RESTOCK" && (delta <= 0L || movement.tastingId != null)) ||
                (movement.kind == "CORRECTION" && movement.tastingId != null)) {
                return Plan(emptyList(), "Delta ou association de mouvement incohérent.")
            }
            val next = try { Math.addExact(balance, delta) } catch (_: ArithmeticException) {
                return Plan(emptyList(), "Débordement dans l’historique de stock.")
            }
            if (next < 0 || next != movement.stockAfterMilliGrams) {
                return Plan(emptyList(), "Soldes historiques incohérents.")
            }
            balance = next
        }
        if (balance == stockMilliGrams) return Plan(emptyList())
        val delta = try { Math.subtractExact(stockMilliGrams, balance) } catch (_: ArithmeticException) {
            return Plan(emptyList(), "Écart de stock hors capacité numérique.")
        }
        val time = try { maxOf(now, Math.addExact(ordered.last().maxEventTime(), 1L)) } catch (_: ArithmeticException) {
            return Plan(emptyList(), "Date de mouvement hors capacité numérique.")
        }
        return Plan(listOf(
            StockMovementEntity(newId(), teaId, "CORRECTION", delta, stockMilliGrams, time, time, null,
                "Régularisation du stock préexistant"),
        ))
    }

    private fun StockMovementEntity.maxEventTime(): Long = maxOf(occurredAtEpochMillis, recordedAtEpochMillis)
}
