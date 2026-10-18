---
name: shared-ui-components
description: 'Regel 4 i Fonder — samma elementtyp ser ut och beter sig likadant överallt och varje sak finns på ett ställe. Enda källan för komponent- och byggstenstabellerna, de kända dubbletterna och förbudslistan. Ladda alltid vid UI, skärmar, komponenter, interaktionsmönster, formulär, listor, beräkningar eller liknande kod på två ställen. Trigger-ord: komponent, skärm, knapp, kort, lista, listrad, fält, dialog, laddning, tomt tillstånd, diagram, dra för att uppdatera, dubbelkod, återbruk, TotalCard, ComparisonChart, UiConsistencyTest, cpdCheck, allowlist.'
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

Utseendet följer Fonders designspråk – nordisk blå, kopparaccent, M3 Expressive, Space Grotesk, tabulära
siffror, fast palett (UI-1). Färger, former och typografi kommer från `ui/theme`
(`FonderLightColorScheme`/`FonderDarkColorScheme`, `ReturnColors`, `StatusColors`,
`ChartSeriesColors`, `FonderShapes`, `FonderTypography`, `MonoAmountStyle`).

## Utseende: elementtyp → enda tillåtna komponent (`ui/components/`)

| Elementtyp | Komponent | Används i · det som gör den speciell |
|---|---|---|
| Kort | `AppCard` | Varje kort på varje skärm. `AppCardVariant.Outlined` (yta + svag kontur, standard), `Filled` (tonad, för rader inne i ett kort – Hems avgifts- och analysrader), `Error` (farozonen). 16 dp inre marginal (`contentPadding`; 0 för ett kort vars klickytor går ut i kanten, Facit och Händelsers lista). Med `onClick` en klickbar nod (UI-13). `groupPosition` (`AppCardGroupPosition.of(index, count)`) låter rader som är egna `LazyColumn`-items se ut som ett kort med delad kontur (Händelser). |
| Knapp | `AppButton`, `AppIconButton` | Varje knapp. `AppButtonVariant.Primary`/`Tonal`/`Outlined`/`Text`/`Danger`; M3E-formmorf (`ButtonDefaults.shapes()`). Ikonknappen kräver `contentDescription` (UI-3, UI-13); `AppIconButtonVariant.Tonal` = rund platta i ytvariant, Översiktens Inställningar. |
| Textfält | `AppTextField` | Transaktionsformuläret, importerna, fondsök, ISIN-fältet, riskprofilens andelar. Konturerat, `shapes.extraSmall`; `label`, `keyboardOptions`, `suffix`, `supportingText`, `isError` (UI-13). |
| Dialog | `AppDialog`, `ConfirmDialog`, `LoadingDialog` | `ConfirmDialog` före varje åtgärd som raderar eller ersätter data (Transaktioner, Inställningar) – `destructive` ger bekräftelseknappen i felfärgen; `AppDialog` för en enda åtgärd (`ImportCompleteDialog`); `LoadingDialog` för importernas blockerande väntan. 28 dp (UI-13). |
| Kryssrad | `CheckRow` | Importernas rader, och via `FollowedToggleRow` "Genomförd". `toggleable` på hela raden, en nod med rollen kryssruta, ≥ 48 dp; `supportingText`, `labelStyle` (UI-3, UI-13). |
| Listrad | `ItemRow` | Fondsök, Händelser, Beslut (bytesraderna och de avslutade). Rubrik, `supporting`, `trailing`, `leading` (Händelsers datum, Besluts chip), `overline` (ovanför rubriken – Besluts chip och ursprung); ≥ 56 dp, rubrik och undertext i en nod. De övriga egna raderna flyttas hit (se *Kända dubbletter*). |
| Förloppsstapel | `ProgressBar` | Fondsökens katalogladdning. M3E:s vågiga stapel, obestämd utan `progress` (UI-13). |
| Tomt tillstånd | `EmptyState` | Alla listskärmar, importerna, Fonddetalj, Facit, Bytesfönster, Beslut. `title` + `body`, valfri dekorativ `icon` i en rundad primärbehållare. Rita aldrig en egen tom-vy. |
| Kortets rubrikrad med väntesnurra | `CardTitleRow` | Varje kort som väntar på data (Hem, Portfölj, Inställningar, Facit, Bytesfönster). Snurran i raden, aldrig som hörnöverlägg (NAV-6, UI-6); rubriken kapas, snurran aldrig. |
| Väntesnurra / bakgrundsjobb | `WorkingIndicator`, `WorkerStatusIcon` | `WorkingIndicator` är M3 Expressives formskiftande `LoadingIndicator` i kort och diagram; `WorkerStatusIcon` sitter i flikrubrikens och topprads åtgärder (`AppNavigation`) och renderar ingenting i vila. |
| Dra ned för att uppdatera | `PullToRefreshContainer` | Varje skärm med hämtad data (Hem, Portfölj, Fonddetalj, Fondsök, Facit, Bytesfönster). `refreshing` drivs av samma körstatus som kortens snurror, aldrig av en lokal flagga (UI-11). Indikatorn är `PullToRefreshDefaults.LoadingIndicator`. |
| Ett val bland några (knappgrupp) | `ChoiceChipRow`, `ConnectedChoiceButton` | Tema, kontotyp (Inställningar), riskenkäten, Händelsers filter Alla/Köp/Sälj; `ConnectedChoiceButton` även i `FundLineChart`s periodrad. Sammanhängande M3E-knappgrupp (`ToggleButton` med `ButtonGroupDefaults`-former), läses som radioval (UI-12); `enabled = false` låser valet (förifyllt formulär). `optionLabel` är `@Composable`; horisontellt skrollbar så inget val klipps (UI-5). |
| Risknivåväljare | `RiskLevelPicker` | Pågående byte (ANA-13, köpnivån). Sju lika breda knappar 1–7 utan skroll, samma färger som `ChoiceChipRow` (`choiceButtonColors`); vald = fylld och helt rund, `marked` = ram (säljfondens nivå) med `markedDescription` för skärmläsaren; läses "Risknivå 4 av 7" som radioval, 48 dp tryckyta; `enabled = false` låser. |
| Dropdown | `SelectField` | Fondsök, transaktionsformuläret, importerna och bytesväljaren (`SwitchDecisionField` i `ui/transaktioner`, BYT-2). `clearOptionLabel` ger ett "alla/inget"-val överst; `supportingText` en förklaring under fältet; `enabled = false` låser fältet med valet synligt. |
| Datumfält | `DateField` | Transaktionsformuläret, importerna. Plattformens `DatePickerDialog` med livscykel via `DisposableEffect`; läses som **en** knapp med etikett och värde. |
| Värde per period / nyckeltal | `PeriodRow` | Hem, Portfölj, Fonddetalj, Facit, Bytesfönster, Händelser (realiserat resultat). kr + %, eller bara % (`amount = null`); `stackValue` för mått som inte ska färgkodas som vinst/förlust; `null` = otillräcklig data, aldrig 0 (ANA-4). |
| "Värde per <datum>" | `ValueAsOfRow` | Hem och Portfölj. Renderar ingenting utan datum (POR-7). |
| Andel per kategori | `ExposureBar` | Portfölj (fondtyp, region, index/aktivt) och Hem. Restposter får `outline`-färg (POR-9). 10 dp stapel med runda ändar. |
| Ihopfällbar sektion | `ExpandableSection` | Fonddetaljs Analys och ordlista. Hela rubrikraden klickbar ≥ 48 dp, `rememberSaveable` (UI-3, UI-9); `onExpand` för innehåll som är dyrt att hämta. |
| Utfällbar rad med förklaring | `ExpandableInfoRow` | Fonddetalj, Facit. Skal på `ExpandableSection`; `extraContent` för t.ex. jämförelsediagrammet (ANA-11). |
| Strömbrytarrad | `SwitchRow` | Pågående byte (*Bara indexfonder*, ANA-13). Etikett + M3 `Switch`, `toggleable` på hela raden, en nod med rollen strömbrytare, ≥ 48 dp; `supportingText`, `enabled`. `CheckRow` för en markering i en lista, `SwitchRow` för ett läge som gäller direkt. |
| "Genomförd"-kvittering | `FollowedToggleRow` | Bara Facit (SET-5) sedan 0.68.0 — Följ på Beslut och Fonddetalj öppnar säljformuläret (BYT-2). En `CheckRow` med kvitteringens etikett; skriver mot det inspelade förslaget (SET-5). |
| Tillståndsmärke | `StateChip` (+ `StateChipTone`) | Beslut (BYT-4) och `RiskBadge`. Text i `labelMedium` på tonad behållare i `shapes.small`: `Primary` (Föreslaget), `Accent` (koppar, Sålt), `Gain` (`ReturnColors.gainContainer`, Köpt), `Tertiary` (Avgift), `Neutral` (ytvariant – Avstått/Avbrutet/Utgånget, risknivån). Tillståndet alltid i text (UI-3); `contentDescription` för förkortad text. Inte klickbart. |
| Risknivå | `RiskBadge` | Fonddetalj, Portfölj, Fondsök, Bytesfönster. "Risk 5/7" i text, `toLevel` ger "Risk 5 → 4" (UI-10). En `StateChip` i `Neutral` med utskriven beskrivning ("Risknivå 5 av 7"). |
| Vinsthemtagningsläge | `ProfitTakeBadge` | Portfölj. Ikon + text, medvetet skild från trafikljuset; aldrig ett köp-/säljråd (ANA-8). |
| Säljsignalens status | `StatusDot`, `AnalysisStatusBanner`, `AnalysisGuidanceCard` (`AnalysisStatus.kt`) | Fonddetalj och Hem (`StatusDot` även i Portfölj). Samma titel och triggertexter för samma status (ANA-3, HEM-4). |
| Påminnelserad med åtgärd | `ReminderRow` | Hem (HEM-12, säkerhetskopia; HEM-13, "Att besluta"). Tonad i ytvariant (`surfaceVariant`, kortens rundning), ikon + rubrik + undertext + åtgärdsetikett; `tone = Accent` (sekundärbehållaren, koppar), `count` (stort `CountBadge` i stället för ikonen) och `chevron` (pil i stället för etiketten, som blir klickåtgärdens namn); **en** klickbar nod ≥ 48 dp som skärmläsaren läser i ett svep (UI-3). Ingen stängknapp – raden försvinner med orsaken. |
| Import klar | `ImportCompleteDialog` | Excel- och PDF-importen. Stängbar `AppDialog`, inte en fullskärmsvy. |
| Kopparmärke med antal | `CountBadge` | Flikraden (Beslut, NAV-9) och `ReminderRow` med `count` (HEM-13). Rund i `secondary`, `CountBadgeSize.Small` 18 dp / `Large` 28 dp; dekorativt för skärmläsaren – antalet står alltid i omgivande text eller beskrivning. |
| Flikrad | `AppFloatingToolbar` (+ `AppToolbarTab`) | `AppNavigation`. M3E `HorizontalFloatingToolbar`, yta med svag kontur och full rundning; vald flik = primärbehållare med ikon + namn, ovalda bara ikon; valfritt kopparmärke (`badgeCount`, ritat med `CountBadge`). `Role.Tab` + valt-läge, ≥ 48 dp (NAV-7, UI-14). |
| Plusknapp med meny | `AppFabMenu` (+ `AppFabMenuItem`, `AppFabMenuDefaults`) | `AppNavigation`. M3E `FloatingActionButtonMenu` + `ToggleFloatingActionButton`, primär, "+" fyrkantigt rundad → "×" rund; val som pills i primärbehållaren; bakåt stänger (NAV-8, UI-14). |
| Topprad / flikrubrik | `AppTopBar`, `AppTabHeading` | `AppNavigation`. `AppTopBar` = bakåtpil + rubrik på skärmar djupare i stacken (UI-8); `AppTabHeading` = flikens stora rubrik med åtgärder, läses som rubrik (NAV-7). |

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
| Egna listrader | `FundResultRow` (Fondsök), `HoldingRow`, `FirstPurchaseRow` (Portfölj), `FeeHoldingRow`, `FlaggedFundRow` (Hem), `SwitchPlanRow`, `FeeAlternativeRow`, `SignalRow` (Fonddetalj), `SummaryRow` (Facit) | den delade `ItemRow` (finns, används i Fondsök) – utöka med parametrar för belopp och pil |
| Utfällbart rad-kort | `FacitRadCard` (`facit/FacitScreen.kt`) – Sålda fonders kopia försvann med vyn i 0.62.0 | `ExpandableInfoRow` i ett `AppCard` |
| Analysera innehaven | `FundAnalysisCalc.analyze`-slingan i `hem/HemViewModel.kt` och `portfolj/PortfoljViewModel.kt` | en funktion i `domain/usecase/` som tar innehav + historik |
| Testuppställning | ~40 testfiler med kopierade fakes och fixturer – flest `HemViewModelTest`/`PortfoljViewModelTest`, sedan `SettingsViewModelTest`, `FacitViewModelTest`, migreringstesterna | delade fakes och fixturer (skill `testing-strategy` → DRY i tester) |

