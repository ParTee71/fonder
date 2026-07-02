package se.partee71.fonder.data.repository

import se.partee71.fonder.domain.model.FundPrice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kontrakt för fondkurser (NAV).
 *
 * Implementationen beror på spike-issue #2 (källa hos Handelsbanken utan inloggning).
 * Tills beslutet är taget finns en stub som returnerar tomt — appen ska bygga och köra
 * utan kursdata.
 */
interface FundPriceRepository {
    /** Senaste kända kurs för en fond, eller null om okänd. */
    suspend fun latestPrice(isin: String): FundPrice?

    /** Kurshistorik för en fond inom ett epoch-day-intervall (inklusive). */
    suspend fun priceHistory(isin: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice>
}

@Singleton
class StubFundPriceRepository @Inject constructor() : FundPriceRepository {
    // TODO(#2): ersätt med riktig hämtning när kurskällan är fastställd.
    override suspend fun latestPrice(isin: String): FundPrice? = null
    override suspend fun priceHistory(isin: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
}
