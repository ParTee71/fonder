package se.partee71.fonder.data.common

import android.database.sqlite.SQLiteException
import java.io.IOException

/**
 * Alla fel från datalagret, mappade en gång: Room, de externa källorna (Handelsbanken, Avanza,
 * Riksbanken) och importerna. UI visar dem via [AppErrors] och `DataError.toMessage()` i `ui/common`
 * (NFR-6). Inloggningens och backupens fel har egna orsakstyper (`SignInException.Reason`,
 * `BackupFormatException.Reason`) tills Inställningar flyttas hit.
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

    /** Det som efterfrågas finns inte (längre). Ett nytt försök hjälper inte. */
    data object NotFound : DataError()

    data object Unknown : DataError()

    companion object {
        /**
         * Den enda översättningen från ett fel i datalagret till [DataError]: SQLite (Room) blir
         * [Database], ett I/O-fel från en källa [Network], allt annat [Unknown]. Felets meddelande
         * följer aldrig med – det kan bära innehav eller belopp.
         */
        fun of(error: Throwable): DataError = when (error) {
            is DataError -> error
            is SQLiteException -> Database
            is IOException -> Network
            else -> Unknown
        }
    }
}
