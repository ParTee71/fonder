package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import se.partee71.fonder.domain.model.FundPrice

@Entity(tableName = "fund_prices", primaryKeys = ["fundId", "epochDay"])
data class FundPriceEntity(
    val fundId: String,
    val epochDay: Long,
    val nav: Double,
    val currency: String,
) {
    fun toDomain() = FundPrice(fundId = fundId, epochDay = epochDay, nav = nav, currency = currency)

    companion object {
        fun fromDomain(price: FundPrice) = FundPriceEntity(
            fundId = price.fundId,
            epochDay = price.epochDay,
            nav = price.nav,
            currency = price.currency,
        )
    }
}
