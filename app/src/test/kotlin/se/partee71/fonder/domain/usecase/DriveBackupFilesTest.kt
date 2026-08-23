package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * `appDataFolder` delas med Dagboken (samma Cloud-projekt), så gallringen är den enda plats i
 * appen där en bugg kan radera **en annan apps** data. Den logiken ligger därför i ren Kotlin —
 * och de här testerna är hela skälet till att den gör det.
 */
class DriveBackupFilesTest {

    private fun file(name: String, created: String, id: String = name) =
        DriveBackupFile(id = id, name = name, createdTime = created)

    @Test
    fun `filnamnet bar prefixet och en sorterbar tidsstampel`() {
        val name = DriveBackupFiles.fileName(LocalDateTime.of(2026, 8, 23, 14, 5))

        assertEquals("fonder-backup-20260823-1405.json", name)
        assertTrue(DriveBackupFiles.isOurs(name))
    }

    @Test
    fun `fragan filtrerar bade pa prefix och pa papperskorgen`() {
        // trashed = false är inte kosmetiskt: en papperskorgad fil ligger kvar i mappen och
        // skulle annars kunna returneras som "senaste säkerhetskopian".
        assertTrue(DriveBackupFiles.QUERY.contains(DriveBackupFiles.PREFIX))
        assertTrue(DriveBackupFiles.QUERY.contains("trashed = false"))
    }

    @Test
    fun `Dagbokens filer ar inte vara`() {
        assertFalse(DriveBackupFiles.isOurs("dagboken-backup-20260823-1405.json"))
        assertTrue(DriveBackupFiles.isOurs("fonder-backup-20260823-1405.json"))
    }

    @Test
    fun `gallringen behaller de fem senaste och tar bort resten`() {
        val files = (1..8).map { file("fonder-backup-2026082$it-1200.json", "2026-08-2${it}T12:00:00Z") }

        val stale = DriveBackupFiles.staleFileIds(files)

        assertEquals(3, stale.size)
        assertEquals(
            listOf(
                "fonder-backup-20260823-1200.json",
                "fonder-backup-20260822-1200.json",
                "fonder-backup-20260821-1200.json",
            ),
            stale,
        )
    }

    @Test
    fun `gallringen ror aldrig en fil som inte ar var — aven om den sipprat in i listan`() {
        // Regressionsskydd för det enda felet som kan förstöra Dagbokens data: att listningen
        // någon gång görs utan DriveBackupFiles.QUERY. Urvalet filtrerar om, så en sådan
        // ändring blir ofarlig i stället för katastrofal.
        val files = listOf(
            file("fonder-backup-20260823-1200.json", "2026-08-23T12:00:00Z"),
            file("dagboken-backup-20260101-1200.json", "2026-01-01T12:00:00Z"),
            file("dagboken-backup-20250101-1200.json", "2025-01-01T12:00:00Z"),
        )

        val stale = DriveBackupFiles.staleFileIds(files, keep = 1)

        assertTrue("Dagbokens filer får aldrig väljas för radering", stale.isEmpty())
    }

    @Test
    fun `gallringen sorterar sjalv och litar inte pa inkommande ordning`() {
        val files = listOf(
            file("fonder-backup-20260101-1200.json", "2026-01-01T12:00:00Z"),
            file("fonder-backup-20260823-1200.json", "2026-08-23T12:00:00Z"),
            file("fonder-backup-20260501-1200.json", "2026-05-01T12:00:00Z"),
        )

        val stale = DriveBackupFiles.staleFileIds(files, keep = 1)

        assertEquals(
            listOf("fonder-backup-20260501-1200.json", "fonder-backup-20260101-1200.json"),
            stale,
        )
    }

    @Test
    fun `farre kopior an takkravet ger ingen gallring`() {
        val files = (1..3).map { file("fonder-backup-2026082$it-1200.json", "2026-08-2${it}T12:00:00Z") }

        assertTrue(DriveBackupFiles.staleFileIds(files).isEmpty())
    }

    @Test
    fun `keep noll gallrar allt vart — men fortfarande bara vart`() {
        val files = listOf(
            file("fonder-backup-20260823-1200.json", "2026-08-23T12:00:00Z"),
            file("dagboken-backup-20260823-1200.json", "2026-08-23T12:00:00Z"),
        )

        assertEquals(listOf("fonder-backup-20260823-1200.json"), DriveBackupFiles.staleFileIds(files, keep = 0))
    }
}
