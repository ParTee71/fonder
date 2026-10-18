---
name: android-projekt
description: Fonders anpassningar av de generella android:-skillsen från pluginen android@claude-android (android-dev, kotlin-coroutines, kotlin-flows, compose-expert, navigation-3, accessibility-compose, android-gradle-logic, firebase-auth, android-intent-security, mockup). Ladda den alltid tillsammans med en android:-skill – den säger vad som gäller just här och går före pluginens generella råd. Täcker projektets klasser och mönster (Room, DataStore, WorkManager, nätverkskällor), nuvarande avvikelser från pluginen, Gradle-uppsättningen, inloggningen som bara behövs för Drive-backupen, a11y för belopp, procent och diagram, och mockup-processen med påhittad portföljdata. Trigger-ord: Android, Kotlin, ViewModel, Hilt, Compose, coroutine, dispatcher, flöde, StateFlow, händelse, navigering, flik, Översikt, Innehav, Beslut, Händelser, tillgänglighet, TalkBack, belopp, procent, vinst, förlust, Gradle, beroende, Room, DataStore, WorkManager, kursuppdatering, inloggning, logga in, logga ut, Drive, google-services, SHA-1, manifest, Intent, PendingIntent, mockup, skiss, ny skärm, ny komponent, Design-canvas.
---

# Android i Fonder

De generella skillsen kommer från pluginen `android@claude-android` och heter `android:<namn>`.
Pluginen är generell; det som bara gäller Fonder står här, en sektion per pluginskill.
**CLAUDE.md:s fem regler och repots egna skills går före båda.** Arkitekturen, paketträdet och
versionstabellen står i [README.md](../../../README.md); tekniska krav i
[KRAVLISTA.md](../../../KRAVLISTA.md) (TP-serien). Fonder har ingen Firestore och därför inga
security rules – `android:firebase-security-rules-auditor` gäller inte här.

## Baslinje (android:android-dev)

- En modul, `:app`. Paket under `app/src/main/kotlin/se/partee71/fonder/`: `data/` (auth,
  datastore, imports, network, repository, room) · `di/` · `domain/` (model, usecase – ren
  Kotlin utan Android-importer) · `ui/<feature>/` + `ui/components/` + `ui/diagram/` +
  `ui/theme/` · `worker/`.
- MVVM med `StateFlow<UiState>` (TP-3) – byt inte till MVI. `@HiltViewModel` på alla
  ViewModels; state som `MutableStateFlow` + `asStateFlow()`/`stateIn`, ändrat med `update {}`.
- Lagring: **Room** (`fonder.db`, `exportSchema = true`, schemat i `app/schemas/`, TP-4, skill
  `room-migrations`) och **DataStore Preferences** (`PreferencesRepository`) för inställningar.
  Repositories i `data/repository/`, Room-implementationer (`RoomTransactionRepository` m.fl.)
  bundna i `di/RepositoryModule`; repository → Room/DAO enligt skill `android-data-layer`.
- Nätverk: OkHttp + Jsoup (ingen Retrofit) i `data/network/` – Avanza, Handelsbankens
  fondlista, Riksbankens SWEA (TP-20, TP-21). Vad som får skickas: skill `data-privacy-security`.
- Fel mappas i repository-lagret till egna typer: `Result<T>` med `SignInException.Reason`,
  `BackupFormatException.Reason` och `DriveResult`; UI:t översätter orsaken till en svensk sträng.
- Backup: manuell, aldrig automatisk (HEM-12) – `BackupRepository` (fil via SAF, `BackupPayload`
  + `BackupSerializer`) och `DriveBackupRepository` (Drive `appDataFolder`), komponerade av
  anroparen; `BackupStatusRepository` driver påminnelsen på Översikt. Ingen backup-worker
  (TP-7, SET-6/7, skill `data-safety-backup`).
