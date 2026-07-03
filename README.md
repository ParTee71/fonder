# Fonder – Android

App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut värde
och visa utveckling i tabell och diagram — med molnbackup via Google Drive.

**Kravspecifikation:** [KRAVLISTA.md](KRAVLISTA.md) · **Utvecklingsregler:** [CLAUDE.md](CLAUDE.md)

> **Bidrar du (eller en AI-assistent) med kod?** Läs [CLAUDE.md](CLAUDE.md) först — den
> samlar projektets fyra icke-förhandlingsbara regler: datasäkerhet (backup/restore),
> tester på alla nivåer, aktuell kravlista och återbruk av delade komponenter.

---

## Status

Projektet är i **tidig fas**. Grunden (arkitektur, tema, Room/DataStore, navigering,
repository-kontrakt, CI) finns; slutfunktionerna byggs som egna issues:

- [x] Kurskälla från Handelsbanken utan inloggning (spike #2 → implementerad i #3)
- [ ] Fondtransaktioner (köp/sälj)
- [ ] Värdeberäkning (historiskt + nuvarande)
- [ ] Utveckling i tabell och diagram
- [ ] Google Drive-backup
- [ ] Google-inloggning

---

## Design

Visuell identitet **grön petrol** (kärna `#167C6E`, primär `#0E5249`) med
mässings-/guldaccent (`#C9A227`), **fast palett** (ingen dynamisk färg), ljust + mörkt
tema. Rubriker i **Space Grotesk**; belopp med **tabulära siffror**. Avkastning visas med
semantisk färg **och** tecken/pil (aldrig färg ensam).

---

## Kom igång

### Förutsättningar

- Android Studio (senaste), JDK 17, Android SDK API 35 (compileSdk), minSdk 30

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
├── network/      HandelsbankenFondlistaClient + HandelsbankenHtmlParser (kurskälla, #2/#3)
├── repository/   TransactionRepository (Room) · FundPriceRepository (Handelsbanken) · BackupRepository (stub)
└── room/         AppDatabase (v2) · entities · daos
di/               Hilt-moduler (AppModule, NetworkModule, RepositoryModule)
domain/
├── model/        Fund (fundId) · FundCompany · FundCatalog · Transaction · FundPrice · Holding
└── usecase/      PortfolioCalc · MoneyFormat · FundCompanyMatcher (fond ↔ fondbolag, se nedan)
ui/
├── portfolj/     PortfoljScreen + ViewModel
├── transaktioner/TransaktionerScreen + ViewModel
├── fond/         FondDetaljScreen (diagram-placeholder)
├── fondsok/      FundSearchScreen + ViewModel (sök, filtrera per fondbolag, lägg till fond)
├── settings/     SettingsScreen + ViewModel
├── navigation/   AppNavigation · Screen
├── components/   Delade komponenter (EmptyState …)
├── diagram/      Delade diagram (FundLineChart — tillkommer)
└── theme/        Grön petrol-tema, Space Grotesk-typografi
worker/           FundPriceUpdateWorker (daglig kursuppdatering)
```

Repository är single source of truth. `FundPriceRepository` hämtar och cachar riktiga
kurser från `handelsbanken.fondlista.se` (se issue #2/#3 för källbeslut och risknotis —
odokumenterad, inofficiell HTML-källa). `BackupRepository` och `AuthRepository` är
fortfarande kontrakt med stubbar tills respektive feature byggs.

**Fondbolagsfilter:** sidans eget "Fondbolag"-filter visade sig inte filtrera fondlistan i
praktiken (verifierat manuellt) — `FundCompanyMatcher` bygger därför en egen, ungefärlig
koppling fond ↔ fondbolag i appen: Handelsbanken via `FundId`-prefixet `SHB` (täcker även
varumärket **XACT**), övriga bolag via namnprefix efter att bolagsform städats bort.

---

## Tester

- **Enhet (JVM):** `domain/` (PortfolioCalc, MoneyFormat), `data/network/`
  (HTML-parsning mot verkliga sidfixturer), `data/repository/` (cache/fallback-logik med
  fejkad HTTP-källa) och ViewModels (Turbine).
- **Instrument:** Room DAO-rundtur (`androidTest`), inklusive `FundPriceDao`.

> Formell `MigrationTestHelper`-baserad migreringstest saknas för 1→2 (ingen
> version-1-schemasnapshot finns i repot). Framtida schemaändringar (v2→v3+) bör testas
> med `MigrationTestHelper` mot committade `app/schemas/*.json`.

Kör i **GitHub Actions** (`.github/workflows/android.yml`) vid PR/push mot `master`.
