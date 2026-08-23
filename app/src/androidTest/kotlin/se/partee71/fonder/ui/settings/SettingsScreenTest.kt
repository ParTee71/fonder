package se.partee71.fonder.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.auth.AuthUser
import se.partee71.fonder.data.auth.SignInException
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.data.repository.RestoreSummary
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Inställningars tillståndsdrivna innehåll (issue #27) — fokuserar på
 * kursuppdateringskortet (SET-2): "Senast uppdaterad" och "Uppdatera nu".
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_aldrig_uppdaterad_utan_kand_synktid() {
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(lastPriceSyncEpochMillis = null))
            }
        }

        composeRule.onNodeWithText("Aldrig uppdaterad").assertExists()
    }

    @Test
    fun visar_senast_uppdaterad_tidsstampel_nar_kand() {
        // 2024-03-15 12:00 UTC — matchar mönstret oavsett exakt lokal tidszon i CI.
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(lastPriceSyncEpochMillis = 1710504000000L))
            }
        }

        composeRule.onNodeWithText("Senast uppdaterad:", substring = true).assertExists()
        composeRule.onNodeWithText("Aldrig uppdaterad").assertDoesNotExist()
    }

    @Test
    fun uppdatera_nu_knappen_anropar_callback() {
        var called = false
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(), onRefreshPricesNow = { called = true })
            }
        }

        // performScrollTo före varje knappklick i den här filen: Inställningar är en
        // `verticalScroll`-kolumn som växer med varje nytt kort, och en knapp som hamnar under
        // skärmkanten är fortfarande *komponerad* — assertExists passerar, performClick kastar
        // inget, men klicket landar utanför noden och callbacken uteblir. Det har nu bitit två
        // gånger (issue #78:s "Stäng", och den här när facit-kortet lades in ovanför, #80).
        composeRule.onNodeWithText("Uppdatera nu").performScrollTo().performClick()
        assertTrue(called)
    }

    // --- Riskprofil-ingång (SET-3, issue #68) ---

    @Test
    fun temavaljaren_visar_alla_tre_lagen_via_delad_ChoiceChipRow() {
        // Regression efter extraheringen till den delade ChoiceChipRow (issue #68) —
        // temaväljaren ska fungera precis som innan omskrivningen.
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState()) }
        }

        composeRule.onNodeWithText("Ljust").assertExists()
        composeRule.onNodeWithText("Mörkt").assertExists()
        composeRule.onNodeWithText("Auto").assertExists()
    }

    @Test
    fun riskprofil_knappen_anropar_callback() {
        var called = false
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(), onOpenRiskProfile = { called = true })
            }
        }

        composeRule.onNodeWithText("Öppna riskprofil").performScrollTo().performClick()
        assertTrue(called)
    }

    // --- Kontotyp (SET-4, issue #70) ---

    @Test
    fun kontotypvaljaren_visar_bada_alternativen() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState()) }
        }

        composeRule.onNodeWithText("ISK/KF").assertExists()
        composeRule.onNodeWithText("Depå/AF").assertExists()
    }

    @Test
    fun kontotypvaljaren_anropar_onAccountTypeSelected() {
        var selected: AccountType? = null
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(), onAccountTypeSelected = { selected = it })
            }
        }

        composeRule.onNodeWithText("ISK/KF").performClick()

        assertEquals(AccountType.ISK_KF, selected)
    }

    @Test
    fun facit_knappen_anropar_callback() {
        // SET-5 (issue #80): facit nås som egen undersida från Inställningar, samma mönster som
        // Riskprofil (SET-3).
        var opened = false
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(), onOpenFacit = { opened = true }) }
        }

        composeRule.onNodeWithText("Öppna facit").performScrollTo().performClick()

        assertTrue(opened)
    }

    @Test
    fun tomningsmeddelandet_visas_ur_tillstandet_och_gar_att_kvittera() {
        // Regression (issue #78): meddelandet speglades lokalt via ett LaunchedEffect och
        // ViewModel:ens flagga nollställdes aldrig, så engångshändelsen spelades upp igen vid
        // varje rotation och varje återbesök. Nu läses den ur tillståndet och kvitteras.
        var dismissed = false
        composeRule.setContent {
            FonderTheme {
                SettingsContent(
                    state = SettingsUiState(databaseCleared = true),
                    onClearedMessageDismissed = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Databasen har tömts.").assertExists()
        // Farozonen ligger sist i en `verticalScroll`-kolumn (UI-5). Utan scroll är knappen
        // komponerad men klippt, och klicket landar utanför den — `assertExists` hade passerat
        // och `performClick` kastat inget, men callbacken uteblev.
        composeRule.onNodeWithText("Stäng").performScrollTo().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun tomningsmeddelandet_visas_inte_utan_tomd_databas() {
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(databaseCleared = false))
            }
        }

        composeRule.onNodeWithText("Databasen har tömts.").assertDoesNotExist()
    }

    // --- Säkerhetskopiering (SET-6, issue #82) ---

    @Test
    fun backup_kortet_visar_bada_knapparna() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState()) }
        }

        composeRule.onNodeWithText("Exportera till fil").assertExists()
        composeRule.onNodeWithText("Återställ från fil").assertExists()
    }

    @Test
    fun export_knappen_anropar_callback() {
        var called = false
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(), onExportBackup = { called = true }) }
        }

        composeRule.onNodeWithText("Exportera till fil").performScrollTo().performClick()

        assertTrue(called)
    }

    @Test
    fun aterstallning_kraver_bekraftelse_innan_filvaljaren_oppnas() {
        // Återställningen ersätter all data — filväljaren får inte öppnas på ett enda tryck.
        var called = false
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(), onRestoreBackup = { called = true }) }
        }

        composeRule.onNodeWithText("Återställ från fil").performScrollTo().performClick()

        assertFalse("callbacken får inte köras förrän dialogen bekräftats", called)
        composeRule.onNodeWithText("Återställ från säkerhetskopia?").assertExists()

        composeRule.onNodeWithText("Återställ").performClick()
        assertTrue(called)
    }

    @Test
    fun avbruten_bekraftelse_aterstaller_ingenting() {
        var called = false
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(), onRestoreBackup = { called = true }) }
        }

        composeRule.onNodeWithText("Återställ från fil").performScrollTo().performClick()
        composeRule.onNodeWithText("Avbryt").performClick()

        assertFalse(called)
        composeRule.onNodeWithText("Återställ från säkerhetskopia?").assertDoesNotExist()
    }

    @Test
    fun knapparna_ar_slackta_medan_en_backup_pagar() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(backupInProgress = true)) }
        }

        composeRule.onNodeWithText("Exportera till fil").assertIsNotEnabled()
        composeRule.onNodeWithText("Återställ från fil").assertIsNotEnabled()
    }

    @Test
    fun aterstallningens_summering_visas_och_gar_att_kvittera() {
        var dismissed = false
        composeRule.setContent {
            FonderTheme {
                SettingsContent(
                    state = SettingsUiState(backupMessage = BackupMessage.Restored(RestoreSummary(2, 5, 3))),
                    onBackupMessageDismissed = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Återställt: 2 fonder, 5 transaktioner och 3 inspelade förslag.").assertExists()
        composeRule.onNodeWithText("Stäng").performScrollTo().performClick()

        assertTrue(dismissed)
    }

    @Test
    fun ett_versionsfel_far_sin_egen_text_och_inte_trasig_fil_texten() {
        composeRule.setContent {
            FonderTheme {
                SettingsContent(
                    state = SettingsUiState(
                        backupMessage = BackupMessage.RestoreFailed(BackupFormatException.Reason.UNSUPPORTED_VERSION),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("nyare version av appen", substring = true).assertExists()
        composeRule.onNodeWithText("kunde inte läsas som en säkerhetskopia", substring = true).assertDoesNotExist()
    }

    @Test
    fun farozonen_hanvisar_till_export_i_stallet_for_att_saga_att_ingen_backup_finns() {
        // SET-1 sa tidigare "molnbackup finns ännu inte" — sedan SET-6 finns en väg tillbaka,
        // och texten ska peka på den innan användaren tömmer allt.
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState()) }
        }

        composeRule.onNodeWithText("exportera en säkerhetskopia först", substring = true).assertExists()
    }

    // --- Val av jämförelsefond (HEM-10, issue #102) ---

    @Test
    fun jamforelsekortet_visar_att_appen_valjer_nar_inget_eget_val_finns() {
        composeRule.setContent { FonderTheme { SettingsContent(state = SettingsUiState()) } }

        composeRule.onNodeWithText("Väljs automatiskt", substring = true).performScrollTo().assertIsDisplayed()
        // Ingen "använd appens val"-knapp när appens val redan gäller.
        composeRule.onNodeWithText("Använd appens val").assertDoesNotExist()
    }

    @Test
    fun jamforelsekortet_visar_det_egna_valet_och_gar_att_rensa() {
        var cleared = 0
        composeRule.setContent {
            FonderTheme {
                SettingsContent(
                    state = SettingsUiState(chosenBenchmarkName = "Global Index"),
                    onClearBenchmark = { cleared++ },
                )
            }
        }

        composeRule.onNodeWithText("Ditt val: Global Index").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Använd appens val").performScrollTo().performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun en_fond_utan_isin_ger_ett_felmeddelande_i_stallet_for_tyst_misslyckande() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(benchmarkPickFailed = true)) }
        }

        composeRule.onNodeWithText("saknar ISIN", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun valjknappen_anropar_callbacken() {
        var opened = 0
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(), onOpenBenchmarkPicker = { opened++ }) }
        }

        composeRule.onNodeWithText("Välj fond").performScrollTo().performClick()

        assertEquals(1, opened)
    }

    // --- Google-konto (TP-6) ---

    @Test
    fun utloggad_visar_logga_in_och_inte_logga_ut() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(googleUser = null)) }
        }

        composeRule.onNodeWithText("Inte inloggad").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Logga in med Google").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Logga ut").assertDoesNotExist()
    }

    @Test
    fun inloggad_visar_mejladressen_och_logga_ut() {
        composeRule.setContent {
            FonderTheme {
                SettingsContent(
                    state = SettingsUiState(
                        googleUser = AuthUser("uid-1", "Test Testsson", "test@example.com"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("test@example.com", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Logga ut").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Logga in med Google").assertDoesNotExist()
    }

    @Test
    fun anvandare_utan_mejladress_visas_anda_aldrig_som_tom_rad() {
        // Ett Google-konto utan läsbar mejladress ska ge en begriplig rad, inte "Inloggad som ".
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(googleUser = AuthUser("uid-1", null, null)))
            }
        }

        composeRule.onNodeWithText("ditt Google-konto", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun logga_in_knappen_anropar_callbacken() {
        var signIns = 0
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(), onSignIn = { signIns++ }) }
        }

        composeRule.onNodeWithText("Logga in med Google").performScrollTo().performClick()

        assertEquals(1, signIns)
    }

    @Test
    fun logga_in_knappen_ar_slackt_medan_inloggning_pagar() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(signInInProgress = true)) }
        }

        composeRule.onNodeWithText("Logga in med Google").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun ett_inloggningsfel_visas_med_sin_egen_text() {
        composeRule.setContent {
            FonderTheme {
                SettingsContent(
                    state = SettingsUiState(signInError = SignInException.Reason.NO_CREDENTIAL),
                )
            }
        }

        composeRule.onNodeWithText("Inget Google-konto", substring = true).performScrollTo().assertIsDisplayed()
    }

    // --- Molnbackup till Drive (SET-7) ---

    @Test
    fun visar_att_ingen_molnbackup_gjorts_an() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(lastDriveBackupEpochMillis = null)) }
        }

        composeRule.onNodeWithText("Ingen molnbackup har gjorts än.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun visar_tidsstampel_for_senaste_molnbackup() {
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(lastDriveBackupEpochMillis = 1710504000000L))
            }
        }

        composeRule.onNodeWithText("Senaste molnbackup:", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ingen molnbackup har gjorts än.").assertDoesNotExist()
    }

    @Test
    fun raden_om_saknad_tillatelse_visas_bara_nar_den_behovs() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(driveBackupNeedsAuth = true)) }
        }

        composeRule.onNodeWithText("behöver din tillåtelse", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sakerhetskopiera_till_drive_anropar_callbacken() {
        var backups = 0
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(), onBackupToDrive = { backups++ }) }
        }

        composeRule.onNodeWithText("Säkerhetskopiera till Drive").performScrollTo().performClick()

        assertEquals(1, backups)
    }

    @Test
    fun drive_knapparna_ar_slackta_medan_en_korning_pagar() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(backupInProgress = true)) }
        }

        composeRule.onNodeWithText("Säkerhetskopiera till Drive").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Återställ från Drive").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aterstall_fran_drive_kraver_bekraftelse_forst() {
        // Samma princip som filåterställningen: den ersätter all data, och frågan ska ställas
        // innan något händer — inte efteråt.
        var restores = 0
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState(), onRestoreFromDrive = { restores++ }) }
        }

        composeRule.onNodeWithText("Återställ från Drive").performScrollTo().performClick()
        assertEquals(0, restores)

        composeRule.onNodeWithText("Återställ från säkerhetskopia?").assertExists()
        composeRule.onNodeWithText("Återställ").performClick()
        assertEquals(1, restores)
    }
}
