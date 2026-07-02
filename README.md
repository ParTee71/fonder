# Fonder – Android

App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut värde
och visa utveckling i tabell och diagram — med molnbackup via Google Drive.

**Kravspecifikation:** [KRAVLISTA.md](KRAVLISTA.md) · **Utvecklingsregler:** [CLAUDE.md](CLAUDE.md)

> **Bidrar du (eller en AI-assistent) med kod?** Läs [CLAUDE.md](CLAUDE.md) först — den
> samlar projektets fyra icke-förhandlingsbara regler: datasäkerhet (backup/restore),
> tester på alla nivåer, aktuell kravlista och återbruk av delade komponenter.

---

## Status

Projektet är i **init-fas**. Grunden (arkitektur, tema, Room/DataStore, navigering,
repository-kontrakt, CI) finns; slutfunktionerna byggs som egna issues:

- [ ] Kurskälla från Handelsbanken utan inloggning (spike #2)
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
├── repository/   TransactionRepository (Room) · FundPriceRepository (stub, #2) · BackupRepository (stub)
└── room/         AppDatabase (v1) · entities · daos
di/               Hilt-moduler (AppModule, RepositoryModule)
domain/
├── model/        Fund · Transaction · FundPrice · Holding
└── usecase/      PortfolioCalc · MoneyFormat
ui/
├── portfolj/     PortfoljScreen + ViewModel
├── transaktioner/TransaktionerScreen + ViewModel
├── fond/         FondDetaljScreen (diagram-placeholder)
├── settings/     SettingsScreen + ViewModel
├── navigation/   AppNavigation · Screen
├── components/   Delade komponenter (EmptyState …)
├── diagram/      Delade diagram (FundLineChart — tillkommer)
└── theme/        Grön petrol-tema, Space Grotesk-typografi
worker/           WorkManager-jobb (tillkommer)
```

Repository är single source of truth. `FundPriceRepository`, `BackupRepository` och
`AuthRepository` är kontrakt vars implementationer är stubbar tills respektive feature
byggs (så arkitekturen är komplett från dag ett).

---

## Tester

- **Enhet (JVM):** `domain/` (PortfolioCalc, MoneyFormat) och ViewModels (Turbine).
- **Instrument:** Room DAO-rundtur (`androidTest`).

Kör i **GitHub Actions** (`.github/workflows/android.yml`) vid PR/push mot `master`.