Alla kopior som fanns när `cpdCheck` infördes står i `config/cpd-baseline.txt` (en rad per
uppsättning filer som delar en kopia; 66 rader, varav 5 i `app/src/main`, när kontrollen infördes – 54 respektive 3 sedan 0.62.0). Raderna ovan är
produktionskoden bland dem. Baslinjen ska bara krympa – se *Kontroller*.

## Planerade delade delar

Finns **inte** än – låtsas inte att de gör det. Flyttas upp till tabellerna ovan i PR:en som
bygger dem (med mockup, test och skärmdump ljust + mörkt).

| Del | Vad |
|---|---|
| `EntityListScreen` | listskärmens ram: laddning, tomt, fel med "Försök igen", lista, lägg till |
| `EntityEditScreen` + `EditorState` | redigering: Spara aktiv först när giltigt **och** ändrat, "Släng ändringar?" vid bakåt, sparfel som snackbar |
| `ComponentGallery` | debug-skärm med alla komponenter i alla tillstånd |

## Kod: delade byggstenar i andra lager

| Behov | Enda stället |
|---|---|
| Portföljberäkningar, avgifter, risk, avkastningsserier | `PortfolioCalc`, `PortfolioFeeCalc`, `PortfolioRiskCalc`, `PortfolioPerformanceCalc`, `PortfolioReturnSeriesCalc`, `PortfolioExposureCalc` i `domain/usecase/` |
| Analys och säljsignaler | `FundAnalysisCalc`, `AnalysisGuidance` |
| Byten | `SwitchPlanCalc`, `SwitchPlanResolver`, `SwitchOutcomeCalc`, `SwitchWatchCalc`; facit ur cachen: `FacitSummary` → `FacitSnapshot` (Facit och Beslut-flikens "Hur gick det?", SET-5/NAV-9); `SwitchWatchRepository.startFromSuggestion` (bevakning ur ett kvitterat förslag – Beslut och Fonddetalj) |
| Det som väntar på beslut och dess antal | `PendingDecisionsSource` → `PendingDecisions` → `SwitchRows.build` (listan Byten, BYT-4: en rad per byte med tillstånd, avslutade i 14 dagar; `proposedCount`/`soldCount`/`feeCount`/`waitingCount`) – Beslut, Översiktens "Att besluta" och flikmärket läser samma källa; de inspelade avgiftsbytena som förslag: `CheaperAlternatives` (ANA-9, även Fonddetaljs kvittering); vilka förslag ett avstående döljer och hur länge: `SuggestionDismissals` (HEM-14, även Fonddetalj) |
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

