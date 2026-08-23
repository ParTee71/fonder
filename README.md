# Fonder – Android

App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut värde
och visa utveckling i diagram — med molnbackup via Google Drive.

> Version: 0.51.0 (följer `versionName`/[KRAVLISTA.md](KRAVLISTA.md))

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
- [x] Kurshistorik sedan första köpet, utan datumtak (#7-uppföljning, källan utnyttjad fullt ut i #37)
- [x] Import av exakta transaktioner från PDF-avräkningsnotor, flera samtidigt (#8-uppföljning)
- [x] Töm databasen från Inställningar, med bekräftelse (SET-1)
- [x] Realiserat resultat (FIFO) och avgifter vid försäljning, egen vy "Sålda fonder" (#10)
- [x] Hem — startskärm med portföljens dag/vecka/månadsresultat (#14)
- [x] Analys — nyckeltal och säljsignal-status per innehav, summeringskort på Hem (#16)
- [x] Fondmetadata (avgift, kategori, risk) — sökbart datalager, grund för en framtida
      fondrobot (#57)
- [x] Billigare alternativ till ett innehav — appens första rådgivande funktion, i Fonddetalj
      (ANA-9, #59)
- [x] Portföljens totala fondavgift per år, kort på Hem (HEM-5, #60)
- [x] Samlad besparingspotential, persisterad jämförelse med inkrementell bakgrundsifyllnad
      (HEM-6, #61)
- [x] Exponeringskarta i Portfölj — andel per fondtyp, region, index/aktivt (POR-9, #66)
- [x] Riskprofil — enkät, målrisknivå och jämförelse mot innehavens faktiska risk (SET-3/HEM-7, #68)
- [x] Fondkortet som beslutsstöd — byte överst, foldouts, risknivå överallt och
      jämförelsediagram mot föreslagen fond (ANA-10/ANA-11/UI-10, #85)
- [x] Bytesplanen räknas om på begäran — knapp på riskkortet plus automatiskt vid ändrad
      riskprofil/kontotyp (HEM-8/SET-3/SET-4, #88)
- [x] "Genomförd"-kvittering även på fondkortets bytesförslag, via delad komponent (ANA-10, #90)
- [x] Avgiftsbytena spelas in i facit och kan kvitteras, redovisade skilt från bytesplanens
      byten (ANA-9/ANA-10/SET-5, #91) — varje visat alternativ, oberoende av jämförelsens
      TTL (#93)
- [x] Riskprofil som målfördelning över flera risknivåer, i stället för en enda nivå (SET-3/HEM-7/POR-9, #71)
- [x] Bytesplan i ISK: rangordnade fondbyten mot målfördelningen, med facit-inspelning (SET-4/HEM-8, #70)
- [x] Säkerhetskopiering till fil — export/återställning av all användardata via SAF (SET-6, #82)
- [x] Google-inloggning — Firebase Auth + Credential Manager, kontokort i Inställningar
      (TP-6, #106)
- [ ] Google Drive-backup — steg 2 av TP-7, väntar på att `drive.appdata` tas i bruk
      ([uppsättning](docs/GOOGLE-SETUP.md))

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

### Google Services

Google-inloggning (TP-6) och Drive-backup (TP-7 steg 2) kräver en `google-services.json`
från Firebase Console i `app/`. Filen är **incheckad** — `google-services`-pluginet läser
den vid varje bygge, så utan den i repot går CI inte att köra. Inloggningen är byggd sedan
#106; Drive-beroendena ligger deklarerade i `gradle/libs.versions.toml` och kopplas in i
sitt eget issue.

Signeringsnycklar (`*.jks`, `*.keystore`) får **aldrig** checkas in — CI har en grind som
fäller bygget om någon gör det. Undantaget är `app/debug.keystore`, som är avsiktligt
incheckad med Androids kända standardlösenord.

Uppsättningen — Firebase-projekt, SHA-1-fingeravtryck, OAuth-medgivandeskärm och Drive
API — är dokumenterad steg för steg i **[docs/GOOGLE-SETUP.md](docs/GOOGLE-SETUP.md)**.

---

## Releasebygge och signering

Releasebygget signeras med `app/fonder.jks` (git-ignorerad). Lösenord läses ur
`local.properties` eller miljövariabler — saknas de byggs `release` osignerat i stället
för att fela.

**local.properties (lokalt, git-ignorerad):**
```properties
signing.storePassword=<lösenord>
signing.keyAlias=fonder
signing.keyPassword=<lösenord>
```

**GitHub Secrets (CI):** `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. `.github/workflows/release.yml` avkodar
keystoren, bygger och publicerar en signerad APK — hela släppet går därmed att köra från
telefonen. Se [docs/GOOGLE-SETUP.md](docs/GOOGLE-SETUP.md) för hur nyckeln skapas och
läggs upp.

---

## Arkitektur

```
Compose → ViewModel (StateFlow<UiState>) → Repository → Room / DataStore
```

Paket under `app/src/main/kotlin/se/partee71/fonder/`:

```
data/
├── auth/         AuthRepository + FirebaseAuthRepository (Google-inloggning via Credential Manager, TP-6/#106)
├── datastore/    PreferencesRepository (tema, riskprofil #68 m.m.)
├── network/      HandelsbankenFondlistaClient + HandelsbankenHtmlParser (kurskälla, #2/#3) ·
│                 AvanzaClient + AvanzaJsonParser + AvanzaPriceSource (ISIN-baserad historik, #7-uppföljning) ·
│                 AvanzaFundListParser + AvanzaFundListRequestBuilder (sökbar fondmetadata, #57) ·
│                 RiksbankFxClient + RiksbankJsonParser (dagsnoterade växelkurser, #43)
├── imports/      HoldingsImportParser (Excel-innehav, #8) · AvrakningsnotaPdfParser + PdfTextExtractor
│                 (PDF-avräkningsnotor, flera filer samtidigt, #8-uppföljning)
├── repository/   TransactionRepository (Room) · FundPriceRepository (Handelsbanken + ISIN-källkedja) ·
│                 FundMetadataRepository (fondmetadata + Handelsbanken-köpbarhet + persisterad
│                 billigare-alternativ-jämförelse + kända risknivåer, #57/#61/#68) · BackupRepository (stub)
└── room/         AppDatabase (v9) · entities (inkl. FxRateEntity, FundMetadataEntity) · daos (inkl. FxRateDao, FundMetadataDao)
di/               Hilt-moduler (AppModule, NetworkModule, RepositoryModule)
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
                  FundScreenFilter + FundMetadataFreshness (fondmetadata-frågor, #57) ·
                  FeeComparisonCalc (billigare alternativ, ANA-9, #59) ·
                  PortfolioFeeCalc (portföljens totala fondavgift + samlade besparingspotential, HEM-5/HEM-6, #60/#61) ·
                  PortfolioExposureCalc (exponeringskarta: fondtyp/region/index-aktivt, POR-9, #66) ·
                  RiskProfileCalc (målrisknivå ur enkätsvar, SET-3, #68) ·
                  PortfolioRiskCalc (innehavens värdeviktade risknivå, HEM-7, #68)
ui/
├── hem/          HemScreen + ViewModel (startskärm, dag/vecka/månadsresultat, analys-summeringskort #16,
│                 fondavgiftskort med samlad besparingspotential HEM-5/HEM-6, #60/#61,
│                 riskrad mot riskprofilens målnivå HEM-7/#68)
├── portfolj/     PortfoljScreen + ViewModel (exponeringskarta: fondtyp/region/index-aktivt, POR-9, #66)
├── transaktioner/TransaktionerScreen + ViewModel · TransactionFormScreen + ViewModel (registrera köp/sälj, avgift) ·
│                 SoldFundsScreen + ViewModel (realiserat resultat per sälj, #10)
├── fond/         FondDetaljScreen + ViewModel (bytesbeslut överst: bytesplan + billigare alternativ med
│                 jämförelsediagram, ANA-10/ANA-11/#85 · kurshistorik i diagram sedan första köpet, #7 ·
│                 hopfälld Analys-sektion med nyckeltal/säljsignaler, #16 · billigare alternativ, ANA-9/#59)
├── fondsok/      FundSearchScreen + ViewModel (sök hela plattformens katalog, filtrera per fondbolag via källan, lägg till fond)
├── imports/      ImportHoldingsScreen + ViewModel (Excel-innehav, #8) · ImportOrdersScreen + ViewModel
│                 (PDF-avräkningsnotor, #8-uppföljning)
├── settings/     SettingsScreen + ViewModel (tema, kursuppdatering, riskprofil-ingång SET-3/#68 …)
├── riskprofil/   RiskProfilScreen + ViewModel (enkät + målrisknivå, SET-3, #68)
├── navigation/   AppNavigation · Screen
├── components/   Delade komponenter (EmptyState, SelectField, DateField, PeriodRow, AnalysisStatusBanner/StatusDot,
│                 ExposureBar — proportionell radlista, POR-9/#66 · ChoiceChipRow — val-chiprad, #68 ·
│                 ExpandableSection/ExpandableInfoRow — utfällning, #22/#85 · RiskBadge — risknivå 1–7, UI-10/#85 …)
├── diagram/      Delade diagram (FundLineChart — en eller flera indexerade serier, ANA-11/#85)
└── theme/        Grön petrol-tema, Space Grotesk-typografi (inkl. StatusColors, #16)
worker/           FundPriceUpdateWorker (daglig kursuppdatering + inkrementell jämförelseifyllnad, HEM-6/#61)
```

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
