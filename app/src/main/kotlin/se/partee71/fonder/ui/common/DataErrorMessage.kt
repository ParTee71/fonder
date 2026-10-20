package se.partee71.fonder.ui.common

import androidx.annotation.StringRes
import se.partee71.fonder.R
import se.partee71.fonder.data.common.DataError

/**
 * Den enda översättningen från fel till svensk text (NFR-6). Importfelen, inloggningen, säkerhetskopian
 * och Drive behåller sina skärmars egna texter.
 */
@StringRes
fun DataError.toMessage(): Int = when (this) {
    DataError.Database -> R.string.error_database
    DataError.Network -> R.string.error_network
    DataError.ImportUnreadable -> R.string.import_error_parse
    DataError.ImportEmpty -> R.string.import_error_empty
    DataError.ImportNoTransactions -> R.string.import_orders_error_none_parsed
    DataError.SignInNoCredential -> R.string.sign_in_failed_no_credential
    DataError.SignInFailed -> R.string.sign_in_failed
    DataError.BackupUnsupportedVersion -> R.string.backup_restore_failed_version
    DataError.BackupUnreadable -> R.string.backup_restore_failed_unreadable
    DataError.BackupExportFailed -> R.string.backup_export_failed
    DataError.DriveSignedOut -> R.string.drive_backup_signed_out
    DataError.DriveFailed -> R.string.drive_backup_failed
    DataError.NotFound -> R.string.error_not_found
    DataError.Unknown -> R.string.error_unknown
}