- Bakgrund: WorkManager bara för kursuppdateringen (TP-5) – `FundPriceUpdateWorker`
  (`@HiltWorker`) och `FundPriceRefreshScheduler` (unik periodisk backstop); manifestet stänger av
  standardinitieraren. Inga larm och inga notiser.
- Bilder: Coil 3 är deklarerad i katalogen men oanvänd i koden.
- Nav-argument tas redan med assisted injection (`@HiltViewModel(assistedFactory = …)`), som
  pluginen säger – `FondDetaljViewModel`, `TransactionFormViewModel`, `SwitchWatchViewModel`.
- Tester: JUnit, MockK, Turbine, Robolectric + Roborazzi i JVM, Konsist (`UiConsistencyTest`);
  Room- och Compose-tester även i `androidTest/` (skill `testing-strategy`).
- **Nuvarande läge, avviker från pluginen:** inga sealed events och ingen `onEvent()` – skärmar
  anropar ViewModelns publika funktioner (`viewModel::signOut`, `onMessageShown`); targetSdk 35
  (pluginen säger 36; compileSdk 37, TP-1); inga `ImmutableList` och ingen `@Immutable` i
  `UiState`; ingen Spotless/ktlint (ingen `.editorconfig`); ingen Renovate eller Dependabot;
  ingen Baseline Profile. `material3` står på `1.5.0-beta01` utanför Compose BOM, vilket följer
  pluginen (beta), men utan kommentar i katalogen om varför. Tas i egna PR:er senare; ny kod
  följer pluginen där det går utan att bryta mot grannkodens mönster.

## Coroutines (android:kotlin-coroutines)

- Room:s suspend-DAO:er och DataStore är main-safe – ingen `withContext` runt dem.
- OkHttp-anropen i `data/network/` blockerar och måste köras utanför huvudtråden; Jsoup-
  tolkningen i `FundPriceRepository` är CPU-arbete.
- **Nuvarande läge, avviker från pluginen:** `di/` har inga dispatcher-qualifiers, och
  `Dispatchers.` nämns direkt i produktionskoden: `data/network/AvanzaClient.kt`,
  `RiksbankFxClient.kt`, `HandelsbankenFondlistaClient.kt` (`withContext(Dispatchers.IO)`),
  `FondlistaWafToken.kt` (`Dispatchers.Main`), `data/repository/FundPriceRepository.kt`
  (`Dispatchers.Default`, fyra ställen), `DriveBackupRepository.kt` (`Dispatchers.IO`) och
  `ui/imports/ImportHoldingsScreen.kt`/`ImportOrdersScreen.kt` (`scope.launch(Dispatchers.IO)`
  i composablen, filläsningen). Repot ska migrera enligt pluginen (dispatcher injiceras bara där
  kod väljer en, och bara `di/` nämner `Dispatchers`) i en egen PR senare; ny kod följer pluginen.

## Flöden (android:kotlin-flows)

- `AuthRepository.currentUser: Flow<AuthUser?>` – `callbackFlow` på
  `FirebaseAuth.AuthStateListener` i `FirebaseAuthRepository`, seedad med `trySend` innan
  lyssnaren registreras (annars fryser `SettingsViewModel`s `combine` på sitt startvärde).
- `PreferencesRepository`: DataStore-flöden (`themeMode`, `lastBackup`, `driveBackupNeedsAuth`,
  `riskProfile`, `benchmark` …) ur ett gemensamt `dataStore.data.catch { … }`.
- Room-DAO:ernas `observe…(): Flow<List<…>>` är dataflödena; repositories exponerar dem.
- ViewModels: `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`.
- Engångsmeddelanden är redan state med kvittens, som pluginen säger: inget `Channel` eller
  `SharedFlow` i `app/src/main` (t.ex. `SwitchWatchViewModel.onMessageShown()`,
  `backupProgress.message = null` i `SettingsViewModel`).
