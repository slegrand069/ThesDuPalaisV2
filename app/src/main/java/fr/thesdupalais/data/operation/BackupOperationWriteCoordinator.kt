package fr.thesdupalais.data.operation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Serializes app-level writes and refuses them while any operation requires recovery. */
class BackupOperationWriteCoordinator(
    private val inspect: suspend () -> RecoveryInspection = { RecoveryInspection(RecoveryFinding.NONE) },
) {
    private val mutex = Mutex()

    suspend fun <T> withWritePermit(block: suspend () -> T): T = withPermit(block)

    /** Reserved for future operation preparation/application, which must exclude ordinary writers. */
    suspend fun <T> withOperationPermit(block: suspend () -> T): T = withPermit(block)

    /** Acquires the exclusive permit for a prepared operation without opening the ordinary write path. */
    suspend fun <T> withPreparedOperationPermit(operationId: String, block: suspend () -> T): T {
        val current = currentCoroutineContext()[PermitContext]
        if (current?.owner === this) return block()
        return mutex.withLock {
            val status = try { inspect() } catch (_: Exception) {
                RecoveryInspection(RecoveryFinding.CORRUPT_OR_UNREADABLE_JOURNAL, detail = "État de récupération inaccessible; écritures suspendues.")
            }
            if (status.finding !in setOf(RecoveryFinding.UNCOMMITTED_OPERATION_NEEDS_DECISION, RecoveryFinding.ROOM_COMMITTED_NEEDS_FORWARD_FINALIZATION) || status.operationId != operationId) {
                throw BackupOperationPendingException(status)
            }
            withContext(PermitContext(this)) { block() }
        }
    }

    /** Allows only the explicitly named unfinished operation to enter its future recovery path. */
    suspend fun <T> withRecoveryPermit(operationId: String, block: suspend () -> T): T {
        val current = currentCoroutineContext()[PermitContext]
        if (current?.owner === this) return block()
        return mutex.withLock {
            val status = try { inspect() } catch (_: Exception) {
                RecoveryInspection(RecoveryFinding.CORRUPT_OR_UNREADABLE_JOURNAL, detail = "État de récupération inaccessible; écritures suspendues.")
            }
            if (status.finding !in setOf(RecoveryFinding.UNCOMMITTED_OPERATION_NEEDS_DECISION, RecoveryFinding.ROOM_COMMITTED_NEEDS_FORWARD_FINALIZATION) || status.operationId != operationId) {
                throw BackupOperationPendingException(status)
            }
            withContext(PermitContext(this)) { block() }
        }
    }

    private suspend fun <T> withPermit(block: suspend () -> T): T {
        val current = currentCoroutineContext()[PermitContext]
        if (current?.owner === this) return block()
        return mutex.withLock {
            val status = try { inspect() } catch (_: Exception) {
                RecoveryInspection(RecoveryFinding.CORRUPT_OR_UNREADABLE_JOURNAL, detail = "État de récupération inaccessible; écritures suspendues.")
            }
            if (status.blocksWrites) throw BackupOperationPendingException(status)
            withContext(PermitContext(this)) { block() }
        }
    }

    private class PermitContext(val owner: BackupOperationWriteCoordinator) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<PermitContext>
    }
}

class BackupOperationPendingException(val inspection: RecoveryInspection) :
    IllegalStateException(inspection.detail ?: "Une opération de restauration doit être contrôlée avant toute écriture.")
