package fr.thesdupalais.presentation.startup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.thesdupalais.FirstRunOperations
import fr.thesdupalais.data.importer.CollectionImportException
import fr.thesdupalais.data.importer.CollectionImportResult
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstRunViewModelTest {
    @Test fun startWithoutFileInitializesAndPersistsChoice() {
        val operations = FakeOperations()
        val vm = create(operations)
        await { vm.state.value == FirstRunState.Welcome }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.startWithoutFile() }
        await { vm.state.value is FirstRunState.Completed }
        assertTrue(operations.initialized)
        assertEquals(0, operations.importCalls)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.openCatalogue() }
        assertEquals(FirstRunState.Catalogue, vm.state.value)
        val relaunched = create(operations)
        await { relaunched.state.value == FirstRunState.Catalogue }
    }

    @Test fun validImportMarksSetupOnlyAfterImporterSucceeds() {
        val operations = FakeOperations()
        val vm = create(operations, "valid fixture")
        await { vm.state.value == FirstRunState.Welcome }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.requestFile()
            vm.onDocumentSelected(Uri.parse("content://test/collection.json"))
        }
        await { vm.state.value is FirstRunState.Completed }
        assertEquals(1, operations.importCalls)
        assertTrue(operations.initialized)
    }

    @Test fun importFailureLeavesSetupUninitializedAndAllowsRetryOrEmptyStart() {
        val operations = FakeOperations().apply { importError = CollectionImportException(
            CollectionImportException.Code.UNSUPPORTED_VERSION, "$.schemaVersion", "Version JSON incompatible.") }
        val vm = create(operations, "bad fixture")
        await { vm.state.value == FirstRunState.Welcome }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.requestFile()
            vm.onDocumentSelected(Uri.parse("content://test/invalid.json"))
        }
        await { vm.state.value is FirstRunState.Error }
        assertFalse(operations.initialized)
        assertEquals(0, operations.markCalls)
        val relaunched = create(operations)
        await { relaunched.state.value == FirstRunState.Welcome }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.startWithoutFile() }
        await { vm.state.value is FirstRunState.Completed }
        assertTrue(operations.initialized)
    }

    @Test fun pickerCancellationReturnsToWelcomeWithoutChangingPersistentState() {
        val operations = FakeOperations()
        val vm = create(operations)
        await { vm.state.value == FirstRunState.Welcome }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.requestFile()
            vm.onPickerCancelled()
        }
        assertEquals(FirstRunState.Welcome, vm.state.value)
        assertFalse(operations.initialized)
    }

    private fun create(operations: FakeOperations, json: String = "fixture"): FirstRunViewModel {
        lateinit var vm: FirstRunViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = FirstRunViewModel(operations) { json }
        }
        return vm
    }

    private fun await(condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(100) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for first-run state")
    }

    private class FakeOperations : FirstRunOperations {
        var initialized = false
        var importCalls = 0
        var markCalls = 0
        var importError: Exception? = null
        override suspend fun initialize() = Unit
        override suspend fun isInstallationInitialized() = initialized
        override suspend fun markInstallationInitialized() { markCalls++; initialized = true }
        override suspend fun importCollection(json: String): CollectionImportResult {
            importCalls++
            importError?.let { throw it }
            return CollectionImportResult.Imported(UUID.fromString("90000000-0000-4000-8000-000000000001"), 1, 1)
        }
    }
}