Mönstren står i **en** fil, `app/src/test/resources/ui-forbidden.txt` (regex + vad som ska
användas i stället, per scope `feature`, `component-only`, `vico`, `dispatchers` och `events`); den här listan
sammanfattar den. Undantag bara i `app/src/test/resources/ui-allowlist.txt`
(`fil:symbol – motivering`, användarens ok i PR:en).

- **Feature-kod** (ui-paket utom `ui/components`, `ui/diagram`, `ui/theme`): nya råa
  Material 3-komponenter som har en delad motsvarighet ovan (`Card`/`Surface`, `ListItem`,
  `AlertDialog`/`Dialog`, knappar och ikonknappar, textfält, datumväljare, `DropdownMenu`, chips,
  progressindikatorer, `Badge`, `Checkbox`, `Switch`, `NavigationBar`, flytande verktygsrad, `FloatingActionButton`,
  `TopAppBar`). Kvar: `Scaffold` i `AppNavigation` och `HorizontalDivider`;
  hårdkodade `Color(…)`, `RoundedCornerShape(…)`, `fontSize`/`lineHeight`/`letterSpacing`,
  `TextStyle(…)`, `.shadow(…)` eller literal dp för höjd. Befintliga träffar är skuld, inte förebild.
- **All kod utom `ui/diagram/`:** Vico.
- **All kod utom `ui/components/`:** Material3:s `PullToRefreshBox`, `ExposedDropdownMenuBox`.
- **All kod utom `di/`:** `Dispatchers.IO`/`Default`/`Main`/`Unconfined`. Klassen tar en
  `CoroutineDispatcher` med `@IoDispatcher`, `@DefaultDispatcher` eller `@MainDispatcher` från
  `di/DispatchersModule` (skill `android:kotlin-coroutines`).
