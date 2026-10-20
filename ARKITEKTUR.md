# Arkitektur – Fonder

> Beslut och struktur för appen. Regler för hur koden skrivs finns i [CLAUDE.md](CLAUDE.md)
> och skills i [`.claude/skills/`](.claude/skills/). Krav finns i [KRAVLISTA.md](KRAVLISTA.md).
> Den här filen är enda källan för paketträd, dataflöde och datamodell; andra filer länkar hit.

Innehåll:
1. [ADR-001: Room och Drive-backup](#adr-001-room-och-drive-backup)
2. [Moduler och paket](#moduler-och-paket)
3. [Dataflöde](#dataflöde)
4. [Datamodell](#datamodell)
5. [Verifierade API:er](#verifierade-apier)
6. [Byggkonfiguration](#byggkonfiguration)

---

## ADR-001: Room och Drive-backup

**Status:** gällande. Nedskriven i efterhand 2026-10-20 (#170); beslutet är äldre än filen.

### Kontext

Fonder håller koll på en persons fondinnehav: kurser, transaktioner, värde och utveckling. Den
används av en person på en Android-enhet. Innehav, belopp och transaktioner är ekonomisk
persondata (skill `data-privacy-security`). Systerapparna ReseApoteket och Dagboken bygger på samma
stack; ReseApoteket valde Cloud Firestore (dess `ARKITEKTUR.md`, ADR-001) eftersom ett hushåll
delar data och databasen ska nås från en Claude-session.

### Beslut

- **Room** är den lokala databasen och **DataStore** håller inställningarna. Appen fungerar helt
  offline; nätverket behövs bara för kurser, växelkurser, fondmetadata och backupen.
- **Backup** är en JSON-fil (`BackupPayload`, `formatVersion`) till en lokal fil (SET-6) eller till
  Google Drive `appDataFolder` (SET-7). Rundturen backup → restore täcks av tester (regel 1, skill
  `data-safety-backup`).
- **Schemaändringar** görs med Room-migreringar och `MigrationXYTest`, aldrig destruktivt (skill
  `room-migrations`).
- **Inloggning** (Firebase Auth + Credential Manager) behövs bara för Drive-backupen.

### Konsekvenser

- Fonder behöver inget som motsvarar ReseApotekets Firestore, säkerhetsregler eller `tools/db`;
  databasen nås inte från en session, och testdata är alltid påhittad.
- Varje persisterat fält måste in i backup-kedjan och i en migrering i samma ändring.
- Byter Fonder någon gång till en delad molndatabas skrivs det som en ny ADR här.

---

## Moduler och paket

En modul, `:app`. Att flytta `domain/` till en ren JVM-modul `:core` som i ReseApoteket är #171.

Paket under `app/src/main/kotlin/se/partee71/fonder/`:

Paket under `app/src/main/kotlin/se/partee71/fonder/`:

```
data/
├── auth/         AuthRepository + FirebaseAuthRepository (Google-inloggning via Credential Manager, TP-6/#106)
├── common/       DataError + DataError.of (Room/SQLite, källorna, importerna, inloggningen, backupen, Drive) ·
│                 AppErrors (android-ui:s ErrorMapper, den enda felmappningen, NFR-6/#173) · suspendRunCatching
├── datastore/    PreferencesRepository (tema, riskprofil #68 m.m.)
├── network/      HandelsbankenFondlistaClient + HandelsbankenHtmlParser (kurskälla, #2/#3) ·
│                 AvanzaClient + AvanzaJsonParser + AvanzaPriceSource (ISIN-baserad historik, #7-uppföljning) ·
│                 AvanzaFundListParser + AvanzaFundListRequestBuilder (sökbar fondmetadata, #57) ·
│                 RiksbankFxClient + RiksbankJsonParser (dagsnoterade växelkurser, #43)
├── imports/      HoldingsImportParser (Excel-innehav, #8) · AvrakningsnotaPdfParser + PdfTextExtractor
│                 (PDF-avräkningsnotor, flera filer samtidigt, #8-uppföljning)
├── repository/   TransactionRepository (Room) · FundPriceRepository (Handelsbanken + ISIN-källkedja) ·
│                 FundMetadataRepository (fondmetadata + Handelsbanken-köpbarhet + persisterad
│                 billigare-alternativ-jämförelse + kända risknivåer, #57/#61/#68) ·
│                 BackupRepository/LocalBackupRepository (fil, SET-6) · DriveBackupRepository (moln, SET-7) ·
│                 SwitchWatchRepository (pågående byte mellan sälj och köp, ANA-12/#114) ·
│                 SuggestionDismissalRepository (avstådda bytesförslag per fondpar, HEM-14)
└── room/         AppDatabase (v19) · entities (inkl. FxRateEntity, FundMetadataEntity, SwitchWatchEntity,
                  SuggestionDismissalEntity) · daos (inkl. FxRateDao, FundMetadataDao, SwitchWatchDao, SuggestionDismissalDao)
di/               Hilt-moduler (AppModule, StorageModule – Room-filen och DataStore, NetworkModule, RepositoryModule)
domain/
├── model/        Fund (fundId, valfritt isin/fondlistaFundId) · FundCompany · FundCatalog · Transaction (inkl. fee) · FundPrice ·
│                 IsinPricePoint · ImportedHoldingRow · ImportedOrderTransaction · Holding · RiskProfile/RiskProfileAnswers (#68)
└── usecase/      PortfolioCalc · PortfolioPerformanceCalc (dag/vecka/månad, #14) ·
                  FundAnalysisCalc (nyckeltal + säljsignaler per innehav, #16) ·
                  RealizedGainCalculator (delad FIFO-motor, realiserat + kvarvarande resultat, #10) ·
                  MoneyFormat · SwedishNumberFormat · FundCompanyMatcher (kärnnamn för bolagsledtråd) · FundNameMatcher ·
                  PurchaseDateEstimator · ImportFundMatcher (delad matchningsordning, regel 4) ·
                  CurrencyConverter (fondlistas kurser → kronor, #43) · TransactionFormValidator ·
                  ChartPeriodFilter (kursdiagrammets periodväljare, #51) ·
                  PurchaseMarkerFilter (köpmarkörer i diagrammet, #55) ·
                  MovingAverageCalc (200-dagarssnittet för S2 och diagrammets snittlinje, ANA-14) ·
                  FundScreenFilter + FundMetadataFreshness (fondmetadata-frågor, #57) ·
                  FeeComparisonCalc (billigare alternativ, ANA-9, #59) ·
                  PortfolioFeeCalc (portföljens totala fondavgift + samlade besparingspotential, HEM-5/HEM-6, #60/#61) ·
                  PortfolioExposureCalc (exponeringskarta: fondtyp/region/index-aktivt, POR-9, #66) ·
                  RiskProfileCalc (målrisknivå ur enkätsvar, SET-3, #68) ·
                  PortfolioRiskCalc (innehavens värdeviktade risknivå, HEM-7, #68) ·
                  SwitchWatchCalc (utveckling sedan säljdagen för bevakade alternativ, ANA-12, #114) ·
                  SwitchPlanInputs (portföljläget bytesplanen och målnivåns förval räknas på: innehav med
                  värde, metadata, likvid på väg — en läsväg för bakgrundskörningen och förvalet, BYT-3) ·
                  TargetLevelPreselection (förvald målnivå för en fristående bevakning ur riskprofilen,
                  `SwitchPlanCalc.preselectTargetLevel`, SLD-5/BYT-2/ANA-13, #150) ·
                  RecordTransaction (den enda skrivvägen för transaktioner, och bytets livscykel: `Entry` med
                  `SwitchDecision`, bevakning ur sälj/planrad, avslut med köptransaktionen, koppling från
                  annan dag efter fråga, och en begärd omräkning av planen per skrivning, BYT-2/BYT-3, #143/#144) ·
                  SwitchTransitions + SwitchChoicesSource (bytesväljarens alternativ och förval, `SwitchDecision`,
                  och vilken okopplad bevakning från en annan dag ett sälj kan kopplas till, BYT-2, #144) ·
                  SwitchQuestions (väljarens frågor i formuläret och PDF-importen: Följ, "Köpte den här",
                  fråga om annan dag, köp som avslutar en säljrads bevakning i samma import, BYT-2, #144) ·
                  PendingDecisionsSource (bytesplan + billigare alternativ + pågående byten och deras antal,
                  en källa för Beslut, Översikten och flikmärket, NAV-9/HEM-13) ·
                  CheaperAlternatives (inspelade avgiftsbyten som förslag på Beslut, ANA-9) ·
                  SuggestionDismissals (vilka förslag ett avstående döljer och hur länge, HEM-14) ·
                  FacitSummary (facit ur cachen, en källa för Facit och Beslut-flikens resultat, SET-5/NAV-9)
ui/
├── hem/          HemScreen + ViewModel (Översikt: värde, "Att besluta" HEM-13, avkastning mot index,
│                 dag/vecka/månadsresultat, analys-summeringskort #16, fondavgiftskort HEM-5/HEM-6,
│                 riskkort mot riskprofilens målfördelning HEM-7)
├── beslut/       BeslutScreen + ViewModel (fliken Beslut: pågående byten, bytesplanen och billigare alternativ med Följ/Avstå, vägen till Facit, NAV-9/HEM-14)
├── facit/        FacitScreen + ViewModel (utfallet av inspelade förslag, skärm i stacken under Beslut, SET-5)
├── portfolj/     PortfoljScreen + ViewModel (exponeringskarta: fondtyp/region/index-aktivt, POR-9, #66)
├── transaktioner/TransaktionerScreen + ViewModel (fliken Händelser: filter Alla/Köp/Sälj, årets realiserade
│                 resultat, sälj med realiserat resultat per rad, #10) · TransactionFormScreen (EntityEditScreen) + ViewModel (registrera köp/sälj, avgift, sälj alla, UI-15)
├── fond/         FondDetaljScreen + ViewModel (bytesbeslut överst: bytesplan + billigare alternativ med
│                 jämförelsediagram, ANA-10/ANA-11/#85 · kurshistorik i diagram sedan första köpet, #7, med 200-dagarssnitt, ANA-14 ·
│                 hopfälld Analys-sektion med nyckeltal/säljsignaler, #16 · billigare alternativ, ANA-9/#59)
├── fondsok/      FundSearchScreen + ViewModel (sök hela plattformens katalog, filtrera per fondbolag via källan, lägg till fond)
├── imports/      ImportHoldingsScreen + ViewModel (Excel-innehav, #8) · ImportOrdersScreen + ViewModel
│                 (PDF-avräkningsnotor, #8-uppföljning)
├── settings/     SettingsScreen + ViewModel (tema, kursuppdatering, riskprofil-ingång SET-3/#68 …)
├── riskprofil/   RiskProfilScreen (EntityEditScreen) + ViewModel (enkät + målfördelning, SET-3, UI-15)
├── bytesfonster/ SwitchWatchScreen + ViewModel (pågående byte: jämförelse av bevakade alternativ mellan
│                 sälj och köp, ANA-12/ANA-13, #114)
├── common/       DataErrorMessage (DataError.toMessage(), felens svenska texter) · ListStates (ListUiState-hjälpare:
│                 itemsOrEmpty, errorOrNull, catchAsListError – NFR-6/#173) · DetailStates (DetailUiState-
│                 hjälpare: valueOrNull, errorOrNull, catchAsDetailError)
├── navigation/   AppNavigation (Navigation 3: NavDisplay, en dekorerad stack per flik) · AppBackStack (stack per
│                 flik, sparas över rotation och processdöd) · AppKeys (@Serializable NavKey per skärm) · Screen ·
│                 Transitions · DecisionBadgeViewModel (Beslut-flikens märke, NAV-9)
├── components/   Delade komponenter (EmptyState, SelectField, DateField, PeriodRow, AnalysisStatusBanner/StatusDot,
│                 ExposureBar — proportionell radlista, POR-9/#66 · ChoiceChipRow — val-chiprad, #68 ·
│                 ExpandableSection/ExpandableInfoRow — utfällning, #22/#85 · RiskBadge — risknivå 1–7, UI-10/#85 …)
├── diagram/      Delade diagram (FundLineChart — en eller flera indexerade serier, ANA-11/#85)
└── theme/        Fonders värden till android-ui:s AppTheme: palett, former, Space Grotesk-typskala, ReturnColors/StatusColors (#16, #172)
worker/           FundPriceUpdateWorker (daglig kursuppdatering + inkrementell jämförelseifyllnad, HEM-6/#61 ·
                  kandidatkurser för pågående byten, HEM-11/#114)
```

**Domän → worker, ett medvetet undantag:** `RecordTransaction` (domän) beror på
`FundPriceRefreshScheduler` (worker) för att begära en omräkning av bytesplanen efter varje
transaktion (BYT-3, #144). Omräkningen är en del av vad en transaktion *betyder* — hade varje
ViewModel själv behövt minnas den hade en glömd begäran lämnat planen föreslående ett sälj för ett
gap som redan är fyllt. Beroendet går mot gränssnittet, aldrig mot WorkManager, och testerna
använder `FakeFundPriceRefreshScheduler`.

---

## Dataflöde

```
Compose → ViewModel (StateFlow<UiState>) → Repository → Room / DataStore
                ↑ ListUiState, DetailUiState, EditorState, UiError (android-ui:s ui-common) ← AppErrors ← DataError (data/common)
```

**Tillstånd och fel (NFR-6, #173):** listskärmarnas tillstånd (Händelser, Beslut, Facit, Fondsök,
Portfölj) har listan som ett `ListUiState<T>`-fält från android-ui:s `ui-common` – laddning, tomt, fel
eller innehåll – bredvid skärmens övriga fält (filter, summeringar, körstatus). Ett fel i källan
fångas med `catchAsListError` och blir `ListUiState.Error` i stället för en krasch. Felmappningen
finns på ett ställe: `DataError.of` översätter Room/SQLite till `Database`, ett I/O-fel från en källa
till `Network` och resten till `Unknown`; importerna sätter sina egna (`ImportUnreadable`,
`ImportEmpty`, `ImportNoTransactions`). `AppErrors` gör ett `UiError` av det med texten ur
`DataError.toMessage()`, och skärmen visar det med `EmptyState(error)`. Fondsök sätter
`DataError.Network` när katalogen inte kom (källan svarar `null`) med sin egen text.

Detaljskärmarna (Översikt, Fonddetalj, Pågående byte) har det lästa som ett `DetailUiState<T>`-fält
(`HemPortfolio`, `FundDetail`, `SwitchWatchDetail`) bredvid körstatus, förslag och engångshändelser;
`catchAsDetailError` sist i flödet gör ett fel i källan till `DetailUiState.Error`, och skärmen visar
`EmptyState(error)` före allt annat. Getters (`content`, `loading`, `error`) håller skärmkoden enkel;
`content` är ett standardvärde bara för de grenar som aldrig nås vid fel. En bevakning som inte finns
blir `DataError.NotFound` med Pågående bytes egen text. Riskprofilen och transaktionsformuläret går via `EditorState` +
`EditorLoader` och visas i android-ui:s `EntityEditScreen` (läsfel med Försök igen, sparfel som snackbar, "Släng ändringar?", UI-15). Inloggningens, backupens och Drive-svarens fel (`SignInException.Reason`,
`BackupFormatException.Reason`, `DriveResult`) översätts med `DataError.of` till egna `DataError` med
oförändrade texter, och Inställningar visar `UiError.message`. `suspendRunCatching` gör ett `Result`
av en suspend-åtgärd utan att svälja avbrott. Transaktionsformuläret läser fonderna (och för en
förifylld nyckel fond, kurs och andelar) med `EditorLoader`, sparas oförändrat förifyllt
(`saveUnchanged`), och det tredje utfallet – sparat men kopplingen misslyckades – är ett valideringsfel
som släcker Spara och visas som ramens `formError`. Inställningar använder inte `EditorState`: de sparar
varje reglage direkt utan utkast eller Spara. `shownAfter`
används inte – den skulle fördröja dagens laddningsindikatorer.

Repository är single source of truth. `FundPriceRepository` hämtar och cachar riktiga
kurser från `handelsbanken.fondlista.se` (se issue #2/#3 för källbeslut och risknotis —
odokumenterad, inofficiell HTML-källa). Källan har **inget datumtak**: hela historiken
sedan första köpet kommer i ett anrop, men eftersom svaret kan bli flera megabyte hämtas
det bara som backfill — när cachen inte redan når tillbaka så långt räcker ett kort färskt
fönster (KRAVLISTA TP-18, issue #37). Räcker inte fondlista provas en prioritetsordnad
lista av `IsinPriceHistorySource` som reserv — i dag bara Avanzas odokumenterade fond-API
(`AvanzaPriceSource`, samma riskprofil, se KRAVLISTA TP-14); den täcker fonder som saknas i
katalogen och därför identifieras med sitt ISIN. `BackupRepository` och `AuthRepository` är
fortfarande kontrakt med stubbar tills respektive feature byggs.

**Fondbolagsfilter:** källans `company`-parameter filtrerar fondlistan exakt — utan den
levereras hela plattformens katalog (~1500 fonder), med den bara det valda bolagets.
Fondsök använder därför källans eget filter (`fetchFundsForCompany`) i stället för den
tidigare gissningen i appen. `FundCompanyMatcher.matches` är borttagen; `coreBrandName`
finns kvar som ledtråd åt `FundNameMatcher` vid importmatchning.

**ISIN från källan:** fondens egen sida (`/shb/sv/funds/<fundid>`) bär ISIN i
faktabladslänken, så `lookupIsin` kan koppla `FundId` ↔ ISIN maskinellt — används både när
en fond läggs till via fondsök och för att verifiera importens namnmatchning.

**Valuta:** all cachad NAV är i kronor (`FundPrice.VALUE_CURRENCY`). Fondlista noterar fonder i
fondens egen valuta — sådana kurser räknas om till kronor med en dagsnoterad växelkurs från
Riksbankens öppna API (`RiksbankFxClient`, `CurrencyConverter`, KRAVLISTA TP-19/TP-20) i stället
för att kastas. Saknas växelkursen för en dag utelämnas dagen, aldrig ett gissat värde; går
hämtningen inte alls faller fonden tillbaka på Avanza, som redan ger kronor direkt.

**Fonder som bara är kända via ISIN:** importmatchade fonder (`findFundByIsin`) har ISIN:et
som `fundId` och saknar plattformskod. De får ett uppslaget `fondlistaFundId` (namnkandidat
i katalogen verifierad mot ISIN) så även de når fondlistas färskare kurser — identiteten och
transaktionernas koppling följer fortfarande `fundId`. Avanzas svar filtreras dessutom på
handelsdag: källan levererar ibland helgdaterade kurser, som både är påhittade och gör
staleness-gaten (`isPriceStale`) falskt negativ.

---

## Datamodell

**Room** (`data/room/AppDatabase`, version **19**, `exportSchema = true`). Schemafilerna ligger i
`app/schemas/` och används av migreringstesterna. Migreringarna 1→2 … 18→19 står i
`AppDatabase` och har var sitt `MigrationXYTest` (skill `room-migrations`).

| Entitet | Tabell | Nyckel | I backupen |
|---|---|---|---|
| `FundEntity` | `funds` | `fundId` | ja |
| `TransactionEntity` | `transactions` | `id` (autoincrement) | ja |
| `SuggestionRecordEntity` | `suggestion_records` | `id` (autoincrement) | ja |
| `SwitchWatchEntity` | `switch_watches` | `id` (autoincrement) | ja |
| `SwitchWatchCandidateEntity` | `switch_watch_candidates` | främmande nyckel till `switch_watches` | ja, med sitt byte |
| `SuggestionDismissalEntity` | `suggestion_dismissals` | `sellIsin`, `buyIsin`, `kind` | ja |
| `FundPriceEntity` | `fund_prices` | `fundId`, `epochDay` | nej, cache från källorna |
| `FxRateEntity` | `fx_rates` | `currency`, `epochDay` | nej, cache från Riksbanken |
| `FundMetadataEntity` | `fund_metadata` | `isin` | nej, cache från Avanza/Handelsbanken |

**DataStore** (`data/datastore/PreferencesRepository`): användarens val (tema, riskprofil,
kontotyp, egenvald referensfond) ingår i backupen; cache och driftstatus (senaste kurssynk,
senaste säkerhetskopia, Drive-flaggan m.fl.) gör det inte.

**Backup** (`data/repository/BackupPayload`, `formatVersion = 1`): vad som ingår och vad som
medvetet inte gör det, med skäl, står i skill `data-safety-backup` (enda källan för fältlistan).

---

## Verifierade API:er

Med Compose BOM `2026.09.00` är Material 3 Expressive-API:erna `internal`. Därför pinnas `material3`
till `1.5.0-beta01` i `libs.versions.toml` (övriga Compose-artefakter följer BOM:en); byts till
stabil 1.5.0 när den släpps. Opt-in för experimentella Expressive-API:er står bara i `ui/theme` och
`ui/components`. Tabellen listar API:er som appen använder och som har ändrats nyligen eller lätt
förväxlas med äldre varianter. "Kompilerad" betyder att `:app:compileDebugKotlin` gick igenom med
versionen i tabellen.

| Behov | API | Artefakt:version | Används i | Verifierad |
|---|---|---|---|---|
| Expressive-tema | `se.partee71.ui.theme.AppTheme` (android-ui, `MaterialExpressiveTheme` + `MotionScheme.expressive()`), inre `MaterialExpressiveTheme(shapes = FonderShapes)` | `material3:1.5.0-beta01`, `ui-theme:0.5.0` | `ui/theme/Theme.kt` | 2026-10-20 (kompilerad, skärmdumparna oförändrade utom ljust temas vinst- och förlustfärg) |
| Laddningsindikator | `LoadingIndicator()` | `material3:1.5.0-beta01` | `ui/components` | 2026-10-20 (kompilerad) |
| Vågig progress | `LinearWavyProgressIndicator` | `material3:1.5.0-beta01` | `ui/components/ProgressBar.kt` | 2026-10-20 (kompilerad) |
| Flytande verktygsrad | `HorizontalFloatingToolbar` | `material3:1.5.0-beta01` | `ui/components/AppFloatingToolbar.kt` | 2026-10-20 (kompilerad) |
| FAB-meny | `FloatingActionButtonMenu` | `material3:1.5.0-beta01` | `ui/components/AppFabMenu.kt` | 2026-10-20 (kompilerad) |
| Växlingsknapp | `ToggleButton` | `material3:1.5.0-beta01` | `ui/components` | 2026-10-20 (kompilerad) |
| Dra för att uppdatera | `PullToRefreshBox` | `material3:1.5.0-beta01` | `ui/components/PullToRefreshContainer.kt` | 2026-10-20 (kompilerad) |
| Navigering | `NavDisplay`, `rememberSaveableStateHolderNavEntryDecorator()` | `navigation3-ui`/`-runtime:1.2.0` | `ui/navigation/AppNavigation.kt` | 2026-10-20 (kompilerad) |
| ViewModel per nav-post | `rememberViewModelStoreNavEntryDecorator()` | `lifecycle-viewmodel-navigation3:2.11.0` | `ui/navigation/AppNavigation.kt` | 2026-10-20 (kompilerad) |
| Diagram | `CartesianChartHost` | `vico:3.3.1` | `ui/diagram` (skill `diagram`) | 2026-10-20 (kompilerad) |
| Google-inloggning | `CredentialManager` + `GetSignInWithGoogleOption` | `credentials:1.6.0`, `googleid:1.2.1` | `data/auth` | 2026-10-20 (kompilerad) |
| Drive-behörighet | `AuthorizationClient` (`Identity.getAuthorizationClient`) | `play-services-auth:22.0.0` | `data/repository/DriveBackupRepository` | 2026-10-20 (kompilerad) |

---

## Byggkonfiguration

| Vad | Värde | Kommentar |
|---|---|---|
| Version | `version.properties` | `versionCode`, `versionName`; `-PversionNameOverride` från `release.yml`/`release-debug.yml` går före. Höjs i funktions-PR:en (CLAUDE.md → PR-flöde). Filen ligger utanför CI-filtren i `android.yml` |
| minSdk / targetSdk / compileSdk | 30 / 36 / 37 | `app/build.gradle.kts`, KRAVLISTA TP-1 |
| Gradle | 9.8.1 | wrapper |
| AGP | 9.4.1 | inbyggt Kotlin-stöd – `org.jetbrains.kotlin.android` används inte |
| Kotlin | 2.4.21 | `libs.versions.toml` |
| KSP / Hilt | 2.3.12 / 2.60.1 | KAPT används inte |
| Java | källa och mål 17, JDK 21 kör bygget | Robolectric kräver 21 för SDK 36 (`.github/actions/setup-android`) |
| Compose | BOM 2026.09.00, `material3` 1.5.0-beta01 | se Verifierade API:er |
| Kontroller | Konsist (`UiConsistencyTest`), PMD CPD (`cpdCheck`), Spotless/ktlint lokalt | skill `shared-ui-components`, `ci-budget` |
| Moduler | `:app` | `:core` är #171 |
| android-ui | `se.partee71.ui` 0.5.0 (`ui-components`, med `ui-theme` och `ui-common` transitivt; i test `ui-testing`) | Mats komponentbibliotek ([ParTee71/android-ui](https://github.com/ParTee71/android-ui)), delat med ReseApoteket och Dagboken. GitHub Packages kräver inloggning även för läsning: `GPR_USER`/`GPR_TOKEN` (classic token, `read:packages`) i sessionen; i CI sätter `setup-android` dem från `secrets.GPR_TOKEN`, annars `github.token` med `packages: read`. Temat (`AppTheme`, `Spacing`) sedan #172, listtillstånd och fel (`ListUiState`, `UiError`, `ErrorMapper`) sedan #173 |

Övrig Gradle-uppsättning (plugins, KSP-inställningar, cache) står i skill `android-projekt` → Gradle.
