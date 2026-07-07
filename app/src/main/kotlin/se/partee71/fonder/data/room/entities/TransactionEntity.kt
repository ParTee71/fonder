package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

@Entity(
    tableName = "transactions",
    indices = [Index("fundId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fundId: String,
    val type: String,
    val epochDay: Long,
    val shares: Double,
    val pricePerShare: Double,
    val fee: Double = 0.0,
) {
    fun toDomain() = Transaction(
        id = id,
        fundId = fundId,
        type = TransactionType.valueOf(type),
        epochDay = epochDay,
        shares = shares,
        pricePerShare = pricePerShare,
        fee = fee,
    )

    companion object {
        fun fromDomain(tx: Transaction) = TransactionEntity(
            id = tx.id,
            fundId = tx.fundId,
            type = tx.type.name,
            epochDay = tx.epochDay,
            shares = tx.shares,
            pricePerShare = tx.pricePerShare,
            fee = tx.fee,
        )
    }
}
