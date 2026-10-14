---
name: room-migrations
description: 'Fonders Room-schemamigreringar — varje databasändring måste migreras utan dataförlust (kärnan i datasäkerhetsregeln). Ladda denna ALLTID när du ändrar en @Entity, lägger till eller ändrar en kolumn eller tabell, höjer databasversionen eller rör Room-schemat. Trigger-ord: Room, migration, migrering, @Entity, schema, AppDatabase, databasversion, SupportSQLiteDatabase, execSQL, exportSchema, fallbackToDestructiveMigration, MigrationXYTest, kolumn, tabell, DAO.'
---

# Room-migreringar

En schemaändring utan migrering raderar **all** användardata vid uppdatering — den
allvarligaste formen av regel 1-brott. Se även skill `data-safety-backup` (rundturen) och
`android-data-layer` (DAO/repository).

## Nuläge

- `data/room/AppDatabase.kt` — `version = 19`, `exportSchema = true`. Migreringarna
  `MIGRATION_1_2 … MIGRATION_18_19` ligger i `companion object` och samlas i `MIGRATIONS`.
  Senaste: `MIGRATION_18_19` ändrar inget schema utan glömmer cachade nej på köpbarhet
  (`fund_metadata.availableAtHandelsbanken = 0` → `NULL`, #153). Före den: `MIGRATION_17_18` lägger `switch_watches.indexOnly` (`NOT NULL DEFAULT 0`, filtret "bara
  indexfonder", ANA-13, #150) — defaulten står också i entiteten (`@ColumnInfo(defaultValue = "0")`),
  så Rooms validering prövar den.
- Migreringstester: `Migration12Test … Migration1819Test` (ett per steg) i
  `androidTest/…/data/room/`, plus DAO-tester och `BackupRoundTripTest`. De körs bara i
  jobbet `instrumented-tests` i `android.yml`, alltså bara i PR som rör `app/src/main`,
  `app/src/androidTest` eller byggfilerna (skill `ci-budget`).
- `room.schemaLocation = $projectDir/schemas` är satt och `androidTest` mountar katalogen
  som assets. Schema-JSON:erna checkas in från och med version 15 (#125): `15.json`, `16.json`
  … — en ny version committas med sin JSON, så schemat kan diffas i PR:en.

## Migreringstester byggs för hand här

Fonder använder **inte** `MigrationTestHelper` med incheckade schema-JSON:er. Testerna
skapar i stället den gamla databasen direkt med `SQLiteDatabase.openOrCreateDatabase` och
rå `execSQL`, fyller den med data, kör migreringen och asserterar. Se `Migration1415Test`.

Det betyder två saker:

- **Du skriver ut det gamla schemat i testet.** Kopiera `CREATE TABLE`-satserna från
  föregående migrering eller från motsvarande äldre test — inte från den nuvarande
  entiteten, som redan har det nya fältet.
- **Ett test som glömmer en tabell missar inget.** Testet skapar bara de tabeller
  migreringen rör. Det är avsiktligt och gör testerna läsbara.

Följ mönstret i det senaste `MigrationXYTest` när du skriver nästa. Från och med
`Migration1516Test` läses det gamla schemats `CREATE`-satser ur den incheckade, frysta
`schemas/…/<version>.json` (androidTest-asset) och körs med rå `execSQL` — fortfarande inte
`MigrationTestHelper` och aldrig den nuvarande entiteten. Skälet är `cpdCheck`: version 15 har åtta
tabeller som alla måste finnas för Rooms schemaverifiering, och en tredje handskriven kopia av
dem vore ny dubbelkod. Raderna med data skrivs fortfarande för hand.

## Procedur

1. **Ändra entiteten** (`@Entity`) — ny kolumn, tabell eller index.
2. **Höj `version`** i `@Database` (15 → 16).
3. **Skriv `MIGRATION_15_16`** med exakt motsvarande `execSQL`. Nya `NOT NULL`-kolumner
   måste ha `DEFAULT`. Återskapa index som entiteten deklarerar.
4. **Lägg objektet i `MIGRATIONS`-arrayen** — annars används det aldrig.
5. **Skriv `Migration1516Test`**: bygg en v15-databas med data, kör migreringen, verifiera
   att den gamla datan finns kvar **och** att den nya kolumnen fick rätt defaultvärde.
6. **Uppdatera backup-kedjan** om fältet är användardata — skill `data-safety-backup`.
   Är det härledd cache (som `fund_prices`, `fx_rates`, `fund_metadata`) ska det uttryckligen
   **inte** in i kontraktet; motivet skrivs i PR-beskrivningen och i KDoc på `BackupPayload`.
7. **Uppdatera KRAVLISTA** — skill `requirements-kravlista`.

## Defaultvärdet är ett beslut, inte en formalitet

Vad en befintlig rad får i den nya kolumnen är en produktfråga. `MIGRATION_13_14` visar
principen: `shownAlternativeIsinsJson` fick `'[]'` och **inte** en lista härledd ur
`cheapestAlternativeIsin`, eftersom en gammal rad bara vet vilket alternativ som var
billigast — de övriga går inte att återskapa, och att fylla på med det enda kända hade sett
ut som "jämförelsen visade ett alternativ" när den i själva verket visade tre.

Fråga alltid: *ser det här defaultvärdet ut som ett riktigt värde för användaren?* Om ja,
och det inte är sant, välj ett som är ärligt tomt i stället.

## Förbjudet

- **`fallbackToDestructiveMigration()`** i produktionsvägen — raderar all data. Finns inte i
  projektet; lägg inte till det.
- **Höja versionen utan ett migreringsobjekt** → krasch eller datatapp vid uppdatering.
- **Ändra en gammal, redan släppt migrering** → enheter som kört den får ett inkonsekvent
  schema. Lägg en ny migrering i stället.
- **En migrering som tyst gallrar data.** Retention är ett krav, inte en implementationsdetalj
  — den ska stå i KRAVLISTA innan den står i SQL.
- **Checka in schema-JSON utan beslut.** Att börja checka in `app/schemas/` (och gå över till
  `MigrationTestHelper`) är ett arkitekturval för agenten `arkitekt`, i ett eget issue.

## Checklista

- [ ] Entitet ändrad + `version` höjd + `MIGRATION_N_N+1` skriven och lagd i `MIGRATIONS`.
- [ ] Nya `NOT NULL`-kolumner har `DEFAULT`; index återskapade.
- [ ] Defaultvärdet är ärligt — det påstår inget som inte är sant om gamla rader.
- [ ] `MigrationXYTest` bevisar att befintlig data överlever.
- [ ] Backup-kedja + rundturstest uppdaterade, eller ett uttryckligt motiv till varför fältet
      står utanför kontraktet.
- [ ] Ingen `fallbackToDestructiveMigration` i produktionsvägen.
