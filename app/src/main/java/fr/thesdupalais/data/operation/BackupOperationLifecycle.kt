package fr.thesdupalais.data.operation

/** Persisted lifecycle for a future restore/merge apply engine. */
enum class BackupOperationState {
    PREPARING,
    PREPARED,
    ROOM_APPLYING,
    ROOM_COMMITTED,
    FINALIZING,
    COMPLETE,
    ABORTED,
    RECOVERY_REQUIRED,
}

object BackupOperationStateMachine {
    private val transitions = mapOf(
        BackupOperationState.PREPARING to setOf(BackupOperationState.PREPARED, BackupOperationState.ABORTED, BackupOperationState.RECOVERY_REQUIRED),
        BackupOperationState.PREPARED to setOf(BackupOperationState.ROOM_APPLYING, BackupOperationState.ABORTED, BackupOperationState.RECOVERY_REQUIRED),
        BackupOperationState.ROOM_APPLYING to setOf(BackupOperationState.ROOM_COMMITTED, BackupOperationState.ABORTED, BackupOperationState.RECOVERY_REQUIRED),
        BackupOperationState.ROOM_COMMITTED to setOf(BackupOperationState.FINALIZING, BackupOperationState.RECOVERY_REQUIRED),
        BackupOperationState.FINALIZING to setOf(BackupOperationState.COMPLETE, BackupOperationState.RECOVERY_REQUIRED),
        BackupOperationState.RECOVERY_REQUIRED to emptySet(),
        BackupOperationState.COMPLETE to emptySet(),
        BackupOperationState.ABORTED to emptySet(),
    )

    fun canTransition(from: BackupOperationState, to: BackupOperationState): Boolean = to in transitions.getValue(from)

    fun requireTransition(from: BackupOperationState, to: BackupOperationState) {
        require(canTransition(from, to)) { "Transition d’opération interdite : $from → $to." }
    }

    fun isTerminal(state: BackupOperationState): Boolean = state in setOf(
        BackupOperationState.COMPLETE,
        BackupOperationState.ABORTED,
        BackupOperationState.RECOVERY_REQUIRED,
    )
}

enum class RecoveryFinding {
    NONE,
    UNCOMMITTED_OPERATION_NEEDS_DECISION,
    ROOM_COMMITTED_NEEDS_FORWARD_FINALIZATION,
    JOURNAL_ROOM_MARKER_MISMATCH,
    CORRUPT_OR_UNREADABLE_JOURNAL,
}

data class RecoveryInspection(val finding: RecoveryFinding, val operationId: String? = null, val detail: String? = null) {
    val blocksWrites: Boolean get() = finding != RecoveryFinding.NONE
}

/** Read-only classification. It intentionally does not resume, roll back, or clean up anything. */
object BackupOperationRecoveryInspector {
    fun inspect(
        journalOperationIds: Set<String>,
        roomOperationId: String?,
        journalReadFailed: Boolean = false,
        journalStates: Map<String, BackupOperationState> = emptyMap(),
        roomMarkerState: String? = null,
    ): RecoveryInspection {
        if (journalReadFailed) return RecoveryInspection(RecoveryFinding.CORRUPT_OR_UNREADABLE_JOURNAL, detail = "Journal illisible : écritures suspendues.")
        if (roomOperationId != null) {
            val roomState = roomMarkerState?.let { runCatching { BackupOperationState.valueOf(it) }.getOrNull() }
            val journalState = journalStates[roomOperationId]
            val roomMarkerValid = roomState in setOf(BackupOperationState.ROOM_COMMITTED, BackupOperationState.FINALIZING)
            val roomCommitPossible = journalState == null || journalState in setOf(
                BackupOperationState.ROOM_APPLYING,
                BackupOperationState.ROOM_COMMITTED,
                BackupOperationState.FINALIZING,
                BackupOperationState.RECOVERY_REQUIRED,
            )
            return if (roomOperationId in journalOperationIds && roomMarkerValid && roomCommitPossible) {
                RecoveryInspection(RecoveryFinding.ROOM_COMMITTED_NEEDS_FORWARD_FINALIZATION, roomOperationId)
            } else {
                RecoveryInspection(RecoveryFinding.JOURNAL_ROOM_MARKER_MISMATCH, roomOperationId, "Marqueur Room sans journal/données durables correspondants.")
            }
        }
        val falselyCommitted = journalStates.entries.firstOrNull { (_, state) -> state in setOf(BackupOperationState.ROOM_COMMITTED, BackupOperationState.FINALIZING) }
        if (falselyCommitted != null) return RecoveryInspection(
            RecoveryFinding.JOURNAL_ROOM_MARKER_MISMATCH,
            falselyCommitted.key,
            "Le journal indique une validation Room, mais le marqueur transactionnel est absent.",
        )
        val ambiguousRecovery = journalStates.entries.firstOrNull { (_, state) -> state == BackupOperationState.RECOVERY_REQUIRED }
        if (ambiguousRecovery != null) return RecoveryInspection(
            RecoveryFinding.JOURNAL_ROOM_MARKER_MISMATCH,
            ambiguousRecovery.key,
            "L’opération est marquée pour récupération, mais son point de validation Room ne peut pas être établi.",
        )
        val pending = journalOperationIds.sorted().firstOrNull()
            ?: return RecoveryInspection(RecoveryFinding.NONE)
        return RecoveryInspection(RecoveryFinding.UNCOMMITTED_OPERATION_NEEDS_DECISION, pending, "Aucune transaction Room validée : décision de reprise manuelle requise.")
    }
}
