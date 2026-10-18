package se.partee71.fonder.data.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.repository.RoomSuggestionDismissalRepository
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind

/** Avstådda bytesförslag (HEM-14) mot riktig Room: en rad per fondpar och sort, och Ångra tar bort just den. */
@RunWith(AndroidJUnit4::class)
class SuggestionDismissalDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSuggestionDismissalRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = RoomSuggestionDismissalRepository(db.suggestionDismissalDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun avsta_igen_skriver_over_datumet_och_angra_tar_bort_bara_det_paret() = runTest {
        val day = LocalDate.of(2026, 10, 1)
        repository.dismiss("SE_A", "SE_B", SuggestionKind.RISK_PLAN, day)
        repository.dismiss("SE_A", "SE_B", SuggestionKind.RISK_PLAN, day.plusDays(3))
        repository.dismiss("SE_B", "SE_A", SuggestionKind.RISK_PLAN, day)

        assertEquals(
            listOf(
                SuggestionDismissal("SE_A", "SE_B", SuggestionKind.RISK_PLAN, day.plusDays(3).toEpochDay()),
                SuggestionDismissal("SE_B", "SE_A", SuggestionKind.RISK_PLAN, day.toEpochDay())
            ),
            repository.observeAll().first()
        )

        repository.undoDismissal("SE_A", "SE_B", SuggestionKind.RISK_PLAN)

        assertEquals(listOf("SE_B"), repository.observeAll().first().map { it.sellIsin })
    }
}
