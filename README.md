# Fonder – Android

App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut värde
och visa utveckling i tabell och diagram — med molnbackup via Google Drive.

> Version: 0.17.0 (följer `versionName`/[KRAVLISTA.md](KRAVLISTA.md))

**Kravspecifikation:** [KRAVLISTA.md](KRAVLISTA.md) · **Utvecklingsregler:** [CLAUDE.md](CLAUDE.md)

> **Bidrar du (eller en AI-assistent) med kod?** Läs [CLAUDE.md](CLAUDE.md) först — den
> samlar projektets fyra icke-förhandlingsbara regler: datasäkerhet (backup/restore),
> tester på alla nivåer, aktuell kravlista och återbruk av delade komponenter.

---

## Status

Projektet är i **tidig fas**. Grunden (arkitektur, tema, Room/DataStore, navigering,
repository-kontrakt, CI) finns; slutfunktionerna byggs som egna issues:

- [x] Kurskälla från Handelsbanken utan inloggning (spike #2 → implementerad i #3)
- [x] Fondtransaktioner (köp/sälj) (#4)
- [x] Värdeberäkning, nuvarande värde (#6)
- [x] Historisk värdeutveckling i tabell och diagram (#7)
- [x] Import av befintliga innehav (Handelsbanken-Excel) (#8)
- [x] Kurshistorik via ISIN sedan första köpet, utöver Handelsbankens 5-årsfönster (#7-uppföljning)
- [x] Import av exakta transaktioner från PDF-avräkningsnotor, flera samtidigt (#8-uppföljning)
- [x] Töm databasen från Inställningar, med bekräftelse (SET-1)
- [x] Realiserat resultat (FIFO) och avgifter vid försäljning, egen vy "Sålda fonder" (#10)
- [x] Hem — startskärm med portföljens dag/vecka/månadsresultat (#14)
- [x] Analys — nyckeltal och säljsignal-status per innehav, summeringskort på Hem (#16)
- [ ] Google Drive-backup — väntar på Firebase-projekt för fonder
- [ ] Google-inloggning — väntar på Firebase-projekt för fonder

---

## Design

Visuell identitet **grön petrol** (kärna `#167C6E`, primär `#0E5249`) med
mässings-/guldaccent (`#C9A227`), **fast palett** (ingen dynamisk färg), ljust + mörkt
tema. Rubriker i **Space Grotesk**; belopp med **tabulära siffror**. Avkastning visas med
semantisk färg **och** tecken/pil (aldrig färg ensam).

---

## Kom igång

### Förutsättningar

- Android Studio (senaste), JDK 17, Android SDK API 36 (compileSdk), minSdk 30

### Bygg och kör

```bash
./gradlew :app:assembleDebug
```

Öppna i Android Studio och kör på en enhet/emulator (API 30+).

---

## Arkitektur

```
Compose → ViewModel (StateFlow<UiState>) → Repository → Room / DataStore
```

Paket under `app/src/main/kotlin/se/partee71/fonder/`:

```
data/
├── auth/         AuthRepository (Google-inloggning — stub tills auth-issue)
├── datastore/    PreferencesRepository (tema m.m.)
├── network/      HandelsbankenFondlistaClient + HandelsbankenHtmlParser (kurskälla, #2/#3) ·
│                 AvanzaClient + AvanzaJsonParser + AvanzaPriceSource (ISIN-baserad historik, #7-uppföljning)
├── imports/      HoldingsImportParser (Excel-innehav, #8) · AvrakningsnotaPdfParser + PdfTextExtractor
│                 (PDF-avräkningsnotor, flera filer samtidigt, #8-uppföljning)
├── repository/   TransactionRepository (Room) · FundPriceRepository (Handelsbanken + ISIN-källkedja) · BackupRepository (stub)
└── room/         AppDatabase (v4) · entities · daos
di/               Hilt-moduler (AppModule, NetworkModule, RepositoryModule)
domain/
├── model/        Fund (fundId, valfritt isin) · FundCompany · FundCatalog · Transaction (inkl. fee) · FundPrice ·
│                 IsinPricePoint · ImportedHoldingRow · ImportedOrderTransaction · Holding
└── usecase/      PortfolioCalc · PortfolioPerformanceCalc (dag/vecka/månad, #14) ·
                  FundAnalysisCalc (nyckeltal + säljsignaler per innehav, #16) ·
                  RealizedGainCalculator (delad FIFO-motor, realiserat + kvarvarande resultat, #10) ·
                  MoneyFormat · SwedishNumberFormat · FundCompanyMatcher (fond ↔ fondbolag) · FundNameMatcher ·
                  PurchaseDateEstimator · ImportFundMatcher (delad matchningsordning, regel 4) ·
                  TransactionFormValidator
ui/
├── hem/          HemScreen + ViewModel (startskärm, dag/vecka/månadsresultat, analys-summeringskort #16)
├── portfolj/     PortfoljScreen + ViewModel
├── transaktioner/TransaktionerScreen + ViewModel · TransactionFormScreen + ViewModel (registrera köp/sälj, avgift) ·
│                 SoldFundsScreen + ViewModel (realiserat resultat per sälj, #10)
├── fond/         FondDetaljScreen + ViewModel (kurshistorik i diagram och tabell sedan första köpet, #7 ·
│                 Analys-sektion med nyckeltal/säljsignaler, #16)
├── fondsok/      FundSearchScreen + ViewModel (sök, filtrera per fondbolag, lägg till fond)
├── imports/      ImportHoldingsScreen + ViewModel (Excel-innehav, #8) · ImportOrdersScreen + ViewModel
│                 (PDF-avräkningsnotor, #8-uppföljning)
├── settings/     SettingsScreen + ViewModel
├── navigation/   AppNavigation · Screen
├── components/   Delade komponenter (EmptyState, SelectField, DateField, PeriodRow, AnalysisStatusBanner/StatusDot …)
├── diagram/      Delade diagram (FundLineChart)
└── theme/        Grön petrol-tema, Space Grotesk-typografi (inkl. StatusColors, #16)
worker/           FundPriceUpdateWorker (daglig kursuppdatering)
```

Repository är single source of truth. `FundPriceRepository` hämtar och cachar riktiga
kurser från `handelsbanken.fondlista.se` (se issue #2/#3 för källbeslut och risknotis —
odokumenterad, inofficiell HTML-källa). Har en fond ett känt **ISIN** hämtas kurshistorik
**sedan första köpet** i stället (Handelsbankens fasta 5-årsfönster räcker inte för äldre
köp) från en prioritetsordnad lista av `IsinPriceHistorySource` — i dag bara Avanzas
odokumenterade fond-API (`AvanzaPriceSource`, samma riskprofil som Handelsbanken-källan,
se KRAVLISTA TP-14). `BackupRepository` och `AuthRepository` är fortfarande kontrakt med
stubbar tills respektive feature byggs.

**Fondbolagsfilter:** sidans eget "Fondbolag"-filter visade sig inte filtrera fondlistan i
praktiken (verifierat manuellt) — `FundCompanyMatcher` bygger därför en egen, ungefärlig
koppling fond ↔ fondbolag i appen: Handelsbanken via `FundId`-prefixet `SHB` (täcker även
varumärket **XACT**), övriga bolag via namnprefix efter att bolagsform städats bort.

---

## Tester

- **Enhet (JVM):** `domain/` (PortfolioCalc, RealizedGainCalculator — FIFO inkl.
  delförsäljning över flera lotter och avgift, FundAnalysisCalc — periodavkastning, CAGR,
  GAV, portföljandel och säljsignalerna S1–S3 kring sina trösklar, MoneyFormat), `data/network/`
  (HTML-/JSON-parsning mot verkliga sid-/API-fixturer, inkl. Avanzas fond-API),
  `data/imports/` (Excel- och PDF-parsning mot verkliga fixturer — köp- **och**
  sälj-avräkningsnota, PDF-textextraktionen fejkad via `PdfTextExtractor` så testerna
  slipper PDF-biblioteket), `data/repository/` (cache/fallback-logik med fejkade
  HTTP-källor) och ViewModels (Turbine).
- **Instrument:** Room DAO-rundtur (`androidTest`), inklusive `FundPriceDao`, migreringstester
  (`Migration12Test`, `Migration23Test`, `Migration34Test`) samt `RoomTransactionRepositoryTest`
  (`clearAll` töms atomiskt över alla tre tabeller, SET-1).

> Formell `MigrationTestHelper`-baserad migreringstest saknas (ingen schema-snapshot i
> `app/schemas/` finns i repot ännu) — migreringstesterna bygger i stället schemat för
> hand och öppnar via den riktiga `AppDatabase`, vilket ändå fångar en felaktig migrering
> via Rooms identity-hash-validering. Framtida schemaändringar bör helst testas med
> `MigrationTestHelper` mot committade `app/schemas/*.json`.

Kör i **GitHub Actions** (`.github/workflows/android.yml`) vid PR/push mot `master`.