- **Nuvarande läge, avviker från pluginen:** Drive-auktoriseringens `PendingIntent` lämnas via en
  callback-parameter (`backupToDrive(onNeedsAuthorization)`), inte via state – medvetet, så att den
  inte spelas upp igen vid rotation. Om den ska bli state med kvittens avgörs i en egen PR senare.

## Compose (android:compose-expert)

- Ordning: skill `shared-ui-components` (regel 4, katalogen i `ui/components/` och `ui/diagram/`)
  går före allt, sedan `ui-style` (nordisk blå, koppar, Space Grotesk, tabulära siffror), skill
  `diagram` för `FundLineChart` (Vico). Skärmdumpar och tester: `testing-strategy`.
- Tema: `FonderTheme` = `MaterialExpressiveTheme` + `MotionScheme.expressive()`, fast palett
  (`FonderLightColorScheme`/`FonderDarkColorScheme`, ingen dynamisk färg), `FonderTypography`,
  `FonderShapes`, `MonoAmountStyle` (tnum) och de semantiska `ReturnColors`, `StatusColors`,
  `ChartSeriesColors` – allt i `ui/theme/`.
- Fonder har inga delade list- eller redigeringsramar; skärmen är `XScreen(viewModel = hiltViewModel())`
  som samlar state med `collectAsStateWithLifecycle()` och lämnar typat state och callbacks till
  en tillståndslös `XContent` (t.ex. `SettingsScreen` → `SettingsContent`).
- Inga Material 3-komponenter, färger, former eller textstilar direkt i `ui/<feature>/` –
  `ui-forbidden.txt`, hooken `regel4-check` och `UiConsistencyTest` fäller.

## Navigation 3 (android:navigation-3)

Googles referens är generell. Här gäller `ui/navigation/`: `@Serializable sealed interface AppKey : NavKey`
i `AppKeys.kt` (flikarna `OversiktKey`, `InnehavKey`, `BeslutKey`, `HandelserKey`; argument i
nyckeln, t.ex. `FondDetaljKey(fundId)`), `AppBackStack` med en stack per flik som sparas med
`rememberSaveable` (`AppBackStack.Saver`), skärmarna kopplade i `fonderEntries`
(`AppNavigation.kt`) med `push`, `select` och `popIfTop(key)` – aldrig från ViewModeln – och
övergångarna bara i `Transitions.kt`. Inställningar och Facit är skärmar i stacken, inte flikar
(NAV-7); flikraden är `AppFloatingToolbar` (UI-14).

## Tillgänglighet (android:accessibility-compose)

Krav: UI-3 (vinst/förlust aldrig färg ensam), UI-5 (skrollbart), UI-6 (värdet kapas aldrig),
UI-10 (risknivå i text). Semantiken ligger i komponenten, aldrig hos anroparen.

- Belopp och procent via `MoneyFormat` (`kr`, `percentSigned`, `percent`); etikett + värde i
  `PeriodRow` och `ValueAsOfRow` är merge:ade till en nod.
- `ReturnColors.forAmount(...)` färgar, tecknet (+/−) bär informationen; `StatusColors` har
  rubrik och triggertexter (ANA-3); `RiskBadge` skriver "Risk 5/7" och läses "Risknivå 5 av 7".
  Kontrollera i båda temana – vinst/förlust har egna ljusa och mörka toner.
- `FundLineChart` sätter en `contentDescription` med period, antal punkter, lägsta och högsta
  värde och i procentläge kurvornas namn (`format_fond_chart_description*` i `strings.xml`).
- `WorkerStatusIcon` och väntesnurran säger vilket kort som uppdateras (NAV-6).
- Periodknapparna och `ChoiceChipRow` följer M3:s knappstorlek och läses som radioval;
  `RiskLevelPicker` använder `minimumInteractiveComponentSize()`.
