package se.partee71.fonder.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import se.partee71.fonder.R
import se.partee71.fonder.data.auth.SignInException
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.BackupRecord
import se.partee71.fonder.domain.model.BackupTarget
import se.partee71.fonder.domain.usecase.BackupReminderCalc
import se.partee71.fonder.ui.components.AppButton
import se.partee71.fonder.ui.components.AppButtonVariant
import se.partee71.fonder.ui.components.AppCard
import se.partee71.fonder.ui.components.AppCardVariant
import se.partee71.fonder.ui.components.CardTitleRow
import se.partee71.fonder.ui.components.ChoiceChipRow
import se.partee71.fonder.ui.components.ConfirmDialog
import se.partee71.ui.theme.Spacing

/** Säkerhetskopieringskortet — målet för Hems påminnelse (HEM-12), adresserbart i tester. */
const val SETTINGS_BACKUP_CARD_TEST_TAG = "settings_backup_card"

private val lastSyncFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Filväljarens filter vid återställning. Flera typer av samma skäl som importflödet: en
 * `.json`-fil rapporteras olika beroende på var den ligger (Drive, Downloads, en delad mapp),
 * och ett för snävt filter döljer användarens egen säkerhetskopia i väljaren.
 */
private val BACKUP_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onImportHoldings: () -> Unit = {},
    onImportOrders: () -> Unit = {},
    onOpenRiskProfile: () -> Unit = {},
    onOpenFacit: () -> Unit = {},
    onOpenBenchmarkPicker: () -> Unit = {},
    scrollToBackup: Boolean = false,
    onScrolledToBackup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // SAF: appen får skriva/läsa precis den fil användaren pekar ut, utan lagringsbehörighet.
    // Själva I/O:n ligger här och inte i ViewModel:en — den känner bara till JSON-strängen,
    // precis som BackupRepository (SET-6, issue #82).
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.export { json ->
            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("Kunde inte öppna $uri för skrivning")
            stream.use { it.write(json.toByteArray()) }
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.restore {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }
    }

    // Drive-scopet begärs skilt från inloggningen och kan kräva att användaren godkänner det i
    // Googles egen ruta. Den startas som en PendingIntent — därför måste den vägen gå via
    // aktiviteten och inte via tillståndet (samma mekanik som SAF-väljarna ovan).
    val driveAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        // Utfallet rapporteras inte tillförlitligt av Identity-API:t. Flaggan rensas och nästa
        // körning får avgöra — ett kvarliggande "behöver tillåtelse" efter ett beviljande är
        // värre än ett som dyker upp igen.
        viewModel.onDriveAuthorizationResolved()
    }
    val launchDriveAuth: (android.app.PendingIntent) -> Unit = { pendingIntent ->
        driveAuthLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    SettingsContent(
        state = state,
        onThemeSelected = viewModel::setThemeMode,
        onImportHoldings = onImportHoldings,
        onImportOrders = onImportOrders,
        onOpenRiskProfile = onOpenRiskProfile,
        onOpenFacit = onOpenFacit,
        onOpenBenchmarkPicker = onOpenBenchmarkPicker,
        onClearBenchmark = viewModel::clearBenchmark,
        onAccountTypeSelected = viewModel::setAccountType,
        onExportBackup = { exportPicker.launch("fonder-backup-${LocalDate.now()}.json") },
        onRestoreBackup = { restorePicker.launch(BACKUP_MIME_TYPES) },
        onBackupMessageDismissed = viewModel::onBackupMessageDismissed,
        onBackupToDrive = { viewModel.backupToDrive(launchDriveAuth) },
        onRestoreFromDrive = { viewModel.restoreFromDrive(launchDriveAuth) },
        onSignIn = { viewModel.signIn(context) },
        onSignOut = viewModel::signOut,
        onSignInErrorDismissed = viewModel::onSignInErrorDismissed,
        onClearDatabase = viewModel::clearDatabase,
        onClearedMessageDismissed = viewModel::onClearedMessageDismissed,
        scrollToBackup = scrollToBackup,
        onScrolledToBackup = onScrolledToBackup,
        modifier = modifier
    )
}

