---
name: shared-ui-components
description: 'Fonders återbruksregel (regel 4) — samma typ av element ser likadant ut och beter sig likadant överallt, och varje sak finns på ett ställe. Enda källan för komponent- och byggstenstabellerna, de kända dubbletterna och förbudslistan. Ladda denna ALLTID när du bygger eller ändrar UI, en skärm, en komponent, ett interaktionsmönster, en ViewModel med formulär eller lista, ett repository, en beräkning eller när du ser liknande kod på två ställen. Trigger-ord: composable, komponent, UI, skärm, vy, knapp, kort, card, lista, listrad, rad, kryss, fält, datum, väljare, dropdown, chip, badge, dialog, laddning, tomt tillstånd, empty state, utfällbar, foldout, diagram, graf, chart, dra för att uppdatera, återbruk, dubbelkod, duplicering, kopiera, likadant, enhetligt, konsekvent, mönster, TotalCard, ComparisonChart, cpdCheck, UiConsistencyTest, allowlist.'
---

# Enhetligt och utan dubbelkod

**Invariant:** en elementtyp = en komponent, ett interaktionsmönster = ett ställe, en sorts
logik = ett ställe. Två skärmar som visar eller gör samma sak ska se ut och bete sig
identiskt. Skillnader uttrycks **bara** med parametrar på den delade delen.

Denna skill är **enda källan** för tabellerna nedan. `CLAUDE.md`, andra skills och agenter
länkar hit – de kopierar dem inte. Komponenter som inte är byggda står **bara** under
*Planerade delade delar*.

```
Grep i app/src/main/kotlin/se/partee71/fonder/ui/components/ och ui/diagram/ först
```

Utseendet följer Fonders designspråk – grön petrol, mässingsaccent, Space Grotesk, tabulära
siffror, fast palett (UI-1). Färger, former och typografi kommer från `ui/theme`
(`FonderLightColorScheme`/`FonderDarkColorScheme`, `ReturnColors`, `StatusColors`,
`ChartSeriesColors`, `FonderShapes`, `FonderTypography`, `MonoAmountStyle`).

## Utseende: elementtyp → enda tillåtna komponent (`ui/components/`)

| Elementtyp | Komponent | Används i · det som gör den speciell |
|---|---|---|
| Tomt tillstånd | `EmptyState` | Alla listskärmar, importerna, Fonddetalj, Facit, Bytesfönster. `title` + `body`. Rita aldrig en egen tom-vy. |
| Kortets rubrikrad med väntesnurra | `CardTitleRow` | Varje kort som väntar på data (Hem, Portfölj, Sålda, Inställningar, Facit, Bytesfönster). Snurran i raden, aldrig som hörnöverlägg (NAV-6, UI-6); rubriken kapas, snurran aldrig. |
| Väntesnurra / bakgrundsjobb | `WorkingIndicator`, `WorkerStatusIcon` | `WorkingIndicator` är snurran i kort och diagram; `WorkerStatusIcon` sitter i toppradens `actions` (`AppNavigation`) och renderar ingenting i vila. |
| Dra ned för att uppdatera | `PullToRefreshContainer` | Varje skärm med hämtad data (Hem, Portfölj, Fonddetalj, Fondsök, Facit, Bytesfönster). `refreshing` drivs av samma körstatus som kortens snurror, aldrig av en lokal flagga (UI-11). |
| Ett val bland några (chips) | `ChoiceChipRow` | Tema, kontotyp (Inställningar), riskenkäten. `optionLabel` är `@Composable`; horisontellt skrollbar så inget val klipps (UI-5). |
| Dropdown | `SelectField` | Fondsök, transaktionsformuläret, importerna. `clearOptionLabel` ger ett "alla/inget"-val överst. |
| Datumfält | `DateField` | Transaktionsformuläret, importerna. Plattformens `DatePickerDialog` med livscykel via `DisposableEffect`; läses som **en** knapp med etikett och värde. |
| Värde per period / nyckeltal | `PeriodRow` | Hem, Portfölj, Fonddetalj, Facit, Bytesfönster. kr + %, eller bara % (`amount = null`); `stackValue` för mått som inte ska färgkodas som vinst/förlust; `null` = otillräcklig data, aldrig 0 (ANA-4). |
| "Värde per <datum>" | `ValueAsOfRow` | Hem och Portfölj. Renderar ingenting utan datum (POR-7). |
| Andel per kategori | `ExposureBar` | Portfölj (fondtyp, region, index/aktivt) och Hem. Restposter får `outline`-färg (POR-9). |
| Ihopfällbar sektion | `ExpandableSection` | Fonddetaljs Analys och ordlista. Hela rubrikraden klickbar ≥ 48 dp, `rememberSaveable` (UI-3, UI-9); `onExpand` för innehåll som är dyrt att hämta. |
| Utfällbar rad med förklaring | `ExpandableInfoRow` | Fonddetalj, Facit, Sålda. Skal på `ExpandableSection`; `extraContent` för t.ex. jämförelsediagrammet (ANA-11). |
| "Genomförd"-kvittering | `FollowedToggleRow` | Hem, Fonddetalj, Facit. `toggleable` på hela raden, en nod med rollen kryssruta; skriver mot det inspelade förslaget (SET-5). |
| Risknivå | `RiskBadge` | Fonddetalj, Portfölj, Fondsök, Bytesfönster. "Risk 5/7" i text, `toLevel` ger "Risk 5 → 4" (UI-10). |
| Vinsthemtagningsläge | `ProfitTakeBadge` | Portfölj. Ikon + text, medvetet skild från trafikljuset; aldrig ett köp-/säljråd (ANA-8). |
| Säljsignalens status | `StatusDot`, `AnalysisStatusBanner`, `AnalysisGuidanceCard` (`AnalysisStatus.kt`) | Fonddetalj och Hem (`StatusDot` även i Portfölj). Samma titel och triggertexter för samma status (ANA-3, HEM-4). |
| Import klar | `ImportCompleteDialog` | Excel- och PDF-importen. Stängbar dialog, inte en fullskärmsvy. |

