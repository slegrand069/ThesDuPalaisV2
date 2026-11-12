package fr.thesdupalais.data.operation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOperationLifecycleTest {
    @Test fun preparedOperationFollowsOnlyTheDeclaredForwardTransitions() {
        val states = listOf(
            BackupOperationState.PREPARING,
            BackupOperationState.PREPARED,
            BackupOperationState.ROOM_APPLYING,
            BackupOperationState.ROOM_COMMITTED,
            BackupOperationState.FINALIZING,
            BackupOperationState.COMPLETE,
        )
        states.zipWithNext().forEach { (from, to) ->
            assertTrue("$from -> $to", BackupOperationStateMachine.canTransition(from, to))
        }
        assertFalse(BackupOperationStateMachine.canTransition(BackupOperationState.COMPLETE, BackupOperationState.PREPARED))
        assertFalse(BackupOperationStateMachine.canTransition(BackupOperationState.ROOM_COMMITTED, BackupOperationState.ABORTED))
        assertThrows(IllegalArgumentException::class.java) {
            BackupOperationStateMachine.requireTransition(BackupOperationState.COMPLETE, BackupOperationState.PREPARING)
        }
    }

    @Test fun recoveryInspectorDistinguishesPreCommitAndCommittedRoomStates() {
        assertEquals(RecoveryFinding.NONE, BackupOperationRecoveryInspector.inspect(emptySet(), null).finding)
        assertEquals(RecoveryFinding.UNCOMMITTED_OPERATION_NEEDS_DECISION, BackupOperationRecoveryInspector.inspect(setOf("op"), null).finding)
        assertEquals(RecoveryFinding.ROOM_COMMITTED_NEEDS_FORWARD_FINALIZATION, BackupOperationRecoveryInspector.inspect(setOf("op"), "op", roomMarkerState = "ROOM_COMMITTED").finding)
        assertEquals(
            RecoveryFinding.JOURNAL_ROOM_MARKER_MISMATCH,
            BackupOperationRecoveryInspector.inspect(setOf("op"), null, journalStates = mapOf("op" to BackupOperationState.ROOM_COMMITTED)).finding,
        )
        assertEquals(RecoveryFinding.JOURNAL_ROOM_MARKER_MISMATCH, BackupOperationRecoveryInspector.inspect(emptySet(), "op").finding)
        assertTrue(BackupOperationRecoveryInspector.inspect(emptySet(), null, journalReadFailed = true).blocksWrites)
    }

    @Test fun writeCoordinatorBlocksOnPendingOperationAndAllowsNestedWrites() = runBlocking {
        var pending = false
        val coordinator = BackupOperationWriteCoordinator {
            if (pending) RecoveryInspection(RecoveryFinding.UNCOMMITTED_OPERATION_NEEDS_DECISION, "op")
            else RecoveryInspection(RecoveryFinding.NONE)
        }
        val allowed = coordinator.withWritePermit {
            coordinator.withWritePermit { "written once under the same permit" }
        }
        assertEquals("written once under the same permit", allowed)
        pending = true
        val error = runCatching { coordinator.withWritePermit { "must not write" } }.exceptionOrNull()
        assertTrue(error is BackupOperationPendingException)
    }

    @Test fun recoveryPermitIsBoundToOneRecognizedPendingOperation() = runBlocking {
        val coordinator = BackupOperationWriteCoordinator {
            RecoveryInspection(RecoveryFinding.ROOM_COMMITTED_NEEDS_FORWARD_FINALIZATION, "op")
        }
        assertEquals("recover", coordinator.withRecoveryPermit("op") { "recover" })
        val wrongOperation = runCatching { coordinator.withRecoveryPermit("other") { "must not recover" } }.exceptionOrNull()
        assertTrue(wrongOperation is BackupOperationPendingException)
    }
}
