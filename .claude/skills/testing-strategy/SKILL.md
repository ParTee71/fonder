---
name: testing-strategy
description: 'Fonders testregel (regel 2, NFR-2) — ingen beteendeändring utan tester på rätt nivå(er), och flaky tester är buggar. Ladda denna ALLTID när du lägger till, ändrar eller fixar funktionalitet, en ViewModel, ett use case, ett repository, en DAO, en migrering, en parser, en worker, en composable eller en bugg. Trigger-ord: test, tester, testa, JUnit, MockK, Turbine, fake, ViewModelTest, instrument, androidTest, Compose UI-test, createComposeRule, StandardTestDispatcher, MigrationXYTest, BackupRoundTripTest, fältvakt, Robolectric, Roborazzi, skärmdump, Konsist, kontraktstest, emulator, regression, flaky, instabil, täckning, TDD.'
---

# Teststrategi: tester på alla nivåer

**Regel:** en ändring av beteende är inte klar förrän tester lagts till eller uppdaterats
på varje berörd nivå. Befintliga tester **uppdateras** så att de speglar det nya
beteendet – de tas aldrig bort, `@Ignore`:as eller försvagas för att bli gröna.
**Ett test som ibland faller är en bugg** – hitta grundorsaken (klocka, dispatcher, delat
tillstånd, ett klick som inte landar) och gör det deterministiskt. Kör aldrig bara om.

Krav: NFR-2 (tester), NFR-1 (rundtur), NFR-3 (migrering) i KRAVLISTA.md.

## Nivåer (enda källan för denna tabell)

| Nivå | Var | Verktyg | Körs i CI |
|---|---|---|---|
| Domän, use cases, parsers, formatering | `app/src/test` | JUnit4, kotlin.test | `android.yml` (push + PR) |
| ViewModel, repository, worker-logik | `app/src/test` | JUnit4, MockK, Turbine, kotlinx-coroutines-test, fakes | `android.yml` |
| Backupformatet + fältvakten | `app/src/test/…/data/repository/BackupSerializerTest.kt` | JUnit4 | `android.yml` |
| Room-DAO | `app/src/androidTest/…/data/room/*DaoTest.kt` | AndroidX Test, in-memory Room | `instrumented.yml` (bara PR) |
| Migrering | `app/src/androidTest/…/data/room/MigrationXYTest.kt` | rå `SQLiteDatabase` (skill `room-migrations`) | `instrumented.yml` |
| Backup-rundtur | `app/src/androidTest/…/data/repository/BackupRoundTripTest.kt` | riktig Room + riktig DataStore | `instrumented.yml` |
| Compose-UI (skärmar och komponenter) | `app/src/androidTest/…/ui/**` | `createComposeRule` mot `*Content` | `instrumented.yml` |
| WorkManager-schemaläggning | `app/src/androidTest/…/worker/` | `work-testing` | `instrumented.yml` |
| Compose-UI i JVM *(kommer i verkstads-PR:en)* | `app/src/test` | Robolectric + `ui-test-junit4` | – |
| Skärmdump *(kommer i verkstads-PR:en)* | `app/src/test` + referensbilder | Roborazzi, ljust + mörkt | – |
| Konsistens och dubbelkod *(kommer i verkstads-PR:en)* | `app/src/test`, hela `app/src` | Konsist `UiConsistencyTest`, `cpdCheck` | – |

Vad som körs när i CI står i skill `ci-budget` (enda källan). **Selektiv körning ändrar inte
kravet på att skriva tester.**

Testernas paketstruktur **speglar** `main`: en ViewModel i `ui/settings/` testas i
`test/…/ui/settings/`.

## Vad ska testas när du ändrar X

| Ändring | Lägg till / uppdatera |
|---|---|
| Use case i `domain/usecase/` | Enhetstest med kantfallen – `PortfolioCalc`, `RealizedGainCalculator`, `SwitchPlanCalc`, `NavCalendar` m.fl. Logiken bor här just för att den ska gå att testa så. |
| ViewModel / UiState | Initialt state, varje händelse → nytt state, felväg; Turbine; `StandardTestDispatcher` + `Dispatchers.setMain`/`resetMain()` |
| Repository | Mot en **fake** DAO/källa, inte mockad Room; verifiera `Flow`-emission och att fel mappas i repository-lagret |
| DAO / `@Query` | Instrumenttest mot in-memory Room (`FundDaoTest`, `SuggestionRecordDaoTest`) |
| Room-schema | `MigrationXYTest` – skill `room-migrations` |
| Persisterad data | Fältvakten i `BackupSerializerTest` + `BackupRoundTripTest` – skill `data-safety-backup` |
| Drive-backup | Ren logik i `DriveBackupFiles` (`DriveBackupFilesTest`) och `outcomeFor` (`DriveBackupOutcomeTest`); aldrig mot riktig Drive |
| Extern källa (Avanza, Handelsbanken, Riksbanken) | Parsertest mot **sparad** HTML/JSON. Aldrig ett test mot nätet. |
| Skärm | Compose-test mot den tillståndsdrivna `*Content`-funktionen med handskrivet `UiState`: rendering, aktiverad/inaktiverad, klick → callback |
| Delad komponent | Compose-test i `androidTest/…/ui/components/` (alla befintliga har ett); skärmdump ljust + mörkt när Roborazzi finns |
| Bugfix | **Först** ett test som reproducerar buggen och faller, sedan fixen |

