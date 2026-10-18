---
name: android-projekt
description: Fonders anpassningar av de generella android:-skillsen från pluginen android@claude-android (android-dev, kotlin-coroutines, kotlin-flows, compose-expert, navigation-3, accessibility-compose, android-gradle-logic, firebase-auth, android-intent-security, mockup, implement-issue, refine-issue, release). Ladda den alltid tillsammans med en android:-skill – den säger vad som gäller just här och går före pluginens generella råd. Täcker projektets klasser och mönster (Room, DataStore, WorkManager, nätverkskällor), nuvarande avvikelser från pluginen, Gradle-uppsättningen, inloggningen som bara behövs för Drive-backupen, a11y för belopp, procent och diagram, mockup-processen med påhittad portföljdata, arbetsflödet från issue till PR (repo, lager, Room och backup-kedjan, byggare/arkitekt, kända dubbletter, nätverkskällor, /code-review, commit-områden, APK-länken), issuets repodelar (undersökningspunkter, testplan, Agent · modell · effort) och releasens repodelar (versionen i app/build.gradle.kts, release.yml, debug-snapshot, FORMAT_VERSION). Trigger-ord: Android, Kotlin, ViewModel, Hilt, Compose, coroutine, dispatcher, flöde, StateFlow, händelse, navigering, flik, Översikt, Innehav, Beslut, Händelser, tillgänglighet, TalkBack, belopp, procent, vinst, förlust, Gradle, beroende, Room, DataStore, WorkManager, kursuppdatering, inloggning, logga in, logga ut, Drive, google-services, SHA-1, manifest, Intent, PendingIntent, mockup, skiss, ny skärm, ny komponent, Design-canvas, issue, genomför, implementera, kör #N, kör nästa, förfina, planera, PR, commit, granskning, agent, byggare, arkitekt, modell, effort, release, släpp, ny version, versionshöjning, versionName, versionCode, release.yml, debug-snapshot, bygg APK, signerad APK.
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
  anropar ViewModelns publika funktioner (`viewModel::signOut`, `onMessageShown`); åtta compose-rules-regler som kräver API-ändringar i befintliga komponenter är avstängda i ktlint (`parameter-naming`, `lambda-param-event-trailing`, `lambda-param-in-effect`, `content-slot-reused`, `multiple-emitters-check`, `modifier-naming`, `modifier-missing-check`, `param-order-check`) och `vm-injection-check` för att dess autorättning bryter `AppNavigation` (två `hiltViewModel()`); ingen Renovate eller Dependabot;
  ingen Baseline Profile. `material3` står på `1.5.0-beta01` utanför Compose BOM, vilket följer
  pluginen (beta), men utan kommentar i katalogen om varför. Tas i egna PR:er senare; ny kod
  följer pluginen där det går utan att bryta mot grannkodens mönster.

## Coroutines (android:kotlin-coroutines)

- Room:s suspend-DAO:er och DataStore är main-safe – ingen `withContext` runt dem.
- OkHttp-anropen i `data/network/` blockerar och måste köras utanför huvudtråden; Jsoup-
  tolkningen i `FundPriceRepository` är CPU-arbete.
- Dispatchers: `di/DispatchersModule` ger `@IoDispatcher`, `@DefaultDispatcher` och
  `@MainDispatcher` (WebView i `FondlistaWafToken`); bara `di/` nämner `Dispatchers.`, vilket
  scopet `[dispatchers]` i `ui-forbidden.txt` kontrollerar i `UiConsistencyTest` och hooken.
  Filläsningen i importen går via `data/imports/ImportFileReader`, inte i composablen.
  Enhetstester ger `UnconfinedTestDispatcher()` eller `StandardTestDispatcher(testScheduler)`.

## Flöden (android:kotlin-flows)

- `AuthRepository.currentUser: Flow<AuthUser?>` – `callbackFlow` på
  `FirebaseAuth.AuthStateListener` i `FirebaseAuthRepository`, seedad med `trySend` innan
  lyssnaren registreras (annars fryser `SettingsViewModel`s `combine` på sitt startvärde).
- `PreferencesRepository`: DataStore-flöden (`themeMode`, `lastBackup`, `driveBackupNeedsAuth`,
  `riskProfile`, `benchmark` …) ur ett gemensamt `dataStore.data.catch { … }`.
