# Kravlista – Fonder (Android)

> App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut
> värde och visa utveckling i tabell och diagram, med molnbackup och Google-inloggning.
>
> Version: 0.1.0 · Paket: `se.partee71.fonder` · Språk: Svenska

---

## 1. Översikt och syfte

| ID | Krav |
|----|------|
| ÖV-1 | Appen ska låta användaren **bevaka fonder** och se innehav samlat i en portfölj. |
| ÖV-2 | Appen ska hämta **fondkurser (NAV) från Handelsbanken utan inloggning**. *(planerad — spike #2)* |
| ÖV-3 | Appen ska låta användaren **registrera fondtransaktioner** (köp/sälj). *(planerad)* |
| ÖV-4 | Appen ska räkna ut **historiskt och nuvarande värde** utifrån transaktioner och kurser. *(planerad)* |
| ÖV-5 | Appen ska visa fonders **utveckling i tabell och diagram**. *(planerad)* |
| ÖV-6 | Appen ska fungera **offline-först**; data lagras lokalt och backas upp till molnet. |
| ÖV-7 | Hela gränssnittet ska vara på **svenska**. |

---

## 2. Teknisk plattform (förutsättningar)

| ID | Krav |
|----|------|
| TP-1 | Android, **minSdk 30**, target/compileSdk 35, JDK 17. Paket `se.partee71.fonder`. |
| TP-2 | UI byggt med **Jetpack Compose** + Material 3. |
| TP-3 | Arkitektur: **MVVM** med Hilt (DI), Repository-mönster, ViewModels med `StateFlow`. |
| TP-4 | Lokal lagring i **Room** (`exportSchema = true`); inställningar i **DataStore (Preferences)**. |
| TP-5 | Bakgrundsjobb via **WorkManager** (Hilt-integrerad worker) — uppsatt för kommande backup/kursuppdatering. |
| TP-6 | Inloggning via **Firebase Auth + Google Credential Manager**. *(planerad)* |
| TP-7 | Molnbackup via **Google Drive (appDataFolder)**. *(planerad)* |
| TP-8 | Krävd behörighet: `INTERNET`. |

---

## 3. Utseende och tema

| ID | Krav |
|----|------|
| UI-1 | Visuell identitet **grön petrol** med mässings-/guldaccent, fast palett (ej dynamisk färg), ljust + mörkt tema. |
| UI-2 | Rubriker/UI i **Space Grotesk**; belopp och sifferkolumner med **tabulära siffror**. |
| UI-3 | Avkastning (vinst/förlust) visas med **semantisk färg + tecken/pil**, aldrig färg ensam. |
| UI-4 | Tema kan väljas: **Ljust / Mörkt / Auto** (sparas i DataStore). |

---

## 4. Navigation och skärmar (grund)

| ID | Krav |
|----|------|
| NAV-1 | Toppnivå med navigeringsrad: **Portfölj**, **Transaktioner**, **Inställningar**. |
| NAV-2 | Från Portfölj kan man öppna **Fonddetalj** (kurshistorik/diagram — planerad). |
| POR-1 | Portföljen visar innehav per fond och **totalt nettoinvesterat belopp**. |
| POR-2 | Tom portfölj visar ett tomt-tillstånd som uppmanar att lägga till en transaktion. |

---

## 5. Datasäkerhet och tester (icke förhandlingsbart)

| ID | Krav |
|----|------|
| NFR-1 | All persisterad användardata ska överleva en **backup → restore-rundtur** utan förlust. *(backup planerad)* |
| NFR-2 | Ingen beteendeändring utan **tester** på berörd nivå (enhet/instrument/migrering). |
| NFR-3 | Room-schemaändring kräver **migrering** utan dataförlust. |

---

## Följdkrav (planerade — se GitHub-issues)

Kurskälla (#2), transaktioner, värdeberäkning, diagram, Drive-backup och Google-inloggning
läggs till som egna krav i respektive avsnitt när de implementeras.