- **All kod i `ui/`** – även `ui/components/`, `ui/diagram/` och `ui/theme/`: `Channel(` och
  `.receiveAsFlow(` för engångshändelser. Händelsen blir ett fält i tillståndet som skärmen agerar
  på i en `LaunchedEffect` nyckelad på värdet och kvitterar till ViewModel:n (`saved` +
  `onSavedHandled()`, `startedSwitchWatchId` + `onSwitchWatchOpened()`; skill `android-projekt`, Flöden).
- **UiState-typer** (icke-privata data classes som heter `*State` och sealed interfaces `*UiState` i `ui/`)
  utan `@Immutable` eller `@Stable`. Listor, kartor och mängder i dem är `ImmutableList`/`ImmutableMap`/`ImmutableSet`;
  domänmodellerna, de beräkningsresultat som ligger i tillstånden och datum-/tidstyperna räknas som stabila via `app/compose-stability.conf` (skill `android:compose-expert`).
- **En kontroll utan något att skriva mot** – visa den inte alls (ANA-4-principen).

## Kontroller

| Kontroll | När | Vad |
|---|---|---|
| Hooken `regel4-check` (`.claude/hooks/regel4-check.mjs`) | direkt efter varje `Edit`/`Write` av en `.kt`-fil i `app/src/main` | samma mönster och undantag som testet; exit 2 med rad, träff och ersättning. Kontrollerar bara Fonder (ett grannrepo i samma session har sin egen hook) |
| `UiConsistencyTest` (Konsist) | `testDebugUnitTest` | hela `app/src/main` mot `ui-forbidden.txt` minus `ui-allowlist.txt`; exemplen i `ui-forbidden-examples.txt` körs både här och i hooktestet, så att hook och bygge tolkar lika; dessutom (bara här) att UiState-typerna är `@Immutable`/`@Stable` |
| `./gradlew cpdCheck` (PMD CPD, 80 tokens) | `build-and-test` | kopierad Kotlin i `app/src/{main,test,androidTest}`; fäller en kopia vars filuppsättning inte står i `config/cpd-baseline.txt` |