- Room-DAO:ernas `observe…(): Flow<List<…>>` är dataflödena; repositories exponerar dem.
- ViewModels: `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`.
- Händelser från en ViewModel är fält i tillståndet som skärmen kvitterar (pluginen, Googles råd):
  meddelanden (`SwitchWatchViewModel.onMessageShown()`, `backupProgress.message = null` i
  `SettingsViewModel`), navigering efter sparat (`saved` + `onSavedHandled()` i `RiskProfilViewModel`
  och `TransactionFormViewModel`) och en nystartad bevakning (`startedSwitchWatchId` +
  `onSwitchWatchOpened()` på Beslut, Händelser och Fonddetalj). Skärmen agerar i en `LaunchedEffect`
  nyckelad på värdet och kvitterar efteråt, så att inget upprepas vid rotation eller tappas när
  skärmen inte visas. Ingen `Channel` eller `receiveAsFlow` i `ui/` – scopet `[events]` i
  `ui-forbidden.txt` kontrollerar det; inget `SharedFlow` heller. Fonder har inget delat
  formulärmönster (`EditorState`). `SwitchWatchUiState.closed` är ett bestående tillstånd, ingen
  händelse: skärmen lämnas med `popIfTop`, som inte gör något om skärmen redan lämnats.
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

Versioner och SDK-nivåer: KRAVLISTA TP-1 (minSdk 30, targetSdk 36, compileSdk 37) och
`gradle/libs.versions.toml` (AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.1). En höjning som ändrar
SDK-nivåer uppdaterar TP-1 i samma PR.

- En modul (`:app`), ingen `build-logic` och inga convention plugins – rotens `build.gradle.kts`
  har bara `apply false`-deklarationerna, Spotless och `cpdCheck`.
- Plugins i `:app`: `android.application` (AGP 9, inbyggd Kotlin), `kotlin.compose`, `ksp`,
  `hilt`, `kotlin.serialization`, `google-services`, `roborazzi`.
- Java 17 utan toolchain: `sourceCompatibility`/`targetCompatibility` 17 och `jvmTarget` 17 i
  `app/build.gradle.kts`. JDK 21 kör bygget lokalt och i alla CI-jobb (`.github/actions/setup-android`),
  eftersom Robolectric kräver 21 för SDK 36.
- KSP för Room (`room.schemaLocation` = `app/schemas`, även som `androidTest`-assets för
  migreringstesterna) och Hilt.
- `cpdCheck` (PMD CPD via CLI som `JavaExec`, 80 tokens, baslinjen `config/cpd-baseline.txt`)
  över `app/src/main`, `app/src/test`, `app/src/androidTest`, kopplad till `check`.
- Kodstil: ktlint 1.8 (stil `android_studio`) med compose-rules via Spotless, bara lokalt och inte i CI (regel 5). Kör `./gradlew spotlessApply` före push; `spotlessCheck` kontrollerar. Reglerna står i `ktlintRules` i rotens `build.gradle.kts`; nya CompositionLocals läggs i `compose_allowed_composition_locals`.
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
- **Länken** läggs i issuet under rubriken **Design** (skill `android:refine-issue`); koden skrivs
  först efter användarens ok (skill `android:implement-issue`).
- **I PR:en:** Roborazzi-skärmdumpar (ljust + mörkt, `captureLightAndDark`) av det byggda bredvid
  mockup-länken; referenserna spelas in lokalt med `./gradlew :app:recordRoborazziDebug` vid
  avsiktlig designändring och checkas in. Länka dessutom debug-snapshoten (CLAUDE.md → Bygg & test).

## Arbetsflöde (android:implement-issue)

Stegen står i pluginen; det här är Fonders delar.

- **Repo:** owner `ParTee71`, repo `fonder`, basbranch `master`. Issues och PR:er läses och
  skrivs med GitHub MCP.
- **Lokalt och i CI:** testerna körs lokalt före push när Android SDK finns i `/opt/android-sdk`
  (session-start-hooken säger om den gör det; annars `.claude/hooks/install-android-sdk.sh`).
  Går det inte får CI testa – säg det i PR:en. `android.yml` kör `build-and-test` på push och i
  PR som rör koden, och emulatorjobbet `instrumented-tests` **bara i PR** som rör koden det
  testar (skill `ci-budget`).