/** Tillståndsdriven, testbar del av [SettingsScreen] — inget ViewModel/Hilt-beroende (issue #27). */
@OptIn(ExperimentalFoundationApi::class) // BringIntoViewRequester, för Hems påminnelse (HEM-12)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onThemeSelected: (ThemeMode) -> Unit = {},
    onImportHoldings: () -> Unit = {},
    onImportOrders: () -> Unit = {},
    onOpenRiskProfile: () -> Unit = {},
    onOpenFacit: () -> Unit = {},
    onOpenBenchmarkPicker: () -> Unit = {},
    onClearBenchmark: () -> Unit = {},
    onAccountTypeSelected: (AccountType) -> Unit = {},
    onExportBackup: () -> Unit = {},
    onRestoreBackup: () -> Unit = {},
    onBackupMessageDismissed: () -> Unit = {},
    onBackupToDrive: () -> Unit = {},
    onRestoreFromDrive: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onSignInErrorDismissed: () -> Unit = {},
    onClearDatabase: () -> Unit = {},
    onClearedMessageDismissed: () -> Unit = {},
    scrollToBackup: Boolean = false,
    onScrolledToBackup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Hems påminnelse (HEM-12) landar här: säkerhetskopieringen ligger långt ned, så den
    // skrollas fram och begäran kvitteras — en gång, inte vid varje återbesök.
    val backupBringIntoView = remember { BringIntoViewRequester() }
    val scrolledToBackup by rememberUpdatedState(onScrolledToBackup)
    LaunchedEffect(scrollToBackup) {
        if (scrollToBackup) {
            backupBringIntoView.bringIntoView()
            scrolledToBackup()
        }
    }

    // rememberSaveable: en bekräftelsedialog ska inte försvinna tyst vid rotation (issue #78).
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showRestoreConfirm by rememberSaveable { mutableStateOf(false) }
    var showDriveRestoreConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l)
    ) {
        Text(
            stringResource(R.string.settings_theme_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = Spacing.s)
        )
        ChoiceChipRow(
            options = ThemeMode.entries,
            selected = state.themeMode,
            optionLabel = { mode -> stringResource(themeModeLabelRes(mode)) },
            onSelect = onThemeSelected
        )

        AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl)) {
            Text(stringResource(R.string.settings_riskprofile_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_riskprofile_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            AppButton(text = stringResource(R.string.settings_riskprofile_button), onClick = onOpenRiskProfile)
        }

        AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
            Text(stringResource(R.string.settings_account_type_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_account_type_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            ChoiceChipRow(
                options = AccountType.entries,
                selected = state.accountType,
                optionLabel = { type -> stringResource(type.labelRes()) },
                onSelect = onAccountTypeSelected
            )
        }

        // Jämförelsereferensen (HEM-10, issue #102) — appens val är ett förslag, användarens
        // val vinner. Raden säger alltid vilket av de två som gäller just nu.
        AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
            Text(stringResource(R.string.settings_benchmark_section), style = MaterialTheme.typography.titleSmall)
            Text(
                state.chosenBenchmarkName
                    ?.let { stringResource(R.string.format_settings_benchmark_chosen, it) }
                    ?: stringResource(R.string.settings_benchmark_automatic),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.s)
            )
            Text(
                stringResource(R.string.settings_benchmark_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            if (state.benchmarkPickFailed) {
                Text(
                    stringResource(R.string.settings_benchmark_pick_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Spacing.s)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AppButton(text = stringResource(R.string.settings_benchmark_button), onClick = onOpenBenchmarkPicker)
                if (state.chosenBenchmarkName != null) {
                    AppButton(
                        text = stringResource(R.string.settings_benchmark_clear),
                        onClick = onClearBenchmark,
                        variant = AppButtonVariant.Text
                    )
                }
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
            Text(stringResource(R.string.settings_facit_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_facit_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            AppButton(text = stringResource(R.string.settings_facit_button), onClick = onOpenFacit)
        }

        AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
            CardTitleRow(
                title = stringResource(R.string.settings_price_update_section),
                working = state.priceRefreshWorking,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.settings_price_update_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            Text(
                lastPriceSyncText(state.lastPriceSyncEpochMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s)
            )
            // Ingen "Uppdatera nu"-knapp längre (SET-2 struken): uppdatering sker med en
            // dragning där datan faktiskt visas (UI-11), inte tre skärmar bort under en
            // inställningsrubrik. Raden ovan står kvar — den svarar på "hur färsk är datan",
            // vilket är en annan fråga än "uppdatera den".
        }

        // Google-konto (TP-6). Inloggningen flyttar ingen data i sig — den är förutsättningen
        // för molnbackupen (TP-7 steg 2), vilket kortets text säger rakt ut så att "inloggad"
        // inte förväxlas med "säkerhetskopierad".
        AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
            CardTitleRow(
                title = stringResource(R.string.settings_account_section),
                working = state.signInInProgress,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                state.googleUser?.let { user ->
                    stringResource(
                        R.string.format_settings_account_signed_in,
                        user.email ?: user.displayName ?: stringResource(R.string.settings_account_unknown_user)
                    )
                } ?: stringResource(R.string.settings_account_signed_out),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.s)
            )
            Text(
                stringResource(R.string.settings_account_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            // Ett avbrutet kontoval är inget fel och når aldrig hit — se
            // SettingsViewModel.signIn. Kvitteras bort som övriga engångshändelser (#78).
            state.signInError?.let { reason ->
                Text(
                    signInErrorText(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Spacing.xs)
                )
                AppButton(
                    text = stringResource(R.string.close),
                    onClick = onSignInErrorDismissed,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                    variant = AppButtonVariant.Text
                )
            }
            if (state.googleUser == null) {
                AppButton(
                    text = stringResource(R.string.settings_account_sign_in_button),
                    onClick = onSignIn,
                    enabled = !state.signInInProgress
                )
            } else {
                AppButton(
                    text = stringResource(R.string.settings_account_sign_out_button),
                    onClick = onSignOut,
                    enabled = !state.signInInProgress,
                    variant = AppButtonVariant.Text
                )
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
            Text(stringResource(R.string.settings_import_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_import_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            AppButton(text = stringResource(R.string.settings_import_button), onClick = onImportHoldings)
        }

        AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
            Text(stringResource(R.string.settings_import_orders_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_import_orders_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            AppButton(text = stringResource(R.string.settings_import_orders_button), onClick = onImportOrders)
        }

        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.l)
                .bringIntoViewRequester(backupBringIntoView)
                .testTag(SETTINGS_BACKUP_CARD_TEST_TAG)
        ) {
            CardTitleRow(
                title = stringResource(R.string.settings_backup_section),
                working = state.backupInProgress,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.settings_backup_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs)
            )
            // En rad för båda transporterna: det är samma kopia, och det är den Hems
            // påminnelse (HEM-12) räknar från.
            Text(
                lastBackupText(state.lastBackup),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s)
            )
            // Meddelandet läses direkt ur tillståndet och kvitteras i ViewModel:en, av samma
            // skäl som "databasen tömd" nedan: en engångshändelse lagrad som bestående
            // tillstånd spelas upp igen vid varje rotation och återbesök (issue #78).
            state.backupMessage?.let { message ->
                Text(
                    backupMessageText(message),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = Spacing.xs)
                )
                AppButton(
                    text = stringResource(R.string.close),
                    onClick = onBackupMessageDismissed,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                    variant = AppButtonVariant.Text
                )
            }
            Row {
                AppButton(
                    text = stringResource(R.string.settings_backup_export_button),
                    onClick = onExportBackup,
                    enabled = !state.backupInProgress
                )
                AppButton(
                    text = stringResource(R.string.settings_backup_restore_button),
                    onClick = { showRestoreConfirm = true },
                    enabled = !state.backupInProgress,
                    modifier = Modifier.padding(start = Spacing.s),
                    variant = AppButtonVariant.Text
                )
            }

            // Molnbackupen (SET-7) ligger i samma kort som filbackupen, inte i ett eget:
            // det är samma data och samma kontrakt, bara en annan transport. Två kort hade
            // antytt två sorters säkerhetskopia.
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.m))
            Text(
                stringResource(R.string.settings_drive_backup_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.settings_drive_backup_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            if (state.driveBackupNeedsAuth) {
                Text(
                    stringResource(R.string.settings_drive_backup_needs_auth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Spacing.s)
                )
            }
            Row {
                AppButton(
                    text = stringResource(R.string.settings_drive_backup_button),
                    onClick = onBackupToDrive,
                    enabled = !state.backupInProgress
                )
                AppButton(
                    text = stringResource(R.string.settings_drive_restore_button),
                    onClick = { showDriveRestoreConfirm = true },
                    enabled = !state.backupInProgress,
                    modifier = Modifier.padding(start = Spacing.s),
                    variant = AppButtonVariant.Text
                )
            }
        }

        AppCard(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.l),
            variant = AppCardVariant.Error
        ) {
            Text(
                stringResource(R.string.settings_danger_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                stringResource(R.string.settings_danger_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
            )
            // Meddelandet läses direkt ur tillståndet och kvitteras i ViewModel:en — ingen
            // lokal spegling via LaunchedEffect, som fick engångshändelsen att spelas upp
            // igen vid varje rotation och återbesök (issue #78). Kvitteringen är också
            // meddelandets enda väg ut; tidigare fanns ingen.
            if (state.databaseCleared) {
                Text(
                    stringResource(R.string.settings_clear_database_success),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(bottom = Spacing.xs)
                )
                AppButton(
                    text = stringResource(R.string.close),
                    onClick = onClearedMessageDismissed,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                    variant = AppButtonVariant.Text
                )
            }
            AppButton(
                text = stringResource(R.string.settings_clear_database_button),
                onClick = { showClearConfirm = true },
                variant = AppButtonVariant.Danger
            )
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.clear_database_confirm_title),
            body = stringResource(R.string.clear_database_confirm_body),
            confirmLabel = stringResource(R.string.settings_clear_database_button),
            onConfirm = {
                onClearDatabase()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false },
            destructive = true
        )
    }

    // Samma bekräftelse som filåterställningen, och med flit samma text: det är samma
    // ersättning av samma data, bara från en annan plats.
    if (showDriveRestoreConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.backup_restore_confirm_title),
            body = stringResource(R.string.backup_restore_confirm_body),
            confirmLabel = stringResource(R.string.backup_restore_confirm_button),
            onConfirm = {
                showDriveRestoreConfirm = false
                onRestoreFromDrive()
            },
            onDismiss = { showDriveRestoreConfirm = false }
        )
    }

    // Bekräftelsen kommer före filväljaren, inte efter: en återställning ersätter all data, och
    // frågan ska ställas innan användaren är mitt i ett val (samma ordning som farozonen).
    if (showRestoreConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.backup_restore_confirm_title),
            body = stringResource(R.string.backup_restore_confirm_body),
            confirmLabel = stringResource(R.string.backup_restore_confirm_button),
            onConfirm = {
                showRestoreConfirm = false
                onRestoreBackup()
            },
            onDismiss = { showRestoreConfirm = false }
        )
    }
}