## Diagram (`ui/diagram/`)

| Elementtyp | Komponent | Används i · det som gör den speciell |
|---|---|---|
| Linjediagram | `FundLineChart` | Fonddetalj (kurshistorik, jämförelse), Hem (avkastning), Bytesfönster. Enda stället som rör Vico (TP-12). Periodväljare, köpmarkörer, `comparisonSeries` indexeras till 100 (`ChartSeriesNormalizer`), `valueAxis` för kr eller %. |

Nytt diagrambehov → utöka `FundLineChart` med en parameter (så byggdes jämförelsen, ANA-11).

## Kända dubbletter – slås ihop

Dessa bryter mot regel 4 i dag. Rör du koden: slå ihop **först**, i en egen commit, och stryk
raden här. Bygg aldrig en tredje kopia.

| Dubblett | Var | Mål |
|---|---|---|
| `TotalCard` | `hem/HemScreen.kt` och `portfolj/PortfoljScreen.kt` – identisk kod, olika `UiState` | en delad komponent som tar primitiver (värde, vinst, andel, `working`) |
| `ComparisonChart` | `fond/FondDetaljScreen.kt` och `bytesfonster/SwitchWatchScreen.kt` | ett delat jämförelsekort ovanpå `FundLineChart` (laddar/ej tillgänglig/diagram) |
| `ImportLoadingDialog` / `ImportOrdersLoadingDialog` | `imports/ImportHoldingsScreen.kt`, `imports/ImportOrdersScreen.kt` – samma dialog, olika strängar | en delad laddningsdialog med `title`/`body` |
| Egna listrader | `FundResultRow` (Fondsök), `HoldingRow`, `FirstPurchaseRow` (Portfölj), `TransactionRow` (Transaktioner), `SoldFundRow` (Sålda), `FeeHoldingRow`, `FlaggedFundRow`, `SwitchSuggestionRow` (Hem), `SwitchPlanRow`, `FeeAlternativeRow`, `SignalRow` (Fonddetalj), `SummaryRow` (Facit) | en delad listrad (`ItemRow`, planerad) med parametrar för undertext, badge, belopp och pil |
| Kort | drygt 30 råa `Card(...)` med egen `padding(16.dp)` i feature-koden (flest i Hem och Inställningar) | ett delat kort (`AppCard`, planerat) |

## Planerade delade delar *(kommer i verkstads-PR:en)*

Finns **inte** än – låtsas inte att de gör det. Flyttas upp till tabellerna ovan i PR:en som
bygger dem (med mockup, test och skärmdump ljust + mörkt).

| Del | Vad |
|---|---|
| `AppCard` | enda kortet; ersätter råa `Card` |
| `ItemRow` | enda listraden; ersätter de egna raderna ovan |
| `ConfirmDialog` | enda bekräftelsen vid permanent radering |
| `EntityListScreen` | listskärmens ram: laddning, tomt, fel med "Försök igen", lista, lägg till |
| `EntityEditScreen` + `EditorState` | redigering: Spara aktiv först när giltigt **och** ändrat, "Släng ändringar?" vid bakåt, sparfel som snackbar |
| `ComponentGallery` | debug-skärm med alla komponenter i alla tillstånd |

