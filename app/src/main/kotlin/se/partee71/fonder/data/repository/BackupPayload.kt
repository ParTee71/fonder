package se.partee71.fonder.data.repository

import kotlinx.serialization.Serializable
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.Transaction

/**
 * Filformatet för en säkerhetskopia (SET-6, issue #82) — hela backup-kontraktet (NFR-1) i en
 * serialiserbar form.
 *
 * Byggd på **domänmodellerna**, inte Room-entiteterna, med flit: entiteterna följer schemat och
 * ändras av migreringar, och filformatet ska inte tyst ändra sig med dem. Ett fält som byter
 * kolumnnamn i Room ska kräva ett medvetet beslut här, inte glida med.
 *
 * Innehåller **bara genuin användardata**. `fund_prices`, `fx_rates` och `fund_metadata` ligger
 * utanför: de är härledd cache som hämtas om från källan (se `FundMetadataEntity`s KDoc), och
 * skulle mångdubbla filen utan att skydda något som inte går att återskapa. Samma gräns som
 * [se.partee71.fonder.data.datastore.PreferencesRepository.lastPriceSyncEpochMillis] och
 * `fundFilterVocabulary`, som medvetet inte är med.
 *
 * @param formatVersion filformatets version, [FORMAT_VERSION] när den skrevs. En fil med *högre*
 *   version avvisas av [BackupSerializer.decode] i stället för att läsas in med de fält den här
 *   versionen råkar känna igen — en delvis inläst säkerhetskopia är värre än ingen.
 * @param themeMode användarens temaval — ett val, inte cache, och därför med. Till skillnad från
 *   resten är det billigt att sätta om för hand, men gränsen går vid "härledd ur en källa".
 */
@Serializable
data class BackupPayload(
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAtEpochMillis: Long,
    val funds: List<Fund> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val suggestionRecords: List<SuggestionRecord> = emptyList(),
    val riskProfile: RiskProfile? = null,
    val accountType: AccountType? = null,
    val themeMode: ThemeMode = ThemeMode.AUTO,
) {
    companion object {
        /**
         * Nuvarande filformatversion. **Höj den bara** när ett fält tas bort eller byter
         * betydelse — ett *tillagt* fält med defaultvärde läses av äldre appversioner utan
         * problem (okända nycklar ignoreras, se [BackupSerializer]).
         */
        const val FORMAT_VERSION = 1
    }
}

/** Vad en återställning faktiskt skrev — visas som kvittens, så "klart" inte betyder "tomt". */
data class RestoreSummary(
    val funds: Int,
    val transactions: Int,
    val suggestionRecords: Int,
)
