---
name: diagram
description: 'Fonders diagram — FundLineChart i ui/diagram (Vico 3.3.1) och diagrammatematiken i domain/usecase. Ladda när du ritar, ändrar eller använder ett diagram, en kurva, en period, en jämförelse, köpmarkörer eller avkastning över tid, och tillsammans med shared-ui-components. Trigger-ord: diagram, graf, kurva, kurshistorik, period, jämförelse, index, referensfond, köpmarkör, axel, zoom, Vico, glidande medel, MovingAverageCalc, ComparisonChart, FundLineChart.'
---

# Diagram

**Invariant:** ett diagram ritas bara med komponenterna i `ui/diagram/` – ett diagram per
diagramtyp – och allt som räknas (periodfönster, indexering, nollställning, avkastningsserie,
markörer) räknas i `domain/usecase/` som rena objekt med JVM-tester. Vico anropas bara i
`ui/diagram/`. Kraven står i KRAVLISTA (TP-12, HEM-9, HEM-10, ANA-11, ANA-14, UI-3, NAV-6); vid skillnad
gäller kravlistan. Vilken komponent som är vilken elementtyp står i skill `shared-ui-components`.

## Komponenten

`FundLineChart` (`ui/diagram/FundLineChart.kt`) är appens enda diagram:

| Parameter | Betydelse |
|---|---|
| `points` | `(epochDay, värde)` stigande; tom lista ritar inget – anroparen visar `EmptyState` |
| `purchaseEpochDays` | köpdagar; markeras bara inom vald period, bara på `points` (inte på jämförelseserier) |
| `primaryLabel`, `comparisonSeries: List<ChartSeries>` | fler serier → teckenförklaring (`ChartLegend`) och, i kronläge, indexering till 100 |
| `valueAxis: ChartValueAxis` | `KRONOR` (NAV) eller `PROCENT` (avkastning som andel, indexeras aldrig – nollställs i stället) |
| `movingAverage` | 200-dagarssnittet (ANA-14) ur `MovingAverageCalc.series` över **hela** historiken; beskärs till periodens fönster, ritas prickat utan yta med förklaringsraderna Kurs/200-dagarssnitt och senaste värden, och nämns i beskrivningen. Bara ensam kurva i kronor – ignoreras med `comparisonSeries` eller i `PROCENT`. `null`/tom i perioden = ingen linje, ingen förklaring |
| `working` | väntesnurran för diagram som inte ligger i ett kort med rubrik (NAV-6) |

Teckenförklaringen `ChartLegend` är en: färgprick + namn per serie i en jämförelse, eller linjesymbol
(`LegendSymbol.LINE`/`DOTTED_LINE`) + namn + högerställt värde (`LegendEntry.value`, tabulära siffror)
för kurs och snitt.

Inbyggt: periodväljaren (`ChartPeriodFilter.Period`, skrollbar `LazyRow`,
`CHART_PERIOD_ROW_TEST_TAG`, vald period i `rememberSaveable`), `Zoom.Content` + scroll till slutet,
y-axel padd runt datans min/max (`PriceRangeProvider`, aldrig tvingad nolla), köpmarkörer i koppar, tonad yta under en ensam kurva, periodväljaren som sammanhängande knappgrupp (`ConnectedChoiceButton`),
seriefärger ur `ChartSeriesColors`, och en talbar `contentDescription` (skill `android:accessibility-compose` och `android-projekt` → "Tillgänglighet").

Används i dag i `HemScreen` (avkastningskurva mot referens, `PROCENT`), `FondDetaljScreen`
(kurshistorik och jämförelse) och `SwitchWatchScreen` (säljfond mot bevakade alternativ).

## Matematiken (`domain/usecase/`)

| Objekt | Gör | Test (`app/src/test/.../domain/usecase/`) |
|---|---|---|
| `ChartPeriodFilter` | `apply(points, period)` – fönster bakåt från **senaste punkten**, inte dagens datum; kortare historik ger hela | `ChartPeriodFilterTest` |
| `ChartSeriesNormalizer` | `index(...)` – serier till 100 vid första gemensamma dag (`partial` om någon beskurits); `rebaseReturns(...)` – avkastningsserier nollställda som **kvot** mot periodens första gemensamma dag | `ChartSeriesNormalizerTest` |
| `PortfolioReturnSeriesCalc` | `compute(...)` – tidsviktad, kedjad portföljavkastning (HEM-9); `benchmark(...)` – skuggportfölj i referensfonden (HEM-10), `null` om historiken inte räcker | `PortfolioReturnSeriesCalcTest` |
| `MovingAverageCalc` | `series(points)` – 200-dagars glidande medel per kurspunkt (kurser i `[d − 200, d]`, kräver en kurs på eller före `d − 200`, annars ingen punkt); `averageAt(points, day)` – samma regel för en dag, används av S2 i `FundAnalysisCalc` (en implementering) | `MovingAverageCalcTest` |
| `PurchaseMarkerFilter` | `apply(points, purchaseEpochDays)` – köpdagar inom perioden, snappade till närmaste kurspunkt (Vicos markörer kräver exakt x) | `PurchaseMarkerFilterTest` |
| `MoneyFormat` | etiketter och sammanfattning ("+12,3 %", "1 234,50 kr") | `domain/MoneyFormatTest` |