- `ExpandableSection`, `ExpandableInfoRow` och `FollowedToggleRow` kommunicerar läget.
- Belopp i `MonoAmountStyle` behåller kolumnerna vid stor text; etiketten kapas, värdet aldrig.
- Tester: Robolectric i `app/src/test` (`EmptyStateTest`, `ChoiceChipRowTest`) och instrument i
  `app/src/androidTest` (`RiskBadgeTest`, `CardTitleRowTest`, `WorkerStatusIconTest`). Det finns
  inget komponentgalleri med `fontScale = 1.3` än; komponenternas skärmdumpar ligger i
  `ComponentScreenshotTest`.

## Gradle (android:android-gradle-logic)

Versioner och SDK-nivåer: KRAVLISTA TP-1 (minSdk 30, targetSdk 35, compileSdk 37) och
`gradle/libs.versions.toml` (AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.1). En höjning som ändrar
SDK-nivåer uppdaterar TP-1 i samma PR.

- En modul (`:app`), ingen `build-logic` och inga convention plugins – rotens `build.gradle.kts`
  har bara `apply false`-deklarationerna och `cpdCheck`.
- Plugins i `:app`: `android.application` (AGP 9, inbyggd Kotlin), `kotlin.compose`, `ksp`,
  `hilt`, `kotlin.serialization`, `google-services`, `roborazzi`.
- Java 17 utan toolchain: `sourceCompatibility`/`targetCompatibility` 17 och `jvmTarget` 17 i
  `app/build.gradle.kts`, samma som CI:s JDK 17.
- KSP för Room (`room.schemaLocation` = `app/schemas`, även som `androidTest`-assets för
  migreringstesterna) och Hilt.
- `cpdCheck` (PMD CPD via CLI som `JavaExec`, 80 tokens, baslinjen `config/cpd-baseline.txt`)
  över `app/src/main`, `app/src/test`, `app/src/androidTest`, kopplad till `check`.
- `gradle.properties`: `org.gradle.caching` och `org.gradle.parallel` påslagna;
  configuration cache är inte påslagen (skill `ci-budget`).
- `app/google-services.json` är **incheckad** och `google-services` appliceras alltid; utan filen
  fäller pluginet bygget (ingen `authStub`). Uppsättningen: [docs/GOOGLE-SETUP.md](../../../docs/GOOGLE-SETUP.md).
- Release minifieras med keep-regler i `app/proguard-rules.pro` (skill `data-privacy-security`).
- Nytt beroende: byggtidseffekten anges i PR:en (skill `ci-budget`).

## Inloggning (android:firebase-auth)

Inloggningen är **valfri** och bär ingen data (TP-6): den är bara förutsättningen för
Drive-backupen (TP-7 steg 2, SET-7). Ingen spärr före appen, ingen användarrot, inget
`schemaVersion` per användare.

- Kontraktet i `data/auth/`: `AuthUser(id, displayName, email)`, `SignInException.Reason`
  (`CANCELLED`, `NO_CREDENTIAL`, `FAILED`) och `AuthRepository` med `currentUser`,
  `signInWithGoogle(activityContext)` och `signOut()`; `FirebaseAuthRepository` bunden i
  `di/RepositoryModule`.
- Flödet: `SettingsScreen` → `SettingsViewModel.signIn(activityContext)` (dubbeltrycksspärr) →
  `AuthRepository`. `CANCELLED` blir inget felmeddelande; ett fel som inte är en
  `SignInException` faller tillbaka på `FAILED`. Täckningen ligger i `SettingsViewModelTest`
  med en fejkad `AuthRepository`; `FirebaseAuthRepository` testas inte.
- Drive-scopet `drive.appdata` begärs **skilt från inloggningen** via
  `Identity.getAuthorizationClient`, först när backupen används; kontot tas som hint ur
  `currentUser`. Utloggad svarar `DriveBackupRepository` `DriveResult.NoAccount`, som ger ett
  eget meddelande. Ingen worker kräver inloggning (Drive-workern är borttagen, SET-7).
- Sessionen ingår inte i backup-kontraktet (NFR-1), och Auto Backup-reglerna
  (`backup_rules.xml`, `data_extraction_rules.xml`) är include-listor med bara `fonder.db` och
  `datastore` – lägg aldrig till hela filkatalogen.
