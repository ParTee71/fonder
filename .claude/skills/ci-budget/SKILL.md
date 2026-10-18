---
name: ci-budget
description: 'Fonders CI-regel (regel 5) — en PR ska kosta ca 8 Actions-minuter, plus instrumenttesterna bara när PR:en rör koden de testar; release-bygge (R8) och lint körs veckovis, på begäran och vid release. Enda källan för tabellen över vad som körs när. Ladda denna ALLTID när du rör .github/workflows, Gradle-inställningar, nya beroenden eller plugins, testnivåer eller när CI är långsam. Trigger-ord: CI, GitHub Actions, workflow, android.yml, quality.yml, pipeline, byggtid, Actions-minuter, cache, paths-filter, concurrency, emulator, instrumenttester, lint, release-bygge, R8, assembleRelease, nytt beroende, långsam, budget.'
---

# CI-budget

**Regel:** en PR ska kosta ca **8 Actions-minuter** på varm cache, plus instrumenttesterna på
emulator **bara** när PR:en rör koden de testar. Release-bygge (R8) och lint hör inte till
PR-flödet; de körs på begäran, veckovis och vid release. Nya beroenden eller plugins får inte
fördubbla byggtiden – ange uppmätt effekt i PR:en. Att slippa köra ett test är aldrig ett skäl
att inte skriva det (regel 2).

**Läget i dag** (uppskattat, varm cache, varje jobb debiteras uppåt till hel minut):

| Händelse | Jobb som körs | Actions-minuter |
|---|---|---|
| PR som bara rör dokument, `.claude/` eller andra workflows | `changes` (med hooktestet) + `ci-ok` | ~2 |
| PR som rör kod eller byggfiler | `changes` + `build-and-test` + `ci-ok` | ~6–7 |
| … och `app/src/main/**`, `app/src/androidTest/**` eller byggfiler | dessutom `instrumented-tests` | +~10–15 |
| Push till `master` | `changes` + `build-and-test` + `ci-ok` | ~6–7 |
| Veckovis `quality.yml` (bara om `master` ändrats) | `quality` | ~8–12 |
| Release (`release.yml`) | `gate` + `release` (signerat R8-bygge och `lintDebug`) | ~9–12 (före grindarna ~6–8) |
| … och instrumenttesternas kod inte grön i en PR med samma innehåll | dessutom `instrumented` (`instrumented.yml`) | +~10–15 |

Före CI-etappen kostade varje PR-push ~17–20 minuter (`build-and-test` + `release-build` +
emulatorn i `instrumented.yml`, oavsett vad PR:en ändrade). Siffrorna ovan är uppskattningar –
ersätt dem med uppmätta värden när de finns.

**Skärmdumpsjämförelsen** (`verifyRoborazziDebug` i stället för `testDebugUnitTest`) lade
inget jobb till och uppskattas till **+0–10 s** per kod-PR (se "När du lägger till något").

**Verkstads-PR:en** lade till regel 4-kontrollerna och JVM-UI-testerna i de befintliga jobben –
inga nya jobb. Uppskattat tillägg (varm cache): hooktestet ~2 s i `changes`; i `build-and-test`
`cpdCheck` ~7 s (PMD CPD över ~270 filer, uppmätt lokalt), `UiConsistencyTest` (Konsist tolkar
`app/src/main`) ~5–10 s, Robolectric-uppstart ~10–20 s för den första testklassen plus
SDK-jaren (~100 MB) en gång per cachenyckel, och något längre testkompilering – sammanlagt
**~+0,5–1 minut** per kod-PR. Ersätt med uppmätt värde efter första körningen.

## Vad som körs när (enda källan för denna tabell)

Tabellen beskriver workflowfilerna **som de är** och ska kontrolleras mot dem vid varje ändring i
`.github/workflows/` – ändra båda i samma PR. Ett kontrollskript som fäller bygget vid skillnad
finns inte ännu (Dagbokens `check-ci-table.mjs` är förlagan).

