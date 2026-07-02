package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

@Entity(
    tableName = "transactions",
    indices = [Index("fundIsin")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fundIsin: String,
    val type: String,
    val epochDay: Long,
    val shares: Double,
    val pricePerShare: Double,
) {
    fun toDomain() = Transaction(
        id = id,
        fundIsin = fundIsin,
        type = TransactionType.valueOf(type),
        epochDay = epochDay,
        shares = shares,
        pricePerShare = pricePerShare,
    )

    companion object {
        fun fromDomain(tx: Transaction) = TransactionEntity(
            id = tx.id,
            fundIsin = tx.fundIsin,
            type = tx.type.name,
            epochDay = tx.epochDay,
            shares = tx.shares,
            pricePerShare = tx.pricePerShare,
        )
    }
}
