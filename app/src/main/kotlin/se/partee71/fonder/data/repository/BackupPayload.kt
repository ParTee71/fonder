package se.partee71.fonder.data.repository

import kotlinx.serialization.Serializable
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
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
    /**
     * Pågående och avslutade byten med sina bevakade alternativ (ANA-12, issue #114). Genuin
     * användardata av samma skäl som [suggestionRecords]: säljdagen, beloppet, uppsättningen
     * bevakade alternativ och deras ankrade nollpunkt går inte att räkna fram ur NAV-historiken
     * i efterhand — kandidaterna ligger per definition inte i kurscachen (ANA-11).
     */
    val switchWatches: List<SwitchWatch> = emptyList(),
    val riskProfile: RiskProfile? = null,
    val accountType: AccountType? = null,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /**
     * Referensfonden användaren själv valt för Hems indexjämförelse (HEM-10, issue #102), null
     * om appen väljer. Ett **val**, inte något härlett — därför med, till skillnad från den
     * automatiskt valda blandningen (`PreferencesRepository.benchmark`), som är cache i samma
     * kategori som `fundFilterVocabulary`.
     */
    val chosenBenchmarkIsin: String? = null,
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
    val switchWatches: Int = 0,
)