- `google-services.json`: två `client_type: 1` (debug + release SHA-1) och en `client_type: 3`.
  Fonder är andra appen i Firebase-projektet `dagboken-711d2`; `appDataFolder` delas därför med
  Dagboken, och varje listning filtrerar på prefixet `fonder-backup-`.
- **Nuvarande läge, avviker från pluginen:** `currentUser` heter inte `authState`, `AuthUser`
  har `id`/`displayName` och inget foto, `signOut()` returnerar `Unit`, och utloggningen går
  direkt från kontokortets knapp via `SettingsViewModel.signOut()` – utan eget use case och utan
  `ConfirmDialog` (inget går förlorat, sessionen bär ingen data). Ändras i så fall i en egen PR
  senare.

## Intent-säkerhet (android:android-intent-security)

Bara `MainActivity` (launcher) är exporterad; `InitializationProvider` är `exported="false"`.
Filer väljs och skrivs via SAF (`OpenDocument`, `CreateDocument`) i Inställningar och
importskärmarna, och Drive-auktoriseringen startas som Googles egen `PendingIntent` via
`StartIntentSenderForResult`. Appen har inga notiser, ingen `FileProvider` och tar inte emot
delningar; tillkommer något sådant granskas det med pluginskillen och skill `data-privacy-security`.

## Mockup (android:mockup)

Processen (när, hur, visa och vänta) står i pluginen; det här är Fonders delar.

- **Mallen:** det finns **ingen gemensam canvas än**. Den första mockupen blir mallen: ett
  komponentark med katalogen i `ui/components/` och `ui/diagram/` (skill `shared-ui-components`)
  i ljust och mörkt. Länken skrivs då in här och i CLAUDE.md:s processregel. Fonder har inga
  list- eller redigeringsramar, så beteendearket visar de tillstånd som finns: laddar
  (väntesnurran i rubrikraden, NAV-6), tomt (`EmptyState`), fel, okänd data ("Risk okänd", ANA-4)
  och lång text som kapas (UI-6).
- **Facit är det byggda:** `ui/theme` och skärmdumparna i `app/src/test/screenshots/`.
- **Byggs av** befintliga komponenter (`CardTitleRow`, `PeriodRow`, `RiskBadge`, `EmptyState`,
  `FundLineChart` …) i designspråket i skill `ui-style`: nordisk blå, sparsam koppar, M3
  Expressive, Space Grotesk, belopp med tabulära siffror, vinst/förlust med tecken och färg.
  Mörkt tema när det spelar roll: vinst/förlust, diagramkurvor och koppar byter ton.
- **Data:** riktiga svenska texter och påhittad men realistisk portföljdata – fem–sju fonder med
  trovärdiga namn och fondbolag, risknivå 1–7, innehav på några tusen till några hundra tusen
  kronor, avkastning med både plus och minus, en kurshistorik med köpmarkörer, ett bytesförslag.
  Belopp som "12 345,67 kr", "+4,2 %", "−1,8 %". **Aldrig användarens riktiga innehav, belopp
  eller transaktioner** – inte heller avrundade eller "lite ändrade".
- **Tumregel:** padding, ordval, ikonbyte eller färgjustering inom temat → ingen mockup;
  skärmdumpsdiffen i PR:en räcker.
- **Länken** läggs i issuet under rubriken **Design** (skill `refine-issue`); koden skrivs först
  efter användarens ok (skill `implement-issue`).
- **I PR:en:** Roborazzi-skärmdumpar (ljust + mörkt, `captureLightAndDark`) av det byggda bredvid
  mockup-länken; referenserna spelas in lokalt med `./gradlew :app:recordRoborazziDebug` vid
  avsiktlig designändring och checkas in. Länka dessutom debug-snapshoten (CLAUDE.md → Bygg & test).
