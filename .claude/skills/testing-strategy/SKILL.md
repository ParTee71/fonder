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
| Room-DAO | `app/src/androidTest/…/data/room/*DaoTest.kt` | AndroidX Test, in-memory Room | `instrumented-tests` (bara PR) |
| Migrering | `app/src/androidTest/…/data/room/MigrationXYTest.kt` | rå `SQLiteDatabase` (skill `room-migrations`) | `instrumented-tests` |
| Backup-rundtur | `app/src/androidTest/…/data/repository/BackupRoundTripTest.kt` | riktig Room + riktig DataStore | `instrumented-tests` |
| Compose-UI (skärmar och komponenter) | `app/src/androidTest/…/ui/**` | `createComposeRule` mot `*Content` | `instrumented-tests` |
| WorkManager-schemaläggning | `app/src/androidTest/…/worker/` | `work-testing` | `instrumented-tests` |
| Compose-UI i JVM | `app/src/test/…/ui/**` (först: `ui/components/EmptyStateTest.kt`) | Robolectric (`@RunWith(RobolectricTestRunner::class)`) + `createComposeRule`; SDK, mått och `Application` i `app/src/test/resources/robolectric.properties` | `android.yml` (`build-and-test`) |
| Skärmdump | `app/src/test` + referensbilder i `app/src/test/screenshots/` | Roborazzi via `composeRule.captureLightAndDark(name) { … }`, ljust + mörkt – en per komponent i `ComponentScreenshotTest` och `FundLineChartScreenshotTest` | `android.yml` (`build-and-test`): `verifyRoborazziDebug` – samma körning som enhetstesterna, jämför mot referenserna med 0,1 % pixeltolerans och fäller vid skillnad; diffbilderna som artifact `screenshot-diffs`. Se "Skärmdumpsreferenser" nedan |
| Konsistens (regel 4) | `app/src/test/…/consistency/UiConsistencyTest.kt` mot hela `app/src/main` | Konsist + `ui-forbidden.txt`/`ui-allowlist.txt` | `android.yml` (`build-and-test`) |
| Dubbelkod (regel 4) | hela `app/src` (`main`, `test`, `androidTest`) mot `config/cpd-baseline.txt` | `./gradlew cpdCheck` (PMD CPD) | `android.yml` (`build-and-test`) |
| Hooken `regel4-check` och baslinjeskriptet | `.claude/hooks/test/regel4-check.test.mjs` | `node --test` | `android.yml` (`changes`, varje PR) |

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
| Delad komponent | Compose-test – nya i `app/src/test/…/ui/components/` med Robolectric (billigare än emulatorn), befintliga ligger i `androidTest/…/ui/components/`; skärmdump ljust + mörkt med `captureLightAndDark` |
| Bugfix | **Först** ett test som reproducerar buggen och faller, sedan fixen |

## DRY i tester (regel 4 gäller även här)

| Hjälpare | Var | Ersätter |
|---|---|---|
| `FakeFundMetadataRepository`, `FakeSwitchWatchRepository`, `FakeSuggestionRecordRepository`, `FakeSuggestionDismissalRepository`, `FakeCachedPriceRepository` | `app/src/test/…/data/repository/` | egna fakes per test |
| `testPreferencesRepository` | `app/src/test/…/testing/TestPreferences.kt` | egen DataStore-uppställning per test (på testets dispatcher) |
| `FakeFundPriceRefreshScheduler` | `app/src/test/…/worker/` | egen schemaläggare i ViewModel-tester |
| `captureLightAndDark` (extension på compose-regeln; `animated` för oändliga animationer, `screen` för dialoger, `ready = { isDrawn() }` för diagram) | `app/src/test/…/testing/Screenshots.kt` | egna Roborazzi-anrop och egen temaväxling. Anropa aldrig `captureRoboImage(path) { … }` bredvid en `createComposeRule` – det startar ett andra fönster och bilden blir tom |
| `MainDispatcherRule`, delade skärmkontrakt (`runListScreenContract`/`runEditScreenContract`) *(planerade)* | `app/src/test/…/testing/` | `Dispatchers.setMain` i varje test; tomt/fel/laddar och spara-aktivering per skärm |

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

