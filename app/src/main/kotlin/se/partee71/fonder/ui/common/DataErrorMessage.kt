package se.partee71.fonder.ui.common

import androidx.annotation.StringRes
import se.partee71.fonder.R
import se.partee71.fonder.data.common.DataError

/** Den enda översättningen från fel till svensk text (NFR-6). Importfelen behåller importskärmarnas egna texter. */
@StringRes
fun DataError.toMessage(): Int = when (this) {
    DataError.Database -> R.string.error_database
    DataError.Network -> R.string.error_network
    DataError.ImportUnreadable -> R.string.import_error_parse
    DataError.ImportEmpty -> R.string.import_error_empty
    DataError.ImportNoTransactions -> R.string.import_orders_error_none_parsed
    DataError.NotFound -> R.string.error_not_found
    DataError.Unknown -> R.string.error_unknown
}
