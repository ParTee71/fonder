package se.partee71.fonder.data.common

import android.database.sqlite.SQLiteException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import se.partee71.fonder.data.auth.SignInException
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.data.repository.DriveResult

/**
 * Alla fel från datalagret, mappade en gång: Room, de externa källorna (Handelsbanken, Avanza,
 * Riksbanken), importerna, inloggningen, säkerhetskopian och Drive. UI visar dem via [AppErrors]
 * och `DataError.toMessage()` i `ui/common` (NFR-6).
 */
sealed class DataError : Exception() {
    /** Appens databas (Room) gick inte att läsa eller skriva. */
    data object Database : DataError()

    /** En extern källa gick inte att nå, eller svarade med något som inte gick att använda. */
    data object Network : DataError()

    /** Innehavsfilen gick inte att tolka som en Handelsbanken-export (IMP-1). */
    data object ImportUnreadable : DataError()

    /** Innehavsfilen gick att läsa men hade inga rader (IMP-1). */
    data object ImportEmpty : DataError()

    /** Ingen av de valda avräkningsnotorna gav någon transaktion (IMP-6, IMP-7). */
    data object ImportNoTransactions : DataError()

    /** Inget Google-konto på enheten, eller Play-tjänster saknas/är för gamla ([SignInException.Reason.NO_CREDENTIAL], TP-6). */
    data object SignInNoCredential : DataError()

    /** Inloggningen misslyckades av något annat skäl — nätverk, Firebase-avslag eller okänt (TP-6). */
    data object SignInFailed : DataError()

    /** Säkerhetskopian kommer från en nyare app ([BackupFormatException.Reason.UNSUPPORTED_VERSION], SET-6). */
    data object BackupUnsupportedVersion : DataError()

    /** Säkerhetskopian gick inte att läsa eller tolka ([BackupFormatException.Reason.UNREADABLE], SET-6). */
    data object BackupUnreadable : DataError()

    /** Säkerhetskopian gick inte att skapa eller skriva dit användaren valt (SET-6, SET-7). */
    data object BackupExportFailed : DataError()

    /** Ingen är inloggad, så Drive går inte att nå ([DriveResult.NoAccount], SET-7). */
    data object DriveSignedOut : DataError()

    /** Drive svarade med ett fel ([DriveResult.Error], SET-7). */
    data object DriveFailed : DataError()

    /** Det som efterfrågas finns inte (längre). Ett nytt försök hjälper inte. */
    data object NotFound : DataError()

    data object Unknown : DataError()

    companion object {
        /**
         * Den enda översättningen från ett fel i datalagret till [DataError]: SQLite (Room) blir
         * [Database], ett I/O-fel från en källa [Network], inloggningens och säkerhetskopians fel
         * efter sin orsak, allt annat [Unknown]. Felets meddelande följer aldrig med – det kan bära
         * innehav eller belopp.
         */
        fun of(error: Throwable): DataError = when (error) {
            is DataError -> error

            is SQLiteException -> Database

            is IOException -> Network

            is BackupFormatException -> when (error.reason) {
                BackupFormatException.Reason.UNSUPPORTED_VERSION -> BackupUnsupportedVersion
                BackupFormatException.Reason.UNREADABLE -> BackupUnreadable
            }

            // Ett avbrutet kontoval är inget fel — anroparen visar det aldrig (TP-6).
            is SignInException -> when (error.reason) {
                SignInException.Reason.NO_CREDENTIAL -> SignInNoCredential
                SignInException.Reason.FAILED, SignInException.Reason.CANCELLED -> SignInFailed
            }

            else -> Unknown
        }

        /** Drives svar som fel, eller `null` för ett svar som inte är ett fel (SET-7). */
        fun of(result: DriveResult<*>): DataError? = when (result) {
            DriveResult.NoAccount -> DriveSignedOut
            is DriveResult.Error -> DriveFailed
            is DriveResult.Success, DriveResult.NoBackupFound, is DriveResult.NeedsAuthorization -> null
        }
    }
}

/**
 * Kör [block] och fångar ett fel som ett misslyckat [Result] med en [DataError] – för läsningar och
 * skrivningar vars fel ska visas (via [AppErrors]) i stället för att krascha. [mapError] väljer
 * orsaken, som standard [DataError.of]. Avbrott av korutinen släpps igenom.
 */
suspend fun <T> suspendRunCatching(mapError: (Throwable) -> DataError = DataError::of, block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(mapError(e))
}
