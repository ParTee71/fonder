---
name: ci-budget
description: 'Fonders CI-regel (regel 5) — en PR ska kosta ca 8 Actions-minuter, plus instrumenttesterna bara när PR:en rör koden de testar; release-bygge (R8) och lint körs veckovis, på begäran och vid release. Enda källan för tabellen över vad som körs när. Ladda denna ALLTID när du rör .github/workflows, Gradle-inställningar, nya beroenden eller plugins, testnivåer eller när CI är långsam. Trigger-ord: CI, GitHub Actions, workflow, android.yml, instrumented.yml, pipeline, byggtid, Actions-minuter, cache, paths-filter, concurrency, emulator, instrumenttester, lint, release-bygge, R8, assembleRelease, nytt beroende, långsam, budget.'
---

# CI-budget

**Regel:** en PR ska kosta ca **8 Actions-minuter** på varm cache, plus instrumenttesterna på
emulator **bara** när PR:en rör koden de testar. Release-bygge (R8) och lint hör inte till
PR-flödet; de körs på begäran, veckovis och vid release. Nya beroenden eller plugins får inte
fördubbla byggtiden – ange uppmätt effekt i PR:en. Att slippa köra ett test är aldrig ett skäl
att inte skriva det (regel 2).

**Läget i dag:** uppmätt **~17–20 Actions-minuter per PR-push** (`build-and-test` + `release-build`
+ emulatorn i `instrumented.yml`, alla debiterade uppåt till hel minut). Målet nås i CI-etappen;
till dess får ingen ändring göra CI dyrare.

## Vad som körs när (enda källan för denna tabell)

Tabellen beskriver workflowfilerna **som de är** och ska kontrolleras mot dem vid varje ändring i
`.github/workflows/` – ändra båda i samma PR. Ett kontrollskript som fäller bygget vid skillnad
*(kommer i CI-etappen)*.