## Kod: delade byggstenar i andra lager

| Behov | Enda stället |
|---|---|
| Portföljberäkningar, avgifter, risk, avkastningsserier | `PortfolioCalc`, `PortfolioFeeCalc`, `PortfolioRiskCalc`, `PortfolioPerformanceCalc`, `PortfolioReturnSeriesCalc`, `PortfolioExposureCalc` i `domain/usecase/` |
| Analys och säljsignaler | `FundAnalysisCalc`, `AnalysisGuidance` |
| Byten | `SwitchPlanCalc`, `SwitchPlanResolver`, `SwitchOutcomeCalc`, `SwitchWatchCalc` |
| Diagrammets data | `ChartPeriodFilter`, `ChartSeriesNormalizer`, `PurchaseMarkerFilter`, `IndexBenchmarkSelector` |
| Belopp och tal i text | `MoneyFormat`, `SwedishNumberFormat` |
| Fondnamn och matchning | `FundNameKey`, `FundNameMatcher`, `FundCompanyMatcher`, `ImportFundMatcher` |
| Valuta | `CurrencyConverter` |
| Handelsdagar | `NavCalendar` |
| Externa källor | `data/network/` – en klient + en parser per källa (Avanza, Handelsbanken, Riksbanken) |
| Backup-format | `BackupPayload` + `BackupSerializer` (skill `data-safety-backup`) |
| Drive-filnamn och gallring | `DriveBackupFiles` |
| Test-fakes | skill `testing-strategy` |

Beräkningar ligger i `domain/usecase/`, aldrig i en ViewModel eller composable.

## Förbjudet

I dag kontrolleras detta **bara i granskningen** (agenten `granskare`). Automatiska kontroller
*(kommer i verkstads-PR:en)*: `UiConsistencyTest` (Konsist), `cpdCheck` (PMD CPD) och hooken
`regel4-check`, med mönstren i en enda fil `ui-forbidden.txt` och undantag bara i
`ui-allowlist.txt` (`fil:symbol – motivering`, användarens ok i PR:en).

- **Feature-kod** (ui-paket utom `ui/components`, `ui/diagram`, `ui/theme`): nya råa
  Material 3-komponenter som har en delad motsvarighet ovan; hårdkodade `Color(…)`,
  `RoundedCornerShape(…)`, `fontSize`, `TextStyle` eller literala dp för hörn och höjd. Befintliga
  träffar är skuld, inte förebild.
- **All kod utom `ui/diagram/`:** Vico.
- **All kod utom `ui/components/`:** Material3:s `PullToRefreshBox`, `ExposedDropdownMenuBox`.
- **En kontroll utan något att skriva mot** – visa den inte alls (ANA-4-principen).

## Arbetsflöde

1. Slå upp elementtypen eller behovet i tabellerna. Finns det → använd det.
2. Räcker det inte → **utöka** med en parameter vars default bevarar nuvarande utseende och
   beteende (jfr `PeriodRow.stackValue`, `RiskBadge.toLevel`). Befintliga anropare ändras inte.
3. Något nytt behövs → ny delad del i `ui/components/` eller `ui/diagram/`, rad i tabellen,
   instrumenterat Compose-test i `androidTest/…/ui/components/` – och mockup först (skill `mockup`).
4. Delade delar utökas eller slås ihop i en egen commit **före** featuren som behöver dem.
5. Två ställen gör nästan samma sak → bryt ut innan det tredje kommer.
6. Komponenten är **stateless**: värde + callback in, state hoistat till anroparen.

## Tillgänglighet

- Avkastning med semantisk färg **och** tecken/pil – aldrig färg ensam.
- Belopp med tabulära siffror (`MonoAmountStyle`), rubriker i Space Grotesk.
- Interaktiva element har `contentDescription` och minst 48 dp (UI-3). `DateField` och
  `FollowedToggleRow` visar mönstret: en nod med roll, etikett och värde.

## Anti-mönster

- Lokal `Card { }` med egen padding "bara här" – eller en tredje `TotalCard`.
- Egen tom-vy i stället för `EmptyState`; egen `Canvas`-graf vid sidan av `FundLineChart`.
- Kopierad "Genomförd"-kryssruta i stället för `FollowedToggleRow` – en kopia som inte skriver
  mot det inspelade förslaget ser ut att fungera utan att göra något.
- En "nästan likadan" komponent i en feature-mapp som borde bo i `ui/components/`.
- Beräkning i en ViewModel eller composable i stället för i `domain/usecase/`.
- Samma text formulerad på två sätt i `strings.xml`.
- En regeltabell kopierad till en annan fil i stället för en länk hit.