<!-- ci-table:start -->
| Workflow · jobb | Trigger | Villkor | Steg |
|---|---|---|---|
| `android.yml` · `changes` (timeout 5) | `pull_request` och `push` mot `master`, `workflow_dispatch` (input `instrumented`) | alltid | nyckelgrind (`git ls-files '*.jks' '*.keystore' '*.p12' '*.pem'` utom `app/debug.keystore`); hooktest `node --test .claude/hooks/test/*.test.mjs` (regel4-hooken, baslinjen i `ui-allowlist.txt`, hookkommandona); i PR `dorny/paths-filter@v3` med filtren `app` (`app/src/**`, `app/build.gradle.kts`, `config/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `gradle.properties`, `.github/workflows/android.yml`) och `instrumented` (`app/src/main/**`, `app/src/androidTest/**` och samma byggfiler, inte workflowen); sammanfattning "Vad körs" |
| `android.yml` · `build-and-test` (timeout 8) | som ovan | PR: filtret `app`; `push`/`workflow_dispatch`: alltid | `setup-android` (JDK 21 och `setup-gradle`, cache skrivs från `master`, läses i PR), `actions/cache@v4` för Robolectrics SDK-jar (`~/.m2/repository/org/robolectric`), **ett** Gradle-anrop `--continue :app:compileDebugKotlin :app:verifyRoborazziDebug :app:compileDebugAndroidTestKotlin :cpdCheck` med build cache (`org.gradle.caching=true`) – `verifyRoborazziDebug` kör `testDebugUnitTest` (alla enhetstester, Robolectric-testerna och `UiConsistencyTest`) **en gång** med skärmdumpsjämförelsen mot `app/src/test/screenshots/` påslagen (tolerans 0,1 % pixlar i `captureLightAndDark`); testrapport och CPD-rapport som artifact (`test-results`, `if: always()`), vid fel även diffbilderna (`screenshot-diffs`: `app/build/outputs/roborazzi/`, `app/build/test-results/roborazzi/`, `app/build/reports/roborazzi/`; `if: failure()`, 7 dagar) |
| `android.yml` · `instrumented-tests` | som ovan | PR: filtret `instrumented`; `workflow_dispatch`: input `instrumented`; aldrig på `master`-push. `needs: build-and-test` – startar inte om bygget är rött | anropar `instrumented.yml` (check-run `instrumented-tests / instrumented`) |
| `instrumented.yml` · `instrumented` (timeout 18) | bara `workflow_call` (från `android.yml` och `release.yml`) | – | `setup-android`, KVM, `reactivecircus/android-emulator-runner@v2` (API 36, `google_apis`, x86_64, `pixel_6`) med `:app:connectedDebugAndroidTest`; fel-stackar och rapport som artifact. Ingen AVD-cache |
| `android.yml` · `ci-ok` (timeout 2) | som ovan | alltid (`if: always()`) | grönt när alla jobb ovan blivit `success` eller `skipped` – **enda obligatoriska statuskravet** |
| `quality.yml` · `quality` (timeout 20) | `schedule` måndagar 04:23 UTC, `workflow_dispatch` | schemalagd körning hoppas över om senaste gröna `quality.yml` gällde samma commit | `setup-android` (cachen bara läsning), `:app:assembleRelease` osignerat och sedan `:app:lintDebug` (separata anrop – i samma anrop krockade debug- och release-KSP om Room-schemat) (R8 + resursskrympning); lintrapport som artifact vid fel |
| `release-debug.yml` · `release-debug` | bara `workflow_dispatch` | – | `:app:assembleDebug` (valfritt `-PversionNameOverride`), ersätter den rullande releasen `debug-snapshot` |
| `release.yml` · `gate` (timeout 5) | tagg `v*` eller `workflow_dispatch` (`version_name`, `publish_release`) | alltid | stannar om commiten inte ligger på `master` eller om check-run `ci-ok` på exakt commiten inte är `completed success`; löser taggen (vägrar om den finns); `run_instrumented=false` bara om en grön PR-körning av `android.yml` har ett grönt instrumenttestjobb på en commit vars sökvägar i filtret `instrumented` (läst ur `android.yml` med `yq`) är oförändrade |
| `release.yml` · `instrumented` | som ovan | `gate`: `run_instrumented` | anropar `instrumented.yml` |
| `release.yml` · `release` (timeout 30) | som ovan | `gate` grön och `instrumented` `success` eller `skipped` | avkodar keystore ur secrets, `:app:assembleRelease` signerat, sedan `:app:lintDebug` i eget anrop (KSP-krocken om Room-schemat), lintrapport vid fel, artifact, GitHub Release, raderar keystore. Enhetstester och skärmdumpar körs inte om – `ci-ok` på commiten är beviset |
<!-- ci-table:end -->

`concurrency: ci-${{ github.workflow }}-${{ github.ref }}` med `cancel-in-progress` bara för
`pull_request` – en ny push till PR:en avbryter den förra körningen; `master` avbryts aldrig.

**Körs emulatortesterna måste de vara gröna för merge.** `ci-ok` har `instrumented-tests` i
`needs` och fäller på allt utom `success` och `skipped`: ett rött eller avbrutet
`instrumented-tests` gör `ci-ok` rött och blockerar PR:en, ett överhoppat (filtret
`instrumented` orört) gör det inte.

## Kända problem

1. ~~Release-bygget (R8) körs i varje PR~~ *(löst i CI-etappen: `quality.yml` veckovis och `release.yml`, NFR-2)*.
2. ~~`instrumented.yml` saknar paths-filter~~ *(löst: jobbet `instrumented-tests` i `android.yml` bakom filtret `instrumented`)*.
3. ~~Ingen `concurrency`/`cancel-in-progress`~~ *(löst i `android.yml`)*.
4. ~~`cancel-instrumented-on-failure` fungerar inte i PR~~ *(löst: borttaget, ersatt av `needs` + `concurrency`)*.
5. ~~Fyra separata Gradle-anrop~~ *(löst: ett anrop)*.
6. ~~Ingen `setup-gradle` och ingen build cache~~ *(löst; configuration cache är fortfarande av – prövas separat)*.
7. ~~Setup-stegen är kopierade mellan workflows~~ *(löst: JDK 21 och `setup-gradle` i composite action `.github/actions/setup-android`, som alla fem arbetsflöden använder; JDK 21 eftersom Robolectric kräver det för SDK 36, koden byggs för Java 17)*.
8. ~~`release.yml` kör varken lint eller instrumenttester~~ *(löst: grinden `gate`, `instrumented.yml` och `lintDebug` i `release`, Mats beslut 2026-10-18)*.
9. Inget kontrollskript håller tabellen ovan i synk med workflowfilerna.

## Tekniker som håller tiden nere

- `gradle/actions/setup-gradle`: cachen skrivs från `master`, läses i PR.
- `org.gradle.caching=true` och en Gradle-körning per jobb (`--continue`).
- `concurrency` per ref, `cancel-in-progress` bara för PR.
- Ett snabbt `changes`-jobb (`dorny/paths-filter`) avgör vilka jobb som körs; `ci-ok`
  (`if: always()`) är det enda obligatoriska statuskravet, eftersom hoppade jobb inte är ett
  pålitligt statuskrav.
- Varje jobb debiteras uppåt till hel minut – slå ihop korta steg hellre än att lägga till jobb.

## När du lägger till något

- **Nytt beroende eller plugin:** ange uppmätt byggtidseffekt i PR-beskrivningen. Ny R8-känslig
  dependency får keep-regler i `app/proguard-rules.pro` (skill `data-privacy-security`), och
  `quality.yml` körs på PR-grenen för hand (`workflow_dispatch`) så att R8 prövas före merge.
- **Ny testnivå eller nytt verktyg** (som Robolectric, Konsist och `cpdCheck` i verkstads-PR:en):
  lägg det i JVM-jobbet, aldrig i emulatorjobbet om det inte kräver enhet. Snabba kontroller utan
  JDK (som hooktestet) läggs som steg i `changes`, inte som eget jobb.
- **Skärmdumpar (Roborazzi):** `verifyRoborazziDebug` ersätter `testDebugUnitTest` i
  `build-and-test` – samma testtask, så testerna körs en gång och kostnaden är i stort sett
  oförändrad (lokalt, 2 kärnor: `testDebugUnitTest` ~61 s mot `verifyRoborazziDebug` ~55–68 s
  för 1 090 tester och 74 bilder; uppskattat **+0–10 s** per kod-PR, plus uppladdningen av
  diffbilderna bara vid fel). `testDebugUnitTest` behöver inte stå med i anropet – det är redan
  med; ett separat anrop utan verifiering skulle däremot köra testerna en gång till. Referenserna spelas in lokalt (skill `testing-strategy`) och
  checkas in; en inspelningsworkflow behövs inte så länge sessionen har Android SDK.
- **Över budget:** eget issue med mätning och förslag – aldrig en tyst höjning av timeouten.
- **Lokalt före push** (skill `testing-strategy`): kör de tasks ändringen berör. Lokala körningar
  kostar inga Actions-minuter; en röd CI-runda gör det.

## Anti-mönster

- `paths-ignore` på workflow-nivå (dokumentkontroller skulle aldrig köras).
- Göra ett villkorat jobb till obligatoriskt statuskrav (ett hoppat jobb blockerar PR:en).
- Starta emulatorn "bara den här gången" i en PR som inte rör instrumenttesternas kod.
- Kopiera setup-steg mellan workflows i stället för en composite action.
- Höja `timeout-minutes` i stället för att hitta orsaken.
