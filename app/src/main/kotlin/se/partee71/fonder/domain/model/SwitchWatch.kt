package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * Ett **pågående byte** (ANA-12, issue #114) — perioden mellan att en fond sålts och att
 * likviden köpt nästa. Ett fondbyte är inte en handling utan två, med dagar emellan: säljen går
 * ut, pengarna landar T+1–T+3, och först då fattas köpbeslutet. Under tiden bevakas ett par
 * alternativa köpfonder ([candidates]) så beslutet kan fattas på hur de faktiskt rört sig sedan
 * säljdagen, inte på hur de såg ut den dag rådet gavs.
 *
 * Genuin användardata, inte härledd cache: säljdagen, beloppet och den **uppsättning
 * alternativ användaren valde att titta på** går inte att räkna fram i efterhand ur NAV-
 * historiken — lika lite som [SuggestionRecord]s förslagstidpunkt. Ingår därför i
 * backup-kontraktet (NFR-1), se [se.partee71.fonder.data.repository.BackupPayload].
 *
 * Bevakningen **utför aldrig ett byte** — precis som bytesplanen (HEM-8) och kvitteringen
 * (SET-5) registrerar den bara vad användaren gjort. Appen rör aldrig innehav.
 *
 * @param sellIsin fonden som sålts. ISIN, inte `fundId`: kandidaterna identifieras med ISIN
 *   (de är fonder appen aldrig ägt) och en bevakning ska kunna jämföra båda sidor på samma
 *   nyckel.
 * @param soldAtEpochDay säljdagen — nollpunkten all utveckling mäts från. Sätts av användaren
 *   (säljraden i Sålda fonder, SLD-5) eller till dagen kvitteringen gjordes (HEM-8/ANA-10).
 * @param proceedsKr beloppet bytet avser, från [SuggestionRecord.switchValueKr] eller
 *   säljtransaktionens likvid. Null när det inte är känt — då visas utvecklingen i procent utan
 *   kronbelopp, aldrig med ett påhittat (samma princip som ett förslag utan belopp).
 * @param targetLevel risknivån bytet ska fylla (bytesplanens `toLevel`, HEM-8) — styr vilka
 *   kandidater som föreslås automatiskt. Null när bevakningen startats fristående för en fond
 *   utan känd nivå; då erbjuds bara manuellt tillägg, aldrig en gissad nivå (ANA-4-principen).
 * @param sourceRecordId raden i `suggestion_records` som bevakningen startades ur (HEM-8), null
 *   för en fristående bevakning. Nyckeln [SwitchWatchCloseReason.KOPT] skriver `followed` mot
 *   när det köpta faktiskt var det föreslagna.
 * @param closedAtEpochDay null så länge bevakningen är öppen. En stängd bevakning **raderas
 *   aldrig** — den är användardata och bär vad som faktiskt köptes.
 * @param boughtIsin fonden användaren kvitterade som köpt, null om bevakningen stängdes utan
 *   köp (avbruten eller utgången).
 */
@Serializable
data class SwitchWatch(
    val id: Long = 0,
    val sellIsin: String,
    val sellFundName: String,
    val soldAtEpochDay: Long,
    val proceedsKr: Double? = null,
    val targetLevel: Int? = null,
    val sourceRecordId: Long? = null,
    val closedAtEpochDay: Long? = null,
    val boughtIsin: String? = null,
    val closeReason: SwitchWatchCloseReason? = null,
    val candidates: List<SwitchWatchCandidate> = emptyList(),
) {
    /** Öppen = ingen kvittering och ingen utgång skriven. Se [SwitchWatchCalc] för färskhetsgränsen. */
    val isOpen: Boolean get() = closedAtEpochDay == null
}

/**
 * En bevakad köpkandidat i ett pågående byte (ANA-13, issue #114).
 *
 * @param name fondens namn **som det visades när kandidaten lades till**. Sparas till skillnad
 *   från [SuggestionRecord], som slår upp namnen ur metadatacachen: en manuellt tillagd kandidat
 *   kan sakna metadatarad helt, och en bevakning utan namn på hälften av raderna vore obrukbar.
 * @param navAtStart kandidatens NAV på [SwitchWatch.soldAtEpochDay] — nollpunkten utvecklingen
 *   mäts från. Kan **inte** återskapas i efterhand: kurscachen är sanningen om bevakade fonder
 *   och en kandidat ligger inte där (ANA-11), så värdet ankras en gång när historiken hämtas och
 *   sparas sedan. Null tills första hämtningen lyckats — då visas raden som ej utvärderad,
 *   aldrig som 0 (ANA-4-principen).
 * @param navAtStartEpochDay dagen [navAtStart] faktiskt avser. Normalt säljdagen, men en
 *   bevakning startad i efterhand ur en gammal säljrad kan sakna kurs just den dagen — då ankras
 *   den på **första kända dagen efter** säljdagen och raden märks som delvis (samma princip som
 *   ANA-11:s beskurna jämförelse). Utan dagen gick "delvis" inte att avgöra vid visning.
 * @param source om kandidaten föreslogs av appen ([SwitchWatchCandidateSource.AUTO], samma pool
 *   och rangordning som bytesplanens köpkandidater) eller lades till för hand. Skillnaden är
 *   inte kosmetisk: en automatisk kandidat får bytas ut vid en omfyllning, en handplockad aldrig.
 * @param position ordningen kandidaten visas i — de automatiska i källans rangordning, manuellt
 *   tillagda sist i den ordning de lades till.
 */
@Serializable
data class SwitchWatchCandidate(
    val id: Long = 0,
    val watchId: Long = 0,
    val isin: String,
    val name: String,
    val navAtStart: Double? = null,
    val navAtStartEpochDay: Long? = null,
    val source: SwitchWatchCandidateSource = SwitchWatchCandidateSource.AUTO,
    val position: Int = 0,
)

/** Varifrån en bevakad kandidat kom — se [SwitchWatchCandidate.source]. */
@Serializable
enum class SwitchWatchCandidateSource {
    AUTO,
    MANUELL,
}

/**
 * Varför en bevakning stängdes. [KOPT] är det enda avslut som bär ett [SwitchWatch.boughtIsin];
 * [UTGANGEN] sätts av bakgrundskörningen när bevakningen legat orörd längre än
 * [se.partee71.fonder.domain.usecase.SwitchWatchCalc.WATCH_TTL_DAYS] — ett gammalt läge är fel
 * på ett sätt gammal cache inte är (samma princip som `SwitchPlanCalc.PLAN_TTL_DAYS`).
 */
@Serializable
enum class SwitchWatchCloseReason {
    KOPT,
    AVBRUTEN,
    UTGANGEN,
}
