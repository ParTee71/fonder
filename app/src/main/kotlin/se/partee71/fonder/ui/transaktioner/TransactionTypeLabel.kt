package se.partee71.fonder.ui.transaktioner

import androidx.annotation.StringRes
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.TransactionType

/** "Köp"/"Sälj" — en gång för formuläret, transaktionslistan och båda importerna (regel 4). */
@StringRes
fun TransactionType.labelRes(): Int = when (this) {
    TransactionType.KOP -> R.string.transaktion_kop
    TransactionType.SALJ -> R.string.transaktion_salj
}