<!-- ci-table:start -->
| Workflow · jobb | Trigger i dag | Steg i dag | Mål efter CI-etappen |
|---|---|---|---|
| `android.yml` · `build-and-test` (timeout 8) | `push` och `pull_request` mot `master`, `workflow_dispatch` | nyckelgrind (`git ls-files '*.jks' '*.keystore' '*.p12' '*.pem'` utom `app/debug.keystore`), `setup-java` 17 med `cache: gradle`, sedan **fyra separata Gradle-anrop**: `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, `:app:compileDebugAndroidTestKotlin`, `:app:testDebugUnitTest`; testrapport som artifact | en Gradle-körning (`--continue`, configuration cache) med `setup-gradle` och build cache; bara de tasks ändrade sökvägar kräver; nyckelgrinden behålls |
| `android.yml` · `release-build` (timeout 12) | samma som ovan (parallellt, inget `needs`) | `:app:assembleRelease` osignerat (R8 + resursskrympning) | **ut ur PR-flödet** – veckovis (när `master` ändrats), på begäran och i `release.yml` |
| `android.yml` · `cancel-instrumented-on-failure` | `needs: [build-and-test, release-build]`, `if: failure() \|\| cancelled()` | `github-script` som letar körningar av "Instrumented Tests" med `head_sha: context.sha` och avbryter dem | ersätts av ett gemensamt flöde där emulatorjobbet har `needs` på bygget, eller av `concurrency` |
| `instrumented.yml` · `instrumented-tests` (timeout 18) | **varje** `pull_request` mot `master`, `workflow_dispatch` | `setup-java` 17, KVM, `reactivecircus/android-emulator-runner@v2` (API 34, `google_apis`, x86_64, `pixel_6`) med `:app:connectedDebugAndroidTest`; fel-stackar och rapport som artifact | bara när sökvägar under `app/src/**`, byggfiler eller workflowen själv ändrats (paths-filter i ett `changes`-jobb, inte `paths-ignore`); `concurrency` med `cancel-in-progress` för PR |
| `release-debug.yml` · `release-debug` | bara `workflow_dispatch` | `:app:assembleDebug` (valfritt `-PversionNameOverride`), ersätter den rullande releasen `debug-snapshot` | oförändrat (kostar bara när den körs för hand) |
| `release.yml` · `release` | tagg `v*` eller `workflow_dispatch` (`publish_release`) | avkodar keystore ur secrets, `:app:assembleRelease` signerat, artifact, GitHub Release, raderar keystore | lägger till `lintRelease`; kör inte om instrumenttester som redan är gröna för samma kod |
| veckoflöde (lint + `assembleRelease`) | finns inte | – | nytt flöde *(kommer i CI-etappen)*: veckovis cron, hoppar över om `master` inte ändrats |
<!-- ci-table:end -->

## Kända problem (åtgärdas i CI-etappen)

1. **Release-bygget (R8) körs i varje PR** (`release-build`, egen runner, timeout 12 min). Skälet står
   i workflowen och i NFR-2 (#108, #111: R8-fel upptäcktes först vid släpp). Målet flyttar det till
   veckoflödet och release; NFR-2 i KRAVLISTA ändras i samma PR (skill `requirements-kravlista`).
2. **`instrumented.yml` saknar paths-filter** – emulatorn (timeout 18 min) startar även för en PR som
   bara ändrar Markdown.
3. **Ingen `concurrency`/`cancel-in-progress`** i något flöde – en ny push till PR:en låter den förra
   körningen gå klart och betala fullt.
4. **`cancel-instrumented-on-failure` fungerar inte i PR:** i `pull_request` är `context.sha`
   merge-commiten (`refs/pull/N/merge`), medan körningens `head_sha` är PR-grenens topp – sökningen
   hittar aldrig emulatorkörningen, så den avbryts inte när bygget fallerar.
5. **Fyra separata Gradle-anrop** i `build-and-test` – varje anrop konfigurerar om projektet.
6. **Ingen `gradle/actions/setup-gradle` och ingen build cache** – bara `setup-java` med
   `cache: gradle` (beroendecache). `gradle.properties` har `org.gradle.parallel=true` men varken
   `org.gradle.caching` eller `org.gradle.configuration-cache`; oförändrade testtasks körs alltid om.
7. Setup-stegen (checkout, JDK, `chmod +x gradlew`) är kopierade i alla fyra workflows – ska bli en
   composite action.

## Tekniker som håller tiden nere (mål)

- `gradle/actions/setup-gradle`: cachen skrivs från `master`, läses i PR.
- `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, en Gradle-körning per jobb.
- `concurrency: { group: ci-${{ github.workflow }}-${{ github.ref }}, cancel-in-progress: <bara för PR> }`
  – `master` och release avbryts aldrig.
- Ett snabbt `changes`-jobb (t.ex. `dorny/paths-filter`) avgör vilka jobb som körs; ett samlande
  `ci-ok`-jobb (`if: always()`) är det enda obligatoriska statuskravet, eftersom hoppade jobb inte
  rapporterar status.
- Varje jobb debiteras uppåt till hel minut – slå ihop korta steg hellre än att lägga till jobb.

## När du lägger till något

- **Nytt beroende eller plugin:** ange uppmätt byggtidseffekt i PR-beskrivningen. Ny R8-känslig
  dependency får keep-regler i `app/proguard-rules.pro` (skill `data-privacy-security`).
- **Ny testnivå eller nytt verktyg** (t.ex. Roborazzi, Konsist, `cpdCheck` – *kommer i
  verkstads-PR:en*): lägg det i JVM-jobbet, aldrig i emulatorjobbet om det inte kräver enhet.
- **Över budget:** eget issue med mätning och förslag – aldrig en tyst höjning av timeouten.
- **Lokalt före push** (skill `testing-strategy`): kör de tasks ändringen berör. Lokala körningar
  kostar inga Actions-minuter; en röd CI-runda gör det.

## Anti-mönster

- `paths-ignore` på workflow-nivå (dokumentkontroller skulle aldrig köras).
- Göra ett villkorat jobb till obligatoriskt statuskrav (ett hoppat jobb blockerar PR:en).
- Starta emulatorn "bara den här gången" i en PR som inte rör instrumenttesternas kod.
- Kopiera setup-steg mellan workflows i stället för en composite action.
- Höja `timeout-minutes` i stället för att hitta orsaken.
