---
name: data-safety-backup
description: 'Fonders datasäkerhetsregel (regel 1) — backup och restore får ALDRIG tappa användardata. Ladda denna ALLTID när du lägger till, ändrar eller tar bort persisterad data: en Room-entitet eller -kolumn, ett DataStore-värde, en domänmodell som sparas, eller något som rör backup/restore/export/import/JSON. Trigger-ord: backup, restore, återställ, säkerhetskopia, BackupPayload, BackupSerializer, LocalBackupRepository, BackupRoundTripTest, fältvakt, formatVersion, DataStore, PreferencesRepository, SAF, rundtur, round-trip, Drive, appDataFolder, molnbackup, DriveBackupRepository, DriveBackupWorker, DriveBackupFiles, fonder-backup-, gallring.'
---

# Datasäkerhet: backup & restore

**Invariant:** All persisterad användardata måste överleva en **backup → restore-rundtur**
med identiskt innehåll. Ett fält som läggs till utan att komma med i backup-kedjan är en
regression även om appen kompilerar och alla gamla tester är gröna — datan faller tyst ur
varje framtida säkerhetskopia och märks först när någon behöver den.

Relaterade krav: **NFR-1**, **NFR-3**, **TP-7**, **SET-6**, **SET-7**.

## Kedjan (var datan passerar)

```
Room + DataStore  →  BackupPayload  →  BackupSerializer.encode  →  JSON-sträng  ─┬→  SAF-fil (SET-6)
       ▲                                                                         └→  Drive appDataFolder (SET-7)
       └────  LocalBackupRepository.restore  ←  BackupSerializer.decode  ←  samma sträng
```

Filer under `data/repository/`:

- **`BackupPayload.kt`** — *kontraktet*. Byggt på domänmodellerna, inte på egna DTO:er.
  Det här är listan över vad som räknas som användardata.
- **`BackupSerializer.kt`** — *formatet*. Äger `formatVersion` och kastar
  `BackupFormatException` med `Reason.UNSUPPORTED_VERSION` / `Reason.UNREADABLE`.
- **`BackupRepository.kt`** — interfacet (`export(): Result<String>`,
  `restore(json): Result<RestoreSummary>`) och `LocalBackupRepository`.

**Kontraktet är en `String`, inte en `Uri` eller en fil.** Transporten hör inte hemma i
repositoryt: SAF skriver strängen (SET-6) och Drive `appDataFolder` skriver samma sträng
(TP-7 steg 2, SET-7) – formatet är detsamma. Bygg aldrig in en `Uri`, en `ContentResolver`,
ett `File` eller ett Drive-anrop i `BackupRepository` — den I/O:n ligger i skärmen respektive i
`DriveBackupRepository`.

## Vad som ingår — och vad som medvetet inte gör det

**Ingår** (fälten i `BackupPayload`, `formatVersion = 1`): fonder, transaktioner, samtliga
inspelade förslag (`SuggestionRecord`, inkl. `followed`, `switchValueKr`, `batchEpochMillis`,
`kind`), pågående och avslutade byten (`switchWatches`, ANA-12, #114), riskprofil (SET-3, inkl.
legacy-fältet `targetRiskLevel`), kontotyp (SET-4), temaval och den egenvalda referensfonden
(`chosenBenchmarkIsin`, HEM-10).

**Ingår inte, med avsikt:** `fund_prices`, `fx_rates`, `fund_metadata`. Härledd cache som
hämtas om från källan och som skulle mångdubbla filen utan att skydda något oåterskapbart.
De **töms heller inte** av en återställning, så lokal kurshistorik behålls.

Samma gräns gäller cache och driftstatus i DataStore: `lastPriceSyncEpochMillis`,
`fundFilterVocabulary`, den automatiskt valda jämförelseblandningen (`benchmark`) och
Drive-statusen (senaste lyckade körning, "behöver auktorisering") är inte användardata.
Riskprofilen, kontotypen och den egenvalda referensfonden **är** det.

`formatVersion` höjs **bara** när ett fält tas bort eller byter betydelse – ett tillagt fält med
default läses av äldre appar (okända nycklar ignoreras).

Frågan att ställa om ett nytt fält: *går det att återskapa från en källa, eller är det något
användaren själv matat in eller som appen räknat fram och aldrig kan räkna fram igen?* Det
senare ska in i kontraktet.

## Checklista: nytt fält på en befintlig kontraktsbärande modell

1. Lägg fältet på Room-entiteten **och** skriv migreringen (skill `room-migrations`).
2. Lägg fältet på domänmodellen.
3. Lägg fältet i `BackupPayload` — **med default-värde**, annars går äldre
   säkerhetskopior sönder vid inläsning.
4. Fyll fältet i `LocalBackupRepository.export()` och skriv tillbaka det i `restore()`.
5. **Fältvakten i `BackupSerializerTest` kommer att falla.** Det är meningen: den låser
   formatets nyckelmängd per modell, så ett nytt fält kräver ett medvetet beslut i stället
   för att tyst falla ur formatet. Uppdatera vakten *efter* att fältet faktiskt är med.
6. Utöka `BackupRoundTripTest` så rundturen asserterar på fältet.
7. Uppdatera KRAVLISTA (SET-6 listar innehållet, NFR-1 är regeln) — skill
   `requirements-kravlista`.

## Checklista: helt ny datatyp som ska backas upp

