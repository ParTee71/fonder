---
name: data-safety-backup
description: Fonders datasäkerhetsregel (regel 1) — backup och restore får ALDRIG tappa användardata. Ladda denna ALLTID när du lägger till, ändrar eller tar bort persisterad data: en Room-entitet eller -kolumn, ett DataStore-värde, en domänmodell som sparas, eller något som rör backup/restore/export/import/JSON. Trigger-ord: backup, restore, återställ, säkerhetskopia, BackupPayload, BackupSerializer, LocalBackupRepository, BackupRoundTripTest, fältvakt, formatVersion, DataStore, PreferencesRepository, SAF, rundtur, round-trip, Drive, appDataFolder.
---

# Datasäkerhet: backup & restore

**Invariant:** All persisterad användardata måste överleva en **backup → restore-rundtur**
med identiskt innehåll. Ett fält som läggs till utan att komma med i backup-kedjan är en
regression även om appen kompilerar och alla gamla tester är gröna — datan faller tyst ur
varje framtida säkerhetskopia och märks först när någon behöver den.

Relaterade krav: **NFR-1**, **TP-7**, **SET-6**.

## Kedjan (var datan passerar)

```
Room + DataStore  →  BackupPayload  →  BackupSerializer.encode  →  JSON-sträng  →  SAF-fil
       ▲                                                                │
       └────  LocalBackupRepository.restore  ←  BackupSerializer.decode ┘
```

Filer under `data/repository/`:

- **`BackupPayload.kt`** — *kontraktet*. Byggt på domänmodellerna, inte på egna DTO:er.
  Det här är listan över vad som räknas som användardata.
- **`BackupSerializer.kt`** — *formatet*. Äger `formatVersion` och kastar
  `BackupFormatException` med `Reason.UNSUPPORTED_VERSION` / `Reason.UNREADABLE`.
- **`BackupRepository.kt`** — interfacet (`export(): Result<String>`,
  `restore(json): Result<RestoreSummary>`) och `LocalBackupRepository`.

**Kontraktet är en `String`, inte en `Uri` eller en fil.** Transporten hör inte hemma i
repositoryt: SAF skriver strängen i dag (SET-6), Drive `appDataFolder` skriver samma sträng
i morgon (TP-7 steg 2), och formatet ändras inte av bytet. Bygg aldrig in en `Uri`, en
`ContentResolver` eller ett `File` här — den I/O:n ligger i skärmen.

## Vad som ingår — och vad som medvetet inte gör det

**Ingår:** fonder, transaktioner, samtliga inspelade förslag (`SuggestionRecord`, inkl.
`followed`, `switchValueKr`, `batchEpochMillis`, `kind`), riskprofil (SET-3, inkl.
legacy-fältet `targetRiskLevel`), kontotyp (SET-4), temaval.

**Ingår inte, med avsikt:** `fund_prices`, `fx_rates`, `fund_metadata`. Härledd cache som
hämtas om från källan och som skulle mångdubbla filen utan att skydda något oåterskapbart.
De **töms heller inte** av en återställning, så lokal kurshistorik behålls.

Samma gräns gäller cache-metadata i DataStore: `lastPriceSyncEpochMillis` och
`fundFilterVocabulary` är inte användardata. Riskprofilen och kontotypen **är** det.

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

## Drive-backupen (TP-7 steg 2) — inte byggd

Bygg den **inte** som sidoeffekt av något annat issue. När den byggs är den ett rent
transportbyte bakom `BackupRepository`; formatet är redan låst och testat. Två saker gäller
då, och de står i [docs/GOOGLE-SETUP.md](../../../docs/GOOGLE-SETUP.md):

- `appDataFolder` **delas med Dagboken** (samma Cloud-projekt). Filnamn prefixas
  `fonder-backup-`, och **varje** listning filtrerar på prefixet. En obefiltrerad
  gallringsrutin skulle radera Dagbokens säkerhetskopior.
- Inloggningen (TP-6) bär ingen användardata i sig och ingår inte i kontraktet — en session
  hör till enheten, inte till portföljen.
