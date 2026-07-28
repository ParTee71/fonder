package se.partee71.fonder.data.room.entities

import androidx.room.Entity

/**
 * En dagsnoterad växelkurs mot kronor: `1 [currency] = [rate] SEK` den dagen.
 *
 * Cachad härledd data (NFR-1) — hämtas om från Riksbanken vid behov och ingår därför inte i
 * backup-kontraktet. Historiska kurser ändras inte, så en gång hämtad rad är permanent giltig.
 */
@Entity(tableName = "fx_rates", primaryKeys = ["currency", "epochDay"])
data class FxRateEntity(
    val currency: String,
    val epochDay: Long,
    val rate: Double,
)