## DRY i tester (regel 4 gäller även här)

| Hjälpare | Var | Ersätter |
|---|---|---|
| `FakeFundMetadataRepository`, `FakeSwitchWatchRepository` | `app/src/test/…/data/repository/` | egna fakes per test |
| `FakeFundPriceRefreshScheduler` | `app/src/test/…/worker/` | egen schemaläggare i ViewModel-tester |
| `MainDispatcherRule`, delade skärmkontrakt (`runListScreenContract`/`runEditScreenContract`), `captureLightAndDark` *(kommer i verkstads-PR:en)* | `app/src/test/…/testing/` | `Dispatchers.setMain` i varje test; tomt/fel/laddar och spara-aktivering per skärm; egna Roborazzi-anrop |

Behövs samma uppställning på två ställen → gör en hjälpare innan det tredje. Lägger du en
metod på ett interface måste **alla** fakes uppdateras, annars bryts testbygget.

## Projektmönster

- **Fake framför mock för datalagret.** `object : Interface { … }` i testfilen eller en delad
  `Fake*`. MockK för det som inte rimligen går att fejka (t.ex. ett Android-`Context`).
- **Separera skärm från tillstånd.** Varje skärm har en `*Content`-funktion utan
  ViewModel/Hilt (`SettingsContent`, #27); testerna renderar den.
- **Injicera dispatcher**; kalla aldrig `Dispatchers.IO` direkt i testbar kod.
- **Turbine** för `StateFlow`/`Flow`, inte manuell `collect`.
- **Testnamn:** svenska meningar i backticks i `app/src/test`; i `androidTest` vanliga
  identifierare (`snake_case`) utan kommatecken – D8 dexar inte sådana metodnamn.

## Flakiness som redan bitit

Verkliga fall – de kostade tid att hitta:

1. **DataStore på sin egen dispatcher.** `PreferenceDataStoreFactory.create` utan `scope`
   skriver på en `Dispatchers.IO`-scope frikopplad från testets `StandardTestDispatcher`;
   `awaitItem()` väntade på riktig klocktid och överskred ibland Turbines timeout i CI. Ge
   alltid DataStore testets dispatcher:
   `PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher + SupervisorJob()), …)`.
2. **`performScrollTo` före varje knappklick i Inställningar.** En knapp under skärmkanten är
   komponerad – `assertExists` passerar, `performClick` kastar inget, men klicket landar
   utanför noden och callbacken uteblir. Har bitit två gånger (#78, #80). I en lat lista:
   `performScrollToIndex` på listans testtagg (UI-5).
3. **Inga kommatecken i instrumenterade testnamn.** D8 vägrar representera `,` i ett
   metodnamn – `dexBuilderDebugAndroidTest` föll innan emulatorn startade.

Ser du ett test falla ibland: leta efter en tidsberoende väntan, ett klick som inte landar
eller delat tillstånd mellan tester. Kör inte om.

## Assertera på beteende, inte på att vi inte vet

Saknad data ska ge "kunde inte beräknas", aldrig 0 eller ett tomt värde som ser ut som ett
svar (ANA-4). Testa **den** skillnaden – att ett saknat NAV ger "ej utvärderat" och inte noll
är precis den bugg som annars slinker igenom.

## Innan du anser dig klar

```bash
./gradlew :app:testDebugUnitTest               # enhetstester
./gradlew :app:compileDebugAndroidTestKotlin   # instrumenttester ska minst kompilera
./gradlew :app:connectedDebugAndroidTest       # kräver emulator/enhet
```

**Kör det du ändrat lokalt innan varje push** när Android SDK finns (session-start-hooken
säger om den gör det) – en röd CI-runda är det dyraste som finns. Kör en Gradle-körning i
taget och sätt `LC_ALL=C.UTF-8` (testnamn med å/ä/ö). Saknas SDK: CI får bekräfta – säg det i
PR:en. `android.yml` kör kompilering och enhetstester på push och PR; `instrumented.yml` kör
emulatortesterna **bara på PR**, så en ren push ger ingen instrumenttäckning. Vid rött CI – låt
agenten `ci-doktor` diagnostisera.

## Anti-mönster

- Ta bort, `@Ignore`:a eller försvaga ett rött test för att bli klar.
- Köra om ett test tills det blir grönt.
- Testa implementation (interna anrop) i stället för beteende.
- Ett test som går mot nätet eller mot riktig Drive.
- Ny funktion utan test "för att den är liten".
- Låta `main` kompilera medan en fake i test slutat matcha sitt interface.
