package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.room.daos.SuggestionDismissalDao
import se.partee71.fonder.data.room.entities.SuggestionDismissalEntity
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Avstådda bytesförslag (HEM-14) — se [SuggestionDismissal]. */
interface SuggestionDismissalRepository {

    /** Alla avståenden, även utgångna — giltigheten avgörs av [se.partee71.fonder.domain.usecase.SuggestionDismissals]. */
    fun observeAll(): Flow<List<SuggestionDismissal>>

    /** Avstår från paret från och med [today]; ett tidigare avstående för samma par skrivs över. */
    suspend fun dismiss(sellIsin: String, buyIsin: String, kind: SuggestionKind, today: LocalDate = LocalDate.now())

    /** Ångrar ett avstående — förslaget visas igen om planen ger det. */
    suspend fun undoDismissal(sellIsin: String, buyIsin: String, kind: SuggestionKind)
}

@Singleton
class RoomSuggestionDismissalRepository @Inject constructor(
    private val dao: SuggestionDismissalDao,
) : SuggestionDismissalRepository {

    override fun observeAll(): Flow<List<SuggestionDismissal>> =
        dao.observeAll().map { list -> list.map(SuggestionDismissalEntity::toDomain) }

    override suspend fun dismiss(sellIsin: String, buyIsin: String, kind: SuggestionKind, today: LocalDate) {
        dao.upsert(
            SuggestionDismissalEntity.fromDomain(
                SuggestionDismissal(sellIsin = sellIsin, buyIsin = buyIsin, kind = kind, dismissedAtEpochDay = today.toEpochDay()),
            ),
        )
    }

    override suspend fun undoDismissal(sellIsin: String, buyIsin: String, kind: SuggestionKind) {
        dao.delete(sellIsin, buyIsin, kind.name)
    }
}
