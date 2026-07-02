package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import se.partee71.fonder.domain.model.Fund

@Entity(tableName = "funds")
data class FundEntity(
    @PrimaryKey val isin: String,
    val name: String,
    val currency: String,
) {
    fun toDomain() = Fund(isin = isin, name = name, currency = currency)

    companion object {
        fun fromDomain(fund: Fund) =
            FundEntity(isin = fund.isin, name = fund.name, currency = fund.currency)
    }
}
