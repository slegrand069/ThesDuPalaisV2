package fr.thesdupalais.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.thesdupalais.domain.references.StandardReferenceSeeds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandardReferenceSeederTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun seedingIsRepeatableAndPreservesStandardColorIdentities() = runBlocking {
        val seeder = StandardReferenceSeeder(database)
        seeder.seed()
        seeder.seed()

        val actual = database.referenceDao().observeByKind("COLOR").first()
        assertEquals(StandardReferenceSeeds.colors.size, actual.size)
        StandardReferenceSeeds.colors.forEach { expected ->
            val saved = actual.singleOrNull { it.semanticKey == expected.semanticKey }
            assertNotNull("Missing standard color ${expected.semanticKey}", saved)
            assertEquals(expected.id, saved?.id)
        }
    }
}
