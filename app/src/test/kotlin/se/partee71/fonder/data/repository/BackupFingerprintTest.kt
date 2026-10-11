package se.partee71.fonder.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/** HEM-12: fingeravtrycket ändras när användarens data gör det — och bara då. */
class BackupFingerprintTest {

    private val fundA = Fund(fundId = "SHB1", name = "Fond A", isin = "SE0000000001")
    private val fundB = Fund(fundId = "SHB2", name = "Fond B", isin = "SE0000000002")
    private val tx1 = Transaction(id = 1, fundId = "SHB1", type = TransactionType.KOP, epochDay = 19_000, shares = 10.0, pricePerShare = 100.0)
    private val tx2 = Transaction(id = 2, fundId = "SHB2", type = TransactionType.KOP, epochDay = 19_010, shares = 5.0, pricePerShare = 200.0)
    private val record = SuggestionRecord(
        id = 7, suggestedAtEpochDay = 19_020, planIndex = 0, sellIsin = "SE0000000001", buyIsin = "SE0000000002",
        sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 200.0, batchEpochMillis = 1_000,
    )
    private val watch = SwitchWatch(
        id = 3, sellIsin = "SE0000000001", sellFundName = "Fond A", soldAtEpochDay = 19_030,
        candidates = listOf(
            SwitchWatchCandidate(id = 11, watchId = 3, isin = "SE0000000003", name = "Alt 1", position = 0),
            SwitchWatchCandidate(id = 12, watchId = 3, isin = "SE0000000004", name = "Alt 2", position = 1),
            SwitchWatchCandidate(
                id = 13, watchId = 3, isin = "SE0000000005", name = "Eget val",
                source = SwitchWatchCandidateSource.MANUELL, position = 2,
            ),
        ),
    )

    private val payload = BackupPayload(
        exportedAtEpochMillis = 1_700_000_000_000L,
        funds = listOf(fundA, fundB),
        transactions = listOf(tx1, tx2),
        suggestionRecords = listOf(record.copy(followed = true)),
        switchWatches = listOf(watch),
        accountType = AccountType.ISK_KF,
        themeMode = ThemeMode.DARK,
    )

    @Test
    fun `samma data ger samma fingeravtryck`() {
        assertEquals(BackupFingerprint.of(payload), BackupFingerprint.of(payload.copy()))
    }

    @Test
    fun `fingeravtrycket ar en sha-256 i hex`() {
        assertEquals(64, BackupFingerprint.of(payload).length)
    }

    @Test
    fun `en andrad transaktion ger ett annat fingeravtryck`() {
        val changed = payload.copy(transactions = listOf(tx1, tx2.copy(shares = 6.0)))
        assertNotEquals(BackupFingerprint.of(payload), BackupFingerprint.of(changed))
    }

    @Test
    fun `en ny transaktion ger ett annat fingeravtryck`() {
        val changed = payload.copy(transactions = listOf(tx1, tx2, tx2.copy(id = 3)))
        assertNotEquals(BackupFingerprint.of(payload), BackupFingerprint.of(changed))
    }

    @Test
    fun `en andrad installning i kontraktet ger ett annat fingeravtryck`() {
        assertNotEquals(BackupFingerprint.of(payload), BackupFingerprint.of(payload.copy(themeMode = ThemeMode.LIGHT)))
    }

    @Test
    fun `en kvittering av ett forslag ger ett annat fingeravtryck`() {
        val changed = payload.copy(suggestionRecords = listOf(record.copy(followed = false)))
        assertNotEquals(BackupFingerprint.of(payload), BackupFingerprint.of(changed))
    }

    @Test
    fun `exporttidpunkten paverkar inte`() {
        assertEquals(BackupFingerprint.of(payload), BackupFingerprint.of(payload.copy(exportedAtEpochMillis = 42)))
    }

    @Test
    fun `ordningen fran databasen paverkar inte`() {
        val reordered = payload.copy(
            funds = listOf(fundB, fundA),
            transactions = listOf(tx2, tx1),
            switchWatches = listOf(watch.copy(candidates = watch.candidates.reversed())),
        )
        assertEquals(BackupFingerprint.of(payload), BackupFingerprint.of(reordered))
    }

    @Test
    fun `bakgrundens egna skrivningar paverkar inte`() {
        // Källnyckeln slås upp och sparas av kursuppdateringen, okvitterade förslag spelas in och
        // gallras av bytesplansskanningen (HEM-8) — ingen av dem är något användaren gjort.
        val background = payload.copy(
            funds = listOf(fundA.copy(fondlistaFundId = "123"), fundB),
            suggestionRecords = payload.suggestionRecords + record.copy(id = 8, followed = null),
        )
        assertEquals(BackupFingerprint.of(payload), BackupFingerprint.of(background))
    }

    @Test
    fun `omfyllning och omankring av automatiska kandidater paverkar inte`() {
        // ANA-13: appen fyller på, byter ut och ankrar de automatiska kandidaterna själv, och
        // den egna kandidaten numreras om och ankras av appen — inget av det är användarens.
        val manual = watch.candidates.last()
        val refilled = watch.copy(
            candidates = listOf(
                SwitchWatchCandidate(id = 21, watchId = 3, isin = "SE0000000009", name = "Ny auto", navAtStart = 50.0, navAtStartEpochDay = 19_031),
                manual.copy(id = 22, position = 0, navAtStart = 101.0, navAtStartEpochDay = 19_031),
            ),
        )
        assertEquals(BackupFingerprint.of(payload), BackupFingerprint.of(payload.copy(switchWatches = listOf(refilled))))
    }

    @Test
    fun `en bevakning som gar ut av sig sjalv paverkar inte`() {
        val expired = watch.copy(closedAtEpochDay = 19_100, closeReason = SwitchWatchCloseReason.UTGANGEN)
        assertEquals(BackupFingerprint.of(payload), BackupFingerprint.of(payload.copy(switchWatches = listOf(expired))))
    }

    @Test
    fun `anvandarens egna andringar av bytesfonstret ger ett annat fingeravtryck`() {
        val base = BackupFingerprint.of(payload)
        val added = watch.copy(
            candidates = watch.candidates + SwitchWatchCandidate(isin = "SE0000000006", name = "Till", source = SwitchWatchCandidateSource.MANUELL),
        )
        val removed = watch.copy(candidates = watch.candidates.dropLast(1))
        val bought = watch.copy(closedAtEpochDay = 19_040, closeReason = SwitchWatchCloseReason.KOPT, boughtIsin = "SE0000000003")
        val cancelled = watch.copy(closedAtEpochDay = 19_040, closeReason = SwitchWatchCloseReason.AVBRUTEN)

        listOf(added, removed, bought, cancelled).forEach { changed ->
            assertNotEquals(base, BackupFingerprint.of(payload.copy(switchWatches = listOf(changed))))
        }
    }
}
