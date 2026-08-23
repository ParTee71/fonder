---
name: testing-strategy
description: Fonders testregel (regel 2) — ingen beteendeändring utan tester på rätt nivå(er), och inga flaky tester. Ladda denna ALLTID när du lägger till, ändrar eller fixar funktionalitet, en ViewModel, ett use case, ett repository, en DAO, en composable, en parser eller en bugg. Trigger-ord: test, tester, JUnit, MockK, Turbine, fake, ViewModelTest, instrument, androidTest, Compose UI-test, createComposeRule, StandardTestDispatcher, flaky, regression, TDD, täckning.
---

# Teststrategi: tester på alla nivåer

**Regel:** En ändring av synligt beteende är inte klar förrän tester lagts till eller
uppdaterats på varje berörd nivå. Befintliga tester som påverkas **uppdateras** så att de
speglar det nya beteendet — de tas aldrig bort eller försvagas för att bli gröna.
Krav: **NFR-2**.

**Flaky tester är inte tillåtna.** Kör aldrig om ett test som ibland faller för att få
grönt. Ett test som ibland faller är en bugg — i testet eller i koden — och ska göras
deterministiskt. Se "Flakiness som redan bitit" nedan; varje punkt där är ett verkligt fall.

## Nivåer

| Nivå | Källkatalog | Verktyg | Testar |
|---|---|---|---|
| Enhet | `app/src/test/kotlin/…` | JUnit, MockK, Turbine, kotlinx-coroutines-test | ViewModels, `domain/usecase/`, parsers, repository-logik, ren Kotlin. |
| Instrument | `app/src/androidTest/kotlin/…` | AndroidX Test, Compose-test, Room | Compose-UI, Room-DAO, migreringar, backup-rundturen. |

Testernas paketstruktur **speglar** `main`: en ViewModel i `ui/settings/` testas i
`test/…/ui/settings/`.

## Vad ska testas när du ändrar X

| Ändring | Lägg till / uppdatera |
|---|---|
| **ViewModel / UiState** | Enhetstest: initialt state, varje action → nytt state, felväg. Observera `StateFlow` med Turbine. |
| **Use case i `domain/usecase/`** | Enhetstest med kantfall. De här är rena och testbara med flit — `PortfolioCalc`, `RealizedGainCalculator`, `SwitchPlanCalc`, `FeeComparisonCalc`, `NavCalendar` m.fl. Lägg logiken här, inte i en ViewModel, just för att den ska gå att testa så här. |
| **Repository** | Enhetstest mot en **fake** DAO/källa, inte mockad Room. Verifiera `Flow`-emission och att fel mappas till `Result` i repository-lagret. |
| **DAO / `@Query`** | Instrumenttest mot Room (`FundDaoTest`, `SuggestionRecordDaoTest` visar mönstret). |
| **Room-schemaändring** | Instrument-`MigrationXYTest` — skill `room-migrations`. |
| **Persisterad data** | Fältvakt + rundtur — skill `data-safety-backup`. |
| **Composable / skärm** | Compose-UI-test mot den tillståndsdrivna `*Content`-funktionen: rendering, knapp aktiverad/inaktiverad, klick → callback. |
| **Extern källa (Avanza/Handelsbanken/Riksbank)** | Enhetstest av parsern mot **sparad** HTML/JSON. Aldrig ett test som går mot nätet. |
| **Bugfix** | Skriv först ett test som **reproducerar buggen** (faller), fixa sedan. |

## Projektmönster

- **Fakes framför mocks för datalagret.** Repositories och DAO:er fakeas med
  `object : Interface { … }` direkt i testfilen — se `SettingsViewModelTest`. Lägger du en
  metod på ett interface måste alla fakes uppdateras, annars bryts testbygget.
  MockK används för det som inte rimligen går att fejka (t.ex. ett Android-`Context`).
- **Separera skärm från tillstånd.** Varje skärm har en tillståndsdriven `*Content`-funktion
  utan ViewModel/Hilt-beroende (`SettingsContent`, se issue #27). Instrumenttesterna
  renderar *den*, med ett handskrivet `UiState` — inte hela skärmen.
- **Turbine** för `StateFlow`/`Flow`-assertions, inte manuell `collect`.
- **`StandardTestDispatcher` + `Dispatchers.setMain`** i ViewModel-tester, med
  `resetMain()` i `@After`.
- **Svenska testnamn i backticks** för enhetstester, `snake_case` för instrumenttester —
  följ filen du redigerar.

## Flakiness som redan bitit

Tre verkliga fall. De är inte hypotetiska och de kostade tid att hitta:

1. **DataStore på sin egen dispatcher.** `PreferenceDataStoreFactory.create` utan `scope`
   skriver på en `Dispatchers.IO`-bunden scope, frikopplad från testets
   `StandardTestDispatcher`. `awaitItem()` väntade då på riktig klocktid och överskred
   ibland Turbines timeout under CI-belastning. Ge alltid DataStore testets dispatcher:
   `PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher + SupervisorJob()), …)`.
2. **`performScrollTo` före varje knappklick i Inställningar.** Skärmen är en
   `verticalScroll`-kolumn som växer med varje nytt kort. En knapp under skärmkanten är
   fortfarande *komponerad* — `assertExists` passerar, `performClick` kastar inget, men
   klicket landar utanför noden och callbacken uteblir. Har bitit två gånger (#78, #80).
3. **Inga kommatecken i instrumenterade testnamn.** Ett instrumenterat testnamn blir ett
   dex-metodnamn, och D8 vägrar representera `,` — `dexBuilderDebugAndroidTest` föll innan
   emulatorn ens startade. Mellanslag och bindestreck går bra.

Ser du ett test falla ibland: leta efter en av de här formerna — en tidsberoende väntan, ett
klick som inte landar, eller delat tillstånd mellan tester. Kör inte om.

## Assertera på beteende, inte på att vi inte vet

En återkommande princip i appen (ANA-4): saknad data ska ge "kunde inte beräknas", aldrig 0
eller ett tomt värde som ser ut som ett svar. Testa **den** skillnaden — att ett saknat NAV
ger "ej utvärderat" och inte noll är precis den bugg som annars slinker igenom.

## Innan du anser dig klar

```bash
./gradlew :app:testDebugUnitTest               # enhetstester
./gradlew :app:compileDebugAndroidTestKotlin   # instrumenttester ska minst kompilera
./gradlew :app:connectedDebugAndroidTest       # kräver emulator/enhet
```

I fjärr-/telefonsessioner finns ingen Android SDK — **kör inte `./gradlew` där**. Pusha och
lita på CI. `android.yml` kör kompilering + enhetstester på push och PR mot `master`;
`instrumented.yml` kör emulatortesterna **bara på pull requests**, så en ren push ger ingen
instrumenttäckning. Öppna PR:en.

## Anti-mönster

- Ta bort eller `@Ignore`:a ett rött test för att bli klar.
- Köra om ett flaky test i stället för att hitta orsaken.
- Testa implementation i stället för beteende (assertion på interna anrop när resultatet
  räcker).
- Lägga en ny funktion utan test "för att den är liten".
- Låta `main` kompilera medan en fake i test slutat matcha sitt interface.