Ny beräkning som ett diagram behöver (trend, drawdown, glidande medel …) läggs som ett nytt objekt
här med test – aldrig i composablen, och aldrig i en ViewModel om två skärmar kan behöva den.

## Regler

- **Ett diagram per typ.** Behövs en variant → ny parameter på `FundLineChart` med default som
  bevarar dagens beteende (så kom `comparisonSeries`, `valueAxis` och `working` till). En ny
  diagramtyp (staplar, sparkline) blir en ny komponent i `ui/diagram/` med test.
- **Vico bara i `ui/diagram/`.** Ingen `com.patrykandpatrick.vico`-import utanför. Kontrolleras
  automatiskt: scope `vico` i `ui-forbidden.txt` (hooken `regel4-check` och `UiConsistencyTest`).
- **Vico är 3.3.1** (`gradle/libs.versions.toml`, artefakten `compose-m3`, API:t under
  `vico.compose.*`). Höjningen från 2.5.2 ändrade y-axelns steg och etiketter (t.ex. +0,8 % →
  +0,9 %) – godkänt, och skärmdumparna spelades in på nytt. Läs Vico 3-API:t i den befintliga
  filen.
- **Modellen byggs synkront**: `FundLineChart` bygger en `CartesianChartModel` direkt i
  kompositionen (`remember` på serierna) och skickar den till `CartesianChartHost` – inte via
  `CartesianChartModelProducer` i en `LaunchedEffect`. Producenten bygger modellen asynkront på en
  annan tråd än compose-klockan, så en skärmdump kunde tas före första ritningen; den synkrona
  modellen gör skärmdumparna (`FundLineChartScreenshotTest`, `HemScreen_oversikt`) deterministiska.
  Byt inte tillbaka till `modelProducer`.
- **Färger bara ur temat:** `ChartSeriesColors.holding` (blå, heldragen) och `candidate` (koppar,
  streckad – jämförelser och referens), `movingAverage` (`onSurfaceVariant`, prickad med runda 2 dp-prickar,
  ingen yta – snittlinjen, ANA-14; stilen ligger i `rememberSeriesLineProvider`, inte i anroparen), koppar (`secondary`) även för de lodräta köpmarkörerna. Paletten har bara två färger och cyklar (`colors[index % colors.size]`, Vicos
  `LineProvider.series`): i `SwitchWatchScreen` med två eller fler alternativ får den tredje kurvan
  samma färg som säljfonden. En utökad seriepalett hör hemma i `ChartSeriesColors` (skill `ui-style`).
- **Serier namnges** i teckenförklaringen och i beskrivningen – aldrig bara färg (UI-3).
- **Saknad data ritas aldrig som noll**; en jämförelse som inte går att göra visas inte alls,
  med en förklarande text (HEM-10, ANA-4-principen).

## Kända avvikelser (att städa)

- **Dubblett `ComparisonChart`:** privata composables med samma namn och samma mönster ("ingen
  data → förklarande text, annars `FundLineChart` med en jämförelseserie") finns i både
  `ui/fond/FondDetaljScreen.kt` och `ui/bytesfonster/SwitchWatchScreen.kt`. Ska brytas ut till
  en delad komponent i `ui/diagram/` (regel 4).
- **Axelmatematik i UI-lagret:** `PriceRangeProvider` och värdeformatterarna ligger `internal` i
  `FundLineChart.kt` och testas där (`app/src/test/.../ui/diagram/FundLineChartTest.kt`), inte i
  `domain/usecase/`. Paddningsberäkningen bör flyttas till ett rent objekt i `domain/usecase/`.
- `FundLineChart` anropar M3 (`Text`) och literala `dp`/`sp` direkt.

## Tester

- JVM: tabellen ovan + `FundLineChartTest` (y-intervallet) + `FundLineChartMovingAverageTest` (Robolectric:
  snittets förklaringsrader, beskrivningen, inget snitt utan data eller i en jämförelse).
- Instrument: `HemScreenTest` och `SwitchWatchScreenTest` (periodraden via
  `CHART_PERIOD_ROW_TEST_TAG` och `performScrollToIndex`, UI-5).
- Roborazzi ljust + mörkt per diagramläge med `captureLightAndDark` (`FundLineChartScreenshotTest`:
  ensam kurva med yta och köpmarkör, jämförelse, kurva med 200-dagarssnitt `FundLineChart_movingAverage`); verifieras ännu inte i CI (skill `ci-budget`).