## Skärmdumpsreferenser

Referensbilderna i `app/src/test/screenshots/` är facit för `verifyRoborazziDebug`, som CI kör i
stället för `testDebugUnitTest` (skill `ci-budget`). En vanlig `testDebugUnitTest` skriver och
jämför inga bilder.

- **Avsiktlig visuell ändring eller ny skärmdump:** `./gradlew :app:recordRoborazziDebug` lokalt
  (Android SDK i sessionen), granska de ändrade bilderna i `git diff --stat
  app/src/test/screenshots/` och checka in dem **i samma PR** som ändringen. Spela aldrig in för
  att få en oavsiktlig diff grön – rätta koden.
- **Rött i CI:** hämta artifacten `screenshot-diffs`; `<namn>_compare.png` visar referens, ny bild
  och diff sida vid sida. Lokalt: `./gradlew :app:verifyRoborazziDebug`, bilderna hamnar i
  `app/build/outputs/roborazzi/`.
- **Tolerans:** 0,1 % av pixlarna (`CHANGE_THRESHOLD` i `testing/Screenshots.kt`, enda stället) –
  för enstaka kantutjämnade pixlar mellan maskiner. Färg, form och avstånd fäller; ett enstaka
  ändrat tecken i en helskärmsbild (~250 av 4,9 miljoner pixlar) gör det inte, så texter
  kontrolleras i Compose-testerna. Höj inte toleransen för att få grönt – en bild som skiljer
  mellan körningar är flaky (se nedan) och ska göras deterministisk (`animated`, `ready`).

## Innan du anser dig klar

```bash
./gradlew :app:verifyRoborazziDebug            # som CI: enhetstester, Robolectric, UiConsistencyTest och skärmdumpsjämförelsen
./gradlew :app:recordRoborazziDebug            # bara vid avsiktlig visuell ändring – checka in bilderna
./gradlew cpdCheck                             # copy-paste-detektor (regel 4)
./gradlew :app:compileDebugAndroidTestKotlin   # instrumenttester ska minst kompilera
./gradlew :app:connectedDebugAndroidTest       # kräver emulator/enhet
node --test .claude/hooks/test/*.test.mjs      # hooken regel4-check och baslinjen (ingen SDK behövs)
```

**Kör det du ändrat lokalt innan varje push** när Android SDK finns (session-start-hooken
säger om den gör det) – en röd CI-runda är det dyraste som finns. Kör en Gradle-körning i
taget. `LC_ALL=C.UTF-8` (testnamn med å/ä/ö) sätts av `.claude/settings.json`. Saknas SDK:
installera den – det är alltid ok att installera Android SDK och liknande verktyg i sessionen
(`.claude/hooks/install-android-sdk.sh`, ≈450 MB, några minuter; skriptet skriver också
`sdk.dir` i `local.properties`). Går det inte får CI bekräfta – säg det i PR:en. `android.yml` kör kompilering och enhetstester på push och i PR som rör koden, och
emulatortesterna (`instrumented-tests`) **bara i PR** som rör `app/src/main`,
`app/src/androidTest` eller byggfilerna – en ren push ger ingen instrumenttäckning (skill
`ci-budget`). Vid rött CI – låt
agenten `ci-doktor` diagnostisera.

## Fallgropar

- **Första Gradle-körningen i en ny session** hämtar Gradle och beroendena (flera minuter).
  Kör den i bakgrunden och fortsätt med annat.
- **Plattformspaketet heter `android-37.0`**, inte `android-37`, från API 37.
- **Maven Central svarar 429** när många beroenden hämtas samtidigt – kör om med
  `--no-parallel --max-workers=2`, inte i en slinga.
- **Två Gradle-körningar samtidigt** låser varandra eller ger förvirrande cachefel – vänta in
  den första.

## Anti-mönster

- Ta bort, `@Ignore`:a eller försvaga ett rött test för att bli klar.
- Köra om ett test tills det blir grönt.
- Testa implementation (interna anrop) i stället för beteende.
- Ett test som går mot nätet eller mot riktig Drive.
- Ny funktion utan test "för att den är liten".
- Låta `main` kompilera medan en fake i test slutat matcha sitt interface.