- **Syskonklasser att följa:** `PortfoljViewModel`, `TransaktionerViewModel`,
  `FundSearchViewModel`; skärmen är `XScreen(viewModel = hiltViewModel())` → tillståndslös
  `XContent` (avsnittet Compose ovan).
- **Kända dubbletter** (skill `shared-ui-components`): rör du `TotalCard`, `ComparisonChart`,
  importens laddningsdialoger eller en egen listrad – slå ihop den först, som egen commit.
- **Domänskills:** data → skills `data-safety-backup`, `room-migrations` och
  `android-data-layer`; diagram → skill `diagram`; loggning, nätverk och Drive → skill
  `data-privacy-security`.
- **Nätverkskällor:** `data/network/` (Avanza, Handelsbankens fondlista, Riksbanken) är
  odokumenterade källor (TP-10, TP-14); håll parsningen isolerad där och läck den inte in i
  repositories eller ViewModels.
- **Beräkningar** ligger i `domain/usecase/`, aldrig i ViewModel eller composable.
- **Lagerkedjan:** `Compose → ViewModel(StateFlow<UiState>) → Repository → Room/DataStore`, Hilt,
  fel mappas i repository-lagret. Skärmarna anropar ViewModelns publika funktioner – Fonder har
  inga sealed events (Baslinje ovan), så pluginens anti-mönster om publik `fun` gäller inte här.
- **Datakedjan** för ett persisterat fält eller en entitet, i samma ändring: `@Entity`, höjd
  `version`, `MIGRATION_N_N+1` i `MIGRATIONS` och `MigrationXYTest` (skill `room-migrations`);
  fältet i `BackupPayload` med default, i `export()`/`restore()`, fältvakten i
  `BackupSerializerTest` och en assertion i `BackupRoundTripTest` (skill `data-safety-backup`).
  Drive-backupen är en transport och rörs inte av ett nytt fält.
- **Loggning:** aldrig hela HTTP-svar, innehav, belopp, transaktioner eller tokens;
  `contentDescription` och 48 dp tryckyta för nya interaktiva element.
- **Testnivåer:** enhet i `app/src/test` (JUnit, MockK, Turbine, Robolectric, Roborazzi,
  `UiConsistencyTest`); Room, migrering, rundtur och Compose i `app/src/androidTest`.
- **Agenter:** huvudsessionen orkestrerar och granskar; agenten `byggare` tar skärmar,
  komponenter, use cases, nätverkskällor, workers och CI-filer, agenten `arkitekt` Room-schema,
  migreringar, backup-kedjan, DataStore och arkitekturval, `testskrivare` tester på tre eller
  fler nivåer, `granskare` diffen (steg 8) och `ci-doktor` rött CI. Går ett steg fel två gånger i
  rad flyttas det upp en nivå (`byggare` → `arkitekt`, Sonnet → Opus). Saknar issuet
  **Agent · modell · effort** kompletteras det (avsnittet Issues nedan).
- **`/code-review` krävs** (efter `granskare`) när ändringen rör `domain/usecase/`, datalagret,
  Room-schemat, backup-kedjan, en nätverkskälla eller en delad komponent (`ui/components`,
  `ui/diagram`), eller när diffen är större än ett par filer.
- **Commit-områden** efter flik, funktion eller lager, t.ex. `hem`, `portfolj`, `fond`,
  `fondsok`, `beslut`, `transaktioner`, `imports`, `facit`, `bytesfonster`, `riskprofil`,
  `settings`, `room`, `backup`, `network`, `ci`, `claude` – `feat(transaktioner): …`,
  `fix(portfolj): …`.
- **Kommandon:** CLAUDE.md → "Bygg & test" och skill `testing-strategy` → "Innan du anser dig
  klar". Manifest, resurser, proguard eller byggfiler ändrade → release-bygget och lint lokalt
  före push, i två Gradle-anrop (`./gradlew :app:assembleRelease` och sedan
  `./gradlew :app:lintDebug`); de körs inte i PR-flödet (skill `ci-budget`).
- **I PR:en:** krav-ID:n, testplanen (och vad som inte kunde köras lokalt), versionen
  (`versionName`/`versionCode` ur `app/build.gradle.kts`, eller "ingen höjning"), byggtidseffekten
  vid nya beroenden, och för GUI mockup-länken med Roborazzi-skärmdumpar ljust + mörkt
  (`captureLightAndDark`).
