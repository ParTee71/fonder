package se.partee71.fonder.worker

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import se.partee71.fonder.data.repository.DriveResult

/**
 * [DriveBackupWorker] själv går inte att konstruera i ett JVM-test (`CoroutineWorker` kräver
 * Androids `WorkerParameters`-maskineri). Hela dess beslut ligger därför i [outcomeFor], och
 * workern är inget mer än ett `when` över resultatet — det som testas här är alltså det som
 * faktiskt avgör körningen.
 */
class DriveBackupOutcomeTest {

    @Test
    fun `en lyckad uppladdning raknas som sparad`() {
        assertEquals(DriveBackupOutcome.SAVED, outcomeFor(DriveResult.Success("file-id")))
    }

    @Test
    fun `utloggad ar inte ett fel`() {
        // Ett bakgrundsjobb som failar för att ingen loggat in skulle backa av exponentiellt och
        // till slut ge upp — för ett läge som är helt normalt.
        assertEquals(DriveBackupOutcome.NOTHING_TO_DO, outcomeFor(DriveResult.NoAccount))
    }

    @Test
    fun `tom mapp ar inte ett fel`() {
        assertEquals(DriveBackupOutcome.NOTHING_TO_DO, outcomeFor(DriveResult.NoBackupFound))
    }

    @Test
    fun `saknad auktorisering flaggas i stallet for att koas om`() {
        // En retry kan inte lösa saken — bara användaren kan, via Googles ruta i Inställningar.
        val result = DriveResult.NeedsAuthorization(mockk(relaxed = true))

        assertEquals(DriveBackupOutcome.NEEDS_AUTH, outcomeFor(result))
    }

    @Test
    fun `ett Drive-fel ger retry`() {
        assertEquals(DriveBackupOutcome.RETRY, outcomeFor(DriveResult.Error("Drive 503")))
    }
}