1. Ny lista på `BackupPayload` med `= emptyList()` som default.
2. Fyll den i `export()`, skriv tillbaka den i `restore()` — **inuti samma
   `withTransaction`** som övriga Room-skrivningar.
3. `RestoreSummary` utökas om antalet ska redovisas för användaren.
4. Fältvakt + rundturstest enligt ovan.
5. SET-6:s uppräkning i KRAVLISTA uppdateras.

## Tester som alltid krävs

| Test | Plats | Vad det skyddar |
|---|---|---|
| Fältvakt + serialisering | `test/.../data/repository/BackupSerializerTest.kt` | Formatets nyckelmängd per modell; versionsavvisning; att en trasig fil ger `UNREADABLE` och inte en halv inläsning. |
| Rundtur | `androidTest/.../data/repository/BackupRoundTripTest.kt` | backup → SET-1-tömning → restore mot **riktig** Room och **riktig** DataStore. |
| Migrering | `androidTest/.../data/room/MigrationXYTest.kt` | Att schemaändringen inte tappar data (skill `room-migrations`). |

Regel: varje persisterat fält ska gå att spåra till minst ett test som **asserterar på just
det fältet**. Ett fält som bara finns i `BackupPayload` men aldrig assertas är inte skyddat.

## Principer som redan kostat på sig

- **Återställning ersätter, den slår inte ihop.** Det är en återställning, inte en import —
  merge dubblerar transaktioner utan väg tillbaka. Importflödena (IMP-1/IMP-6) täcker det
  fallet. Därför ligger den bakom en bekräftelsedialog.
- **Room-halvan körs i en enda transaktion**, så en avbruten återställning inte kan lämna
  transaktioner utan sina fonder. DataStore är ett eget lager och skrivs **efter**
  databasen: misslyckas den har användaren kvar sin data och behöver sätta om ett par
  inställningar. Omvänd ordning hade gett återställda inställningar till en portfölj som
  aldrig kom fram.
- **Id:n bevaras vid insättning.** Facit refererar fonder via ISIN, men ett id som ändras
  vid varje återställning gör en öppen skärms "ta bort rad" tvetydig.
- **En fil från en nyare app avvisas** med eget felmeddelande i stället för att läsas in med
  bara de fält den här versionen känner igen. En delvis inläst säkerhetskopia är värre än
  ingen.
- **Filen är oskyddad klartext** med innehav och belopp. Kortets text säger det rakt ut;
  ändra inte det utan att ändra verkligheten först.
- **En migrering som gallrar data** (t.ex. retention på inspelade förslag) tar bort något som
  ingår i kontraktet. Det kräver ett uttryckligt krav i KRAVLISTA, inte bara en kodrad.

## Drive-backupen (TP-7 steg 2, SET-7) — byggd

En ren **transport** bakom samma sträng, inte en andra `BackupRepository`. Anroparen komponerar:
`backupRepository.export()` → `driveBackup.upload(json)` och `driveBackup.downloadLatest()` →
`backupRepository.restore(json)`. Ett nytt fält i kontraktet kräver därför **ingen** ändring här.

| Del | Var | Vad |
|---|---|---|
| `DriveBackupRepository` / `GoogleDriveBackupRepository` | `data/repository/DriveBackupRepository.kt` | `upload`, `downloadLatest`, `list` mot `appDataFolder`; utfallet som `DriveResult` (`Success`, `Error`, `NoAccount`, `NoBackupFound`, `NeedsAuthorization`). Scopet `drive.appdata` begärs separat från inloggningen (TP-6), först när backupen används. |
| `DriveBackupFiles` | `domain/usecase/DriveBackupFiles.kt` | Ren Kotlin: prefixet `fonder-backup-`, filnamn `fonder-backup-ÅÅÅÅMMDD-HHMM.json`, frågan `name contains 'fonder-backup-' and trashed = false`, och gallringen `staleFileIds` som behåller de **5** senaste (`KEEP_COUNT`). |
| `DriveBackupWorker` | `worker/DriveBackupWorker.kt` | Dygnsvis körning under eget unikt arbetsnamn `fonder_drive_backup` (`KEEP`, kräver nätverk); "Säkerhetskopiera nu" under `fonder_drive_backup_now`. `outcomeFor` mappar utfallet: utloggad = tyst klart, saknad auktorisering = flagga utan retry, fel = retry. Misslyckas exporten laddas ingenting upp. |

Regler som inte får brytas:

- `appDataFolder` är per Google Cloud-**projekt** och **delas med Dagboken**. Varje listning
  använder `DriveBackupFiles.QUERY`, och gallringen filtrerar om på `isOurs` – en ofiltrerad
  gallring skulle radera Dagbokens säkerhetskopior. Ändras prefix, fråga eller `KEEP_COUNT`:
  uppdatera `DriveBackupFilesTest` och SET-7 i samma PR.
- Kursuppdateringens "Uppdatera nu" ersätter (`REPLACE`) sitt eget arbete – därför har backupen
  ett eget arbetsnamn. Häng aldrig på den på `FundPriceUpdateWorker`.
- Inloggningen (TP-6) bär ingen användardata och ingår inte i kontraktet.
- Drive-anropen testas aldrig mot riktig Drive: logiken ligger i `DriveBackupFiles`
  (`DriveBackupFilesTest`) och `outcomeFor` (`DriveBackupOutcomeTest`).
- Konsolstegen (OAuth-klient, Drive API, SHA-1) står i [docs/GOOGLE-SETUP.md](../../../docs/GOOGLE-SETUP.md).