- **Emulatortesterna:** jobbet `instrumented-tests` körs i PR:er som rör filtret `instrumented`,
  och `ci-ok` fäller PR:en om det blir rött eller avbryts; ett överhoppat jobb fäller inte (skill
  `ci-budget`).
- **APK-länken:** har en körning gett en installerbar APK – debug-snapshoten
  (`release-debug.yml`, https://github.com/ParTee71/fonder/releases/tag/debug-snapshot), en
  signerad release eller en artifact – avslutas svaret med länken, commit och `versionName`
  (CLAUDE.md → Bygg & test). "Bygget är grönt" utan länk när en APK byggts är ett anti-mönster.

## Issues (android:refine-issue)

- **Repo:** owner `ParTee71`, repo `fonder`; dubblettsökning med `mcp__github__search_issues`
  (`repo:ParTee71/fonder <ord>`).
- **Extra undersökningspunkter:**
  - **Skärmar:** `ui/hem`, `ui/portfolj`, `ui/fond`, `ui/fondsok`, `ui/beslut`,
    `ui/transaktioner`, `ui/imports`, `ui/facit`, `ui/bytesfonster`, `ui/riskprofil`,
    `ui/settings` – och vilka ViewModels, repositories, use cases (`domain/usecase/`) och workers.
  - **Kända dubbletter:** `TotalCard`, `ComparisonChart`, importens laddningsdialoger, egna
    listrader – berörs en, slås den ihop först (skill `shared-ui-components`).
  - **Externa källor:** `data/network/` (Avanza, Handelsbanken, Riksbanken) är odokumenterade
    (TP-10, TP-14) – flagga skörheten.
  - **Import:** `data/imports/` (`HoldingsImportParser`, `AvrakningsnotaPdfParser`) och
    matchningen (`FundCompanyMatcher`, `FundNameMatcher`, `ImportFundMatcher`).
  - **Inloggning och molnbackup:** `AuthRepository` (TP-6), `DriveBackupRepository` (TP-7 steg 2,
    SET-7) och påminnelsen `BackupStatusRepository` (HEM-12).
- **Datakedjan i DoD:** entitet, `version`, migrering + `MigrationXYTest`; fältet i
  `BackupPayload` med default, i `export()`/`restore()`, fältvakten i `BackupSerializerTest`
  uppdaterad och `BackupRoundTripTest` asserterar på det – eller "ingen persisterad ändring", eller
  motivet till att det är cache.
- **Integritet och tillgänglighet i DoD:** logga aldrig innehav, belopp, tokens eller hela
  HTTP-svar; `contentDescription` och 48 dp där det är relevant.
- **Berörda filer** under `app/src/main/kotlin/se/partee71/fonder/…`.
- **Testplanens nivåer:** enhet i `app/src/test` (ViewModel med MockK/Turbine, Robolectric,
  Roborazzi-skärmdumpar, `UiConsistencyTest`); instrument i `app/src/androidTest` (Compose, DAO,
  migrering, rundtur; i Actions bara i PR som rör koden).
- **Verifiering**, exempel: `./gradlew :app:testDebugUnitTest --tests "*TransactionViewModel*"`,
  eller vilka Roborazzi-bilder som ändras.
- **Acceptanskriterier**, utöver pluginens: `- [ ] Migrering + MigrationXYTest` (om schemat
  ändras), `- [ ] Backup-kedjan + rundturstest` (om användardata), och alltid
  `- [ ] UiConsistencyTest och cpdCheck gröna`. Exempel på ett beteende:
  `- [ ] Sparar en transaktion och den finns kvar efter omstart`.
- **Titel**, exempel: `Transaktioner: bekräftelsedialog vid borttagning av rad`.
- **Etiketter:** repot har ingen fast lista här – fråga, och hämta repots verkliga etiketter om du
  är osäker.
- **Agent · modell · effort** (obligatoriskt). Modellval i [`.claude/README.md`](../../README.md);
  standarden är agentens frontmatter:

  | Agent | Tar | Standard |
  |---|---|---|
  | `byggare` | skärmar, komponenter, use cases, nätverkskällor, workers, CI-filer | Opus · medium |
  | `arkitekt` | Room-schema, migreringar, backup-kedjan, DataStore, arkitekturval | Opus · high (Fable vid väldigt komplexa issues) |
  | `testskrivare` | tester när tre eller fler nivåer berörs | Sonnet · medium |
  | `granskare` | granskningen i steg 8, alltid med | sessionens modell · medium |
  | `ci-doktor` | rött CI – startas vid behov, anges inte i förväg | Sonnet · medium |

  Room-schema, migreringar och backup-kedjan minst `arkitekt` · Opus · high. Exempel: `arkitekt` ·
  Opus · high – migrering och backup-kedjan; `byggare` · Opus · medium – skärmen.

## Release (android:release)

- **Versionsfilen** är `app/build.gradle.kts`: `versionCode = <n>` och
  `versionName = versionNameOverride ?: "<version>"`. Ändra bara strängen – `versionNameOverride`
  sätts enbart av workflowernas `-PversionNameOverride`. README har två ställen: versionsraden
  överst (`> Version: <version> …`) och raden under "Versionshistorik". Alla höjs och skrivs i
  funktions-PR:en.
- **Workflow:** `release.yml` (owner `ParTee71`, repo `fonder`), tre jobb (detaljer och kostnad i
  skill `ci-budget`):
  - `gate` (sekunder) stannar om commiten inte ligger på `master` eller om `ci-ok` i `android.yml`
    inte är grönt på exakt den commiten – enhetstester, skärmdumpar och `cpdCheck` körs alltså inte
    om. Löser taggen (push av tagg `v*`, eller `workflow_dispatch` med `publish_release` – då skapas
    taggen på den dispatchade commiten, och workflowen vägrar om den redan finns).
  - `instrumented` kör emulatortesterna (`instrumented.yml`) om de inte redan var gröna i en PR
    vars kod i filtret `instrumented` är densamma.
  - `release` avkodar keystoren `fonder.jks` ur `SIGNING_KEYSTORE_BASE64` (konverterad till legacy
    PKCS12), kör `:app:assembleRelease` (med `-PversionNameOverride` när `version_name` anges) och
    sedan `:app:lintDebug`, laddar upp artifacten `fonder-release`, publicerar GitHub Release
    `Fonder v<version>` med automatisk changelog och raderar keystoren.
- **Starta releasen först när `android.yml` är klart och grönt på `master`-HEAD** – annars stannar
  `gate`. Ett testbygge går bara från `master`.
- **Secrets:** `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
  `SIGNING_KEY_PASSWORD`. `app/google-services.json` är incheckad och behöver ingen secret.
- **Har `BackupPayload.FORMAT_VERSION` höjts sedan förra releasen:** nämn det tydligt – en äldre
  app avvisar då nya säkerhetskopior (skill `data-safety-backup`), och höjningen är major om
  äldre appar inte kan läsa formatet.
- **Kravlistan:** TP-kraven matchar SDK-nivåerna i `app/build.gradle.kts` (`compileSdk`,
  `minSdk`, `targetSdk`).
- **Dispatch:** `workflow_id: release.yml`, `ref: master`,
  `inputs: { version_name: "<version>", publish_release: "true" }` – `version_name` utan `v`,
  `publish_release` som sträng (standard `false`, valfri). Med bara `version_name` blir det ett
  testbygge: den signerade APK:n som artifacten `fonder-release`, ingen tagg och ingen Release –
  länka artifacten.
- **Debug-snapshot:** `release-debug.yml` (bara `workflow_dispatch`, valfri `version_name` →
  `-PversionNameOverride`) bygger en debug-APK, signerad med den incheckade `app/debug.keystore`,
  och publicerar den som rullande pre-release under taggen `debug-snapshot`, som skrivs över vid
  varje körning (artifact `fonder-debug`). Ingen versionshöjning, ingen tagg `v*`. Länka
  https://github.com/ParTee71/fonder/releases/tag/debug-snapshot med commit och `versionName`.
- **APK:n** heter `fonder-<versionName>-<tidsstämpel>.apk`; sammanfattningen börjar
  `Släppt: Fonder v<version>`.
- **Lokal reserv:** `./gradlew :app:assembleRelease` kräver `fonder.jks` i `app/` och
  signeringslösenord via `local.properties` (`signing.storePassword`, `signing.keyAlias`,
  `signing.keyPassword`) eller `SIGNING_*`-miljövariabler – saknas de byggs `release` osignerat.
  APK:n hamnar i `app/build/outputs/apk/release/`. Checka aldrig in keystoren (skill
  `data-privacy-security`).