**Baslinjerna** (skuld från före kontrollerna, får bara krympa):

- `ui-allowlist.txt`: rader med motiveringen "baslinje 2026-10 – rättas i skärm-PR:erna" (19 när kontrollen infördes, 0 sedan M3 Expressive steg 2b)
  genereras av `node tools/regel4-baseline.mjs` – kör det när en skärm rättats. Ett undantag gäller
  fil + symbol, så en baslinjerad för `Card(` i `HemScreen.kt` släpper igenom *alla* `Card(` i den
  filen tills den rättats. Hooktestet fäller en inaktuell baslinje. Handskrivna undantag står kvar.
- `config/cpd-baseline.txt`: skriv om med `./gradlew cpdCheck -PcpdWriteBaseline` efter en
  sammanslagning; `cpdCheck` varnar för rader som inte längre gäller. Aldrig för att få in en ny kopia.

## Arbetsflöde

1. Slå upp elementtypen eller behovet i tabellerna. Finns det → använd det.
2. Räcker det inte → **utöka** med en parameter vars default bevarar nuvarande utseende och
   beteende (jfr `PeriodRow.stackValue`, `RiskBadge.toLevel`). Befintliga anropare ändras inte.
3. Något nytt behövs → ny delad del i `ui/components/` eller `ui/diagram/`, rad i tabellen,
   instrumenterat Compose-test i `androidTest/…/ui/components/` – och mockup först (skill `android:mockup` och `android-projekt` → "Mockup").
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
