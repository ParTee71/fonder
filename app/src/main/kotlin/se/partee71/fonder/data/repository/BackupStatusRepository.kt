package se.partee71.fonder.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.domain.model.BackupRecord
import se.partee71.fonder.domain.model.BackupTarget
import se.partee71.fonder.domain.usecase.BackupContentState
import se.partee71.fonder.domain.usecase.BackupReminder
import se.partee71.fonder.domain.usecase.BackupReminderCalc
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Har något ändrats sedan senaste säkerhetskopian (HEM-12, SET-7)? Säkerhetskopieringen är
 * manuell, så det är den här frågan som ersätter den dygnsvisa automatiken.
 *
 * Svaret räknas fram ur **innehållet**, inte ur skrivvägarna: varje lyckad kopia eller
 * återställning sparar ett [BackupFingerprint], och [reminder] jämför dagens innehåll med det.
 * Ingen skrivväg behöver komma ihåg att flagga en ändring, och en ändring som tas tillbaka
 * (en transaktion som läggs till och raderas) ger ingen påminnelse.
 */
@Singleton
class BackupStatusRepository @Inject constructor(
    private val backupRepository: BackupRepository,
    private val preferences: PreferencesRepository,
) {

    /** Senaste kopian eller återställningen på den här enheten, null om ingen gjorts. */
    val lastBackup: Flow<BackupRecord?> = preferences.lastBackup

    /**
     * Påminnelsen, null när ingen behövs. Räknas om först när kontraktets tabeller eller
     * inställningar signalerat en ändring och det blivit stilla en stund ([DEBOUNCE_MILLIS]) —
     * en import med hundra rader ger en omräkning, inte hundra. Går läsningen inte att göra
     * visas ingen påminnelse hellre än en felaktig.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val reminder: Flow<BackupReminder?> =
        backupRepository.observeChanges()
            .debounce(DEBOUNCE_MILLIS)
            .mapLatest { runCatching { contentState(backupRepository.snapshot()) }.getOrNull() }
            .combine(preferences.lastBackup) { current, last ->
                current?.let { BackupReminderCalc.reminder(it, last) }
            }
            .distinctUntilChanged()

    /**
     * Antecknar att [json] — **exakt** den sträng som exporterades, laddades upp eller
     * återställdes — nu finns i en säkerhetskopia på [target]. Fingeravtryck och antal räknas ur
     * just den, inte ur en ny läsning av databasen: en ändring mellan exporten och anteckningen
     * hade annars markerats som säkerhetskopierad utan att finnas i kopian. Efter en
     * återställning är databasen lika med filen (det fingeravtrycket inte räknar, som id:n på
     * automatiska kandidater, får skilja sig).
     *
     * Går strängen inte att läsa som en säkerhetskopia antecknas ingenting.
     */
    suspend fun recordBackup(json: String, target: BackupTarget, nowEpochMillis: Long = System.currentTimeMillis()) {
        val payload = BackupSerializer.decode(json).getOrElse { return }
        val state = contentState(payload)
        preferences.setLastBackup(
            BackupRecord(
                epochMillis = nowEpochMillis,
                target = target,
                fingerprint = state.fingerprint,
                transactionCount = state.transactionCount,
            ),
        )
    }

    private fun contentState(payload: BackupPayload) = BackupContentState(
        fingerprint = BackupFingerprint.of(payload),
        transactionCount = payload.transactions.size,
        hasData = payload.funds.isNotEmpty() || payload.transactions.isNotEmpty(),
    )

    companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}
