package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import se.partee71.fonder.domain.model.Fund

@Entity(tableName = "funds")
data class FundEntity(
    @PrimaryKey val fundId: String,
    val name: String,
    val currency: String,
    val isin: String? = null,
    /** Fondlista-plattformens kod när [fundId] är ett ISIN — se [Fund.fondlistaFundId] (issue #39). */
    val fondlistaFundId: String? = null,
) {
    fun toDomain() = Fund(
        fundId = fundId,
        name = name,
        currency = currency,
        isin = isin,
        fondlistaFundId = fondlistaFundId,
    )

    companion object {
        fun fromDomain(fund: Fund) = FundEntity(
            fundId = fund.fundId,
            name = fund.name,
            currency = fund.currency,
            isin = fund.isin,
            fondlistaFundId = fund.fondlistaFundId,
        )
    }
}