@Composable
private fun lastBackupText(record: BackupRecord?): String {
    if (record == null) return stringResource(R.string.settings_backup_never)
    val date = BackupReminderCalc.formatDate(record.epochMillis)
    return when (record.target) {
        BackupTarget.DRIVE -> stringResource(R.string.format_settings_backup_last, date, stringResource(R.string.backup_target_drive))
        BackupTarget.FILE -> stringResource(R.string.format_settings_backup_last, date, stringResource(R.string.backup_target_file))
        null -> stringResource(R.string.format_settings_backup_last_date_only, date)
    }
}

@Composable
private fun backupMessageText(message: BackupMessage): String = when (message) {
    BackupMessage.Exported -> stringResource(R.string.backup_export_success)

    BackupMessage.ExportFailed -> stringResource(R.string.backup_export_failed)

    is BackupMessage.Restored -> stringResource(
        R.string.format_backup_restore_success,
        message.summary.funds,
        message.summary.transactions,
        message.summary.suggestionRecords,
        message.summary.switchWatches
    )

    is BackupMessage.RestoreFailed -> when (message.reason) {
        BackupFormatException.Reason.UNSUPPORTED_VERSION -> stringResource(R.string.backup_restore_failed_version)
        BackupFormatException.Reason.UNREADABLE -> stringResource(R.string.backup_restore_failed_unreadable)
    }

    BackupMessage.DriveSaved -> stringResource(R.string.drive_backup_success)

    BackupMessage.DriveEmpty -> stringResource(R.string.drive_backup_empty)

    is BackupMessage.DriveFailed ->
        if (message.signedOut) {
            stringResource(R.string.drive_backup_signed_out)
        } else {
            stringResource(R.string.drive_backup_failed)
        }
}

@Composable
private fun signInErrorText(reason: SignInException.Reason): String = when (reason) {
    SignInException.Reason.NO_CREDENTIAL -> stringResource(R.string.sign_in_failed_no_credential)

    SignInException.Reason.FAILED -> stringResource(R.string.sign_in_failed)

    // Kommer aldrig hit: ett avbrutet val filtreras bort i SettingsViewModel.signIn. Grenen
    // finns för att `when` ska vara uttömmande utan `else`, så en ny orsak fäller kompileringen
    // i stället för att tyst visa fel text.
    SignInException.Reason.CANCELLED -> stringResource(R.string.sign_in_failed)
}

@Composable
private fun lastPriceSyncText(epochMillis: Long?): String {
    if (epochMillis == null) return stringResource(R.string.settings_last_price_sync_never)
    val formatted = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(lastSyncFormatter)
    return stringResource(R.string.format_settings_last_price_sync, formatted)
}

private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.AUTO -> R.string.theme_auto
}
