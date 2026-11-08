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
    fun seedingIsRepeatableAndPreservesStandardCategoryIdentities() = runBlocking {
        val seeder = StandardReferenceSeeder(database)
        seeder.seed()
        seeder.seed()

        val actual = database.referenceDao().observeByKind("CATEGORY").first()
        assertEquals(StandardReferenceSeeds.categories.size, actual.size)
        StandardReferenceSeeds.categories.forEach { expected ->
            val saved = actual.singleOrNull { it.semanticKey == expected.semanticKey }
            assertNotNull("Missing standard category ${expected.semanticKey}", saved)
            assertEquals(expected.id, saved?.id)
        }
        assertEquals(6, database.referenceDao().observeByKind("STATUS").first().size)
        assertEquals(5, database.referenceDao().observeByKind("CONTAINER").first().size)
        assertEquals(7, database.referenceDao().observeByKind("MOMENT").first().size)
    }
}
