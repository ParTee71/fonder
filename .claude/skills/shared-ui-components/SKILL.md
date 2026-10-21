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
siffror, fast palett (UI-1). Temat är android-ui:s `AppTheme` via `FonderTheme`; färger, former och
typografi kommer från `ui/theme` (`FonderLightColorScheme`/`FonderDarkColorScheme`, `ReturnColors`,
`StatusColors`, `ChartSeriesColors`, `FonderShapes`, `FonderTypography`, `MonoAmountStyle`) och avstånden
från android-ui:s `Spacing` (`xs` 4 · `s` 8 · `m` 12 · `l` 16 · `xl` 24 · `xxl` 32 dp; skill `ui-style`).

## Utseende: elementtyp → enda tillåtna komponent (`ui/components/`)

| Elementtyp | Komponent | Används i · det som gör den speciell |
|---|---|---|
| Kort | android-ui:s `AppCard` (+ `CardSegment`) | Varje kort på varje skärm (sedan 0.77.0; Fonders egen är borta). `FonderTheme` ger `LocalAppCardStyle` Fonders stil: 1 dp `outlineVariant`-kontur, 16 dp luft (även mellan `CardSegment`-bitar), inget extra mellanrum mellan innehållets delar. `tone = Tone.Filled` (tonad, för rader inne i ett kort – Hems avgifts- och analysrader, med `style = nestedCardStyle`, 12 dp), `Tone.Error` + `contentColor` (farozonen); en ton tar bort konturen. `style = flushCardStyle` (0 dp) för ett kort vars klickytor går ut i kanten (Facit, Besluts bytesrader och Facit-rad). Med `onClick` en klickbar nod (UI-13). Listramens rader (Händelser, Fondsök) ritas utan kontur med 12 dp: lägg ramen i `ListFrameCards { cardStyle -> … }` (`ui/theme/Theme.kt`) och ge ett kort i ramens `header` `style = cardStyle`. |
| Knapp | android-ui:s `AppButton`, `AppIconButton` | Varje knapp (sedan 0.77.0; Fonders egna är borta). `ButtonVariant.Primary`/`Secondary` (tonad primärbehållare)/`Outlined`/`Text`/`Destructive`; stilen (40 dp, M3E-formmorf, `secondaryTone = Tone.Primary`) ges en gång i `FonderTheme` via `LocalAppButtonStyle`. Ikonknappen tar en drawable (`R.drawable.ic_*` eller bibliotekets `UiR.drawable.ic_close`/`ic_arrow_back`), kräver `contentDescription` och är 48 dp (UI-3, UI-13); `IconButtonVariant.Tonal` + `tone = Tone.NeutralPrimary` = rund platta i ytvariant med ikonen i primär, Översiktens Inställningar. |
| Textfält | android-ui:s `AppTextField` | Transaktionsformuläret, importerna, fondsök, ISIN-fältet, riskprofilens andelar (sedan 0.76.0; Fonders egen är borta). Fylld med kortytan, `outlineVariant`-kontur i vila, `shapes.field`; `label` obligatorisk, `error` (felfärg, läses som fel) eller `helper` under fältet, `suffix`, `maxLength` (200 enradigt). Tangentbordet börjar med versal som standard – sätt `keyboardOptions` uttryckligen: tal med `KeyboardType.Decimal`/`Number`, ISIN med versaler utan autokorrigering, fondsök `KeyboardOptions.Default` (UI-13). |
| Dialog | android-ui:s `ConfirmDialog`, Fonders `LoadingDialog` | `ConfirmDialog(title, message, confirmLabel, onConfirm, onDismiss)` före varje åtgärd som raderar eller ersätter data (Transaktioner, Inställningar) – `destructive` ger bekräftelseknappen i felfärgen; `dismissLabel = null` ger en dialog med en knapp (`ImportCompleteDialog`, galleriet). 18 dp hörn (`shapes.card`), kortytan, texten 16 sp dämpad. `LoadingDialog` (`ui/components/LoadingDialog.kt`, biblioteket saknar en) för importernas blockerande väntan (UI-13). |
| Kryssrad | `CheckRow` | Importernas rader, och via `FollowedToggleRow` "Genomförd". `toggleable` på hela raden, en nod med rollen kryssruta, ≥ 48 dp; `supportingText`, `labelStyle` (UI-3, UI-13). |
| Listrad | android-ui:s `ItemRow` (+ `ItemRowText`) | Beslut (profilraden, bytesraderna, de avslutade och Facit-raden), Fondsök och Händelser (sedan 0.77.0; Fonders egen är borta). `title` (16 sp Medium), `subtitle`, `leading` (Händelsers datum, Besluts chip), `trailing`, `below`, `overline` (ovanför rubriken – Besluts chip och ursprung), `navigates` (pil, Facit-raden); ≥ 56 dp, luft 12/8 dp. Med `onClick` en nod; utan slås den inte ihop – lägg `Modifier.semantics(mergeDescendants = true) {}` när raden ska läsas i ett svep. `ItemRowText(title, subtitle)` för ett korts rubrik + förklaring utan rad (Inställningar, Beslut). De övriga egna raderna flyttas hit (se *Kända dubbletter*). |
| Förloppsstapel | `ProgressBar` | Bara i komponentgalleriet sedan 0.75.0 (fondsökens laddning är listramens). M3E:s vågiga stapel, obestämd utan `progress` (UI-13). |
| Tomt tillstånd och läsfel (flikar och detaljskärmar) | android-ui:s `EmptyState` (`actions = listOf(EmptyAction(…))`) och `LoadErrorState` ("Försök igen" när felet går att försöka om), centrerade i `CenteredState` (`ui/components/EmptyState.kt`) | Hem, Portfölj, Beslut, Facit; list- och detaljramens skärmar får dem av ramen (`EntityListScreen(empty = EmptyContent(…, actions), onRetry, errorTitle)`). Händelsers och Innehavs knappar i det tomma läget kommer ur `firstTransactionActions` (TRX-7, POR-10). Retry i ViewModel: `RetrySignal` + `restartOn` efter felfångsten (`ui/common/Retry.kt`). Ikonerna är vektorerna `ic_*` i `res/drawable`. |
| Tomt tillstånd och läsfel (övriga) | `EmptyState` | Importerna, transaktionsformuläret, Bytesfönster (ingen retry). `title` + `body`, valfri dekorativ `icon` i en rundad primärbehållare, valfri `action` (en `AppButton` under texten – transaktionsformulärets "Bevaka en fond", TRX-6); `EmptyState(error: UiError, title = …)` visar ett läsfel (`ListUiState.Error`, `DetailUiState.Error`, `EditorUiState.loadError`, importernas fel) med felets text under rubriken (NFR-6). Rita aldrig en egen tom- eller felvy. |
| Kortets rubrikrad med väntesnurra | `CardTitleRow` | Varje kort som väntar på data (Hem, Portfölj, Inställningar, Facit, Bytesfönster). Snurran i raden, aldrig som hörnöverlägg (NAV-6, UI-6); rubriken kapas, snurran aldrig. Bara **inne i** ett kort – en rubrik över en grupp kort är `SectionHeader`. |
| Avsnittsrubrik | android-ui:s `SectionHeader` | Besluts avsnitt (Byten, Avslutade, Hur gick det?) och Inställningars Tema (sedan 0.77.0). 20 sp, `count` i en pill ("2 väntar på dig"), snurran via `working` + `workingDescription` (NAV-6); läses som rubrik. Besluts flikrubrik (24 sp) är `AppTabHeading` (NAV-7). |
| Avdelare | android-ui:s `AppDivider` | Mellan delar av ett kort (Inställningar, Besluts avslutade) och mellan bytesavsnittet och diagrammet i Fonddetalj. 12 dp indrag; `Modifier.padding` för luft. |
| Väntesnurra / bakgrundsjobb | `WorkingIndicator`, `WorkerStatusIcon` | `WorkingIndicator` är M3 Expressives formskiftande `LoadingIndicator` i kort och diagram; `WorkerStatusIcon` sitter i flikrubrikens och topprads åtgärder (`AppNavigation`) och renderar ingenting i vila. |
| Dra ned för att uppdatera | `PullToRefreshContainer` | Varje skärm med hämtad data (Hem, Portfölj, Facit, Bytesfönster); ramskärmarna (Fondsök, Fonddetalj) får dragningen av ramen (`refreshing`/`onRefresh`). `refreshing` drivs av samma körstatus som kortens snurror, aldrig av en lokal flagga (UI-11). Indikatorn är `PullToRefreshDefaults.LoadingIndicator`. |
| Ett val bland några (knappgrupp) | `ChoiceChipRow`, `ConnectedChoiceButton` | Tema, kontotyp (Inställningar), riskenkäten, Händelsers filter Alla/Köp/Sälj; `ConnectedChoiceButton` även i `FundLineChart`s periodrad. Sammanhängande M3E-knappgrupp (`ToggleButton` med `ButtonGroupDefaults`-former), läses som radioval (UI-12); `enabled = false` låser valet (förifyllt formulär). `optionLabel` är `@Composable`; horisontellt skrollbar så inget val klipps (UI-5). |
| Risknivåväljare | `RiskLevelPicker` | Pågående byte (ANA-13, köpnivån). Sju lika breda knappar 1–7 utan skroll, samma färger som `ChoiceChipRow` (`choiceButtonColors`); vald = fylld och helt rund, `marked` = ram (säljfondens nivå) med `markedDescription` för skärmläsaren; läses "Risknivå 4 av 7" som radioval, 48 dp tryckyta; `enabled = false` låser. |
| Dropdown | `SelectField` | Fondsök, transaktionsformuläret, importerna och bytesväljaren (`SwitchDecisionField` i `ui/transaktioner`, BYT-2). `clearOptionLabel` ger ett "alla/inget"-val överst; `supportingText` en förklaring under fältet; `enabled = false` låser fältet med valet synligt. |
| Datumfält | android-ui:s `DateField` | Transaktionsformuläret, importerna. Visar "tis 20 okt 2026" och öppnar Material 3-datumväljaren på kortytan (OK/Avbryt), öppet läge i `rememberSaveable` (UI-9); läses som **en** knapp med etikett och värde. Datumet är `kotlinx.datetime.LocalDate` – ViewModels har `java.time`, så anroparen konverterar med `toKotlinLocalDate()`/`toJavaLocalDate()`. |
| Värde per period / nyckeltal | `PeriodRow` | Hem, Portfölj, Fonddetalj, Facit, Bytesfönster, Händelser (realiserat resultat). kr + %, eller bara % (`amount = null`); `stackValue` för mått som inte ska färgkodas som vinst/förlust; `null` = otillräcklig data, aldrig 0 (ANA-4); `insufficientText` när orsaken är känd ("Visas efter ett år", "Kurs saknas ännu"); `muted` för en underordnad rad utan vinst/förlust-färg (totalkortets "Varav orealiserat"); `supportingLabel`/`supportingValue` för en dämpad rad under etiketten respektive värdet och `valueTone` för ett färdigformaterat värde som ändå färgas som vinst/förlust (Facit: "3 av 5 bättre", "mätt till …"); `valueMuted` dämpar bara värdet, etiketten står kvar (ungefärliga skatter, se `ApproxTaxRow`). |
| Ungefärlig skatt | `ApproxTaxRow` | Hem (schablonskatt i ISK/KF, HEM-16), Händelsers årskort (depå/AF, TRX-8) och Fonddetaljs bytesavsnitt (depå/AF, ANA-15). En `PeriodRow` med "ca −3 000 kr" i hela kronor, `valueMuted` och vad skatten bygger på som `supportingLabel` (issue #199). Förklaringen under raderna är `ItemRowText(title = null, …)`. |
| Portföljens totalkort | `TotalCard` | Hem och Portfölj (HEM-1/POR-3). `AppCard` med `CardTitleRow`, huvudtal, `PeriodRow`-rader (totalresultat, årsavkastning, varav orealiserat), `ValueAsOfRow` och `ExpandableInfoRow`; tar primitiver och `PortfolioTotalCalc.Result`, aldrig en skärms `UiState`. Platserna `afterTotalResult` (direkt under totalresultatet) och `footer` (sist) tar Hems schablonskatt och dess förklaring (HEM-16); null = kortet som förut. |
| "Värde per <datum>" | `ValueAsOfRow` | Hem och Portfölj (i `TotalCard`). Renderar ingenting utan datum (POR-7). |
| Andel per kategori | `ExposureBar` | Portfölj (fondtyp, region, index/aktivt) och Hem. Restposter får `outline`-färg (POR-9). 10 dp stapel med runda ändar. |
| Ihopfällbar sektion | `ExpandableSection` | Fonddetaljs Analys och ordlista. Hela rubrikraden klickbar ≥ 48 dp, `rememberSaveable` (UI-3, UI-9); `onExpand` för innehåll som är dyrt att hämta. |
| Utfällbar rad med förklaring | `ExpandableInfoRow` | Fonddetalj, Facit, totalkortet ("Vad räknas in?"). Skal på `ExpandableSection`; `extraContent` för t.ex. jämförelsediagrammet (ANA-11). |
| Strömbrytarrad | android-ui:s `SwitchRow` | Pågående byte (*Bara indexfonder*, ANA-13). `SwitchRow(title, checked, onCheckedChange)`; `toggleable` på hela raden, en nod med rollen strömbrytare, ≥ 56 dp; `subtitle`, `enabled` (låst reglage med läget synligt). `CheckRow` för en markering i en lista, `SwitchRow` för ett läge som gäller direkt. |
| "Genomförd"-kvittering | `FollowedToggleRow` | Bara Facit (SET-5) sedan 0.68.0 — Följ på Beslut och Fonddetalj öppnar säljformuläret (BYT-2). En `CheckRow` med kvitteringens etikett; skriver mot det inspelade förslaget (SET-5). |
| Tillståndsmärke | android-ui:s `InfoPill(shape = AppTheme.shapes.smallTile)` | Beslut (BYT-4), `RiskBadge`, `ProfitTakeBadge` (sedan 0.77.0; `StateChip` är borta). Text i `labelMedium` på tonad behållare: `Tone.Primary` (Föreslaget), `Tone.Copper` (Sålt), `Tone.Gain` (Köpt), `Tone.Tertiary` (Avgift), `Tone.Neutral` (ytvariant – Avstått/Avbrutet/Utgånget, risknivån); tonerna i `ui/theme/Color.kt`. Tillståndet alltid i text (UI-3); `contentDescription` för förkortad text. Inte klickbart. |
| Risknivå | `RiskBadge` | Fonddetalj, Portfölj, Fondsök, Bytesfönster. "Risk 5/7" i text, `toLevel` ger "Risk 5 → 4" (UI-10). En `InfoPill` i `Tone.Neutral` med utskriven beskrivning ("Risknivå 5 av 7") som `contentDescription`. |
| Vinsthemtagningsläge | `ProfitTakeBadge` | Portfölj. En `InfoPill` med ikon (`ic_trending_up`) + text i vinstens kulör, medvetet skild från trafikljuset; aldrig ett köp-/säljråd (ANA-8). |
| Säljsignalens status | `StatusDot`, `AnalysisStatusBanner`, `AnalysisGuidanceCard` (`AnalysisStatus.kt`) | Fonddetalj och Hem (`StatusDot` även i Portfölj). Samma titel och triggertexter för samma status (ANA-3, HEM-4). |
| Påminnelserad med åtgärd | `ReminderRow` | Hem (HEM-12, säkerhetskopia; HEM-13, "Att besluta"). Tonad i ytvariant (`surfaceVariant`, kortens rundning), ikon + rubrik + undertext + åtgärdsetikett; `tone = Accent` (sekundärbehållaren, koppar), `count` (stort `CountBadge` i stället för ikonen) och `chevron` (pil i stället för etiketten, som blir klickåtgärdens namn); **en** klickbar nod ≥ 48 dp som skärmläsaren läser i ett svep (UI-3). Ingen stängknapp – raden försvinner med orsaken. |
| Import klar | `ImportCompleteDialog` | Excel- och PDF-importen. android-ui:s `ConfirmDialog` med bara Stäng (`dismissLabel = null`), inte en fullskärmsvy. |
| Kopparmärke med antal | `CountBadge` | Flikraden (Beslut, NAV-9) och `ReminderRow` med `count` (HEM-13). Rund i `secondary`, `CountBadgeSize.Small` 18 dp / `Large` 28 dp; dekorativt för skärmläsaren – antalet står alltid i omgivande text eller beskrivning. |
| Listskärm | android-ui:s `EntityListScreen` | Händelser (`add = null`, filtret och resultatkortet som `filter`/`header`, stor rubrik som fälls ihop; nyckeln både `TabKey` och `FrameKey`, så flikraden och plusmenyn står kvar) och Fondsök (liten topprad med bakåt, fälten som `filter`, egen `errorTitle`, dra ned; nyckeln `FrameKey`, även som jämförelsefondens och Pågående bytes väljare). Kontraktstest med `runListScreenContract` (android-ui:s `ui-testing`). |
| Detaljskärm | android-ui:s `EntityDetailScreen` | Fonddetalj: `header = null` och fondens namn som `title` i den lilla toppraden ("Fond" tills namnet lästs), märkningarna vänsterställda överst i innehållet, sektionerna `sectionGap = Spacing.m` isär, dra ned via `refreshing`/`onRefresh` (UI-11); laddning (`AppLoading`), läsfel med "Försök igen" och "finns inte längre" utan knapp är ramens (NFR-6). Ramen har ingen egen `imePadding` – skärmen lägger den på ramens `modifier` (ISIN-fältet). Nyckeln är en `FrameKey`. Kontraktstest med `runDetailScreenContract`. Översikt och Pågående byte har kvar sina egna lägen. |
| Redigeringsskärm | android-ui:s `EntityEditScreen` + `EditorState`/`EditorLoader` | Riskprofilen och transaktionsformuläret (UI-15; formuläret med `saveUnchanged` förifyllt, `formError` för kopplingen och fältfel via `errorFor`). Liten topprad med rubrik och kompakt Spara (aktiv när giltigt **och** ändrat), "Släng ändringar?" vid bakåt, sparfel som snackbar, läsfel med "Försök igen" (`loader.retry()`); `horizontalMargin = Spacing.l`. Nyckeln är en `FrameKey`. Knappstilen (40 dp, morf, tonad = primärbehållaren) ges en gång i `FonderTheme` via `LocalAppButtonStyle`. Inställningar har kvar sitt eget tillstånd tills vidare. |
| Flikrad | `AppFloatingToolbar` (+ `AppToolbarTab`) | `AppNavigation`. M3E `HorizontalFloatingToolbar`, yta med svag kontur och full rundning; vald flik = primärbehållare med ikon + namn, ovalda bara ikon; valfritt kopparmärke (`badgeCount`, ritat med `CountBadge`). `Role.Tab` + valt-läge, ≥ 48 dp (NAV-7, UI-14). |
| Plusknapp med meny | `AppFabMenu` (+ `AppFabMenuItem`, `AppFabMenuDefaults`) | `AppNavigation`. M3E `FloatingActionButtonMenu` + `ToggleFloatingActionButton`, primär, "+" fyrkantigt rundad → "×" rund; val som pills i primärbehållaren; bakåt stänger (NAV-8, UI-14). |
| Komponentgalleri (debug) | `ComponentGallery` | Inställningar → Komponenter, bara i debug-bygget (`BuildConfig.DEBUG` i `AppNavigation`). Varje komponent i `ui/components` och `ui/diagram` i sina lägen med påhittad data, plus android-ui-delarna appen använder (ramarnas `AppTopBar` med `LocalTopBarStatus`); diagrammet i en hopfälld sektion. En ramskärm (`FrameKey`). Ny delad komponent → lägg den här också. |
| Topprad / flikrubrik | `AppTopBar`, `AppTabHeading` | `AppNavigation`. `AppTopBar` = bakåtpil + rubrik på skärmar djupare i stacken (UI-8); `AppTabHeading` = flikens stora rubrik med åtgärder, läses som rubrik (NAV-7). En ramskärm (nyckel som är `FrameKey`, t.ex. android-ui:s `EntityListScreen`/`EntityEditScreen`/`EntityDetailScreen`) får ingen av dem: skalet ritar ingen rubrik och ingen `imePadding` för den, och chromets `WorkerStatusIcon` når ramens topprad via `LocalTopBarStatus`. Ramarnas bakåtpil följer `LocalTopBarBackStyle` (Fonder: `IconButtonVariant.Plain`, genomskinlig) i `FonderTheme`. |

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
| `ComparisonChart` | `fond/FondDetaljScreen.kt` och `bytesfonster/SwitchWatchScreen.kt` | ett delat jämförelsekort ovanpå `FundLineChart` (laddar/ej tillgänglig/diagram) |
| Egna listrader | `FundResultRow` (Fondsök), `HoldingRow`, `FirstPurchaseRow` (Portfölj), `FeeHoldingRow`, `FlaggedFundRow` (Hem), `SwitchPlanRow`, `FeeAlternativeRow`, `SignalRow` (Fonddetalj), `SummaryRow` (Facit) | android-ui:s `ItemRow` (används i Beslut, Fondsök och Händelser; `trailing` för belopp, `navigates` för pil) |
| Utfällbart rad-kort | `FacitRadCard` (`facit/FacitScreen.kt`) – Sålda fonders kopia försvann med vyn i 0.62.0 | `ExpandableInfoRow` i ett `AppCard` |
`cpdCheck` har ingen baslinje: kopiorna som fanns när kontrollen infördes (66 filuppsättningar)
slogs ihop i 0.78.3 (issue #175) – analysslingan till `FundAnalysisCalc.analyzeAll`, importradernas
huvud till `ImportRowHeader`, testuppställningen till de delade fakes och fixturerna (skill
`testing-strategy` → DRY i tester). Raderna ovan är dubbletter i beteende som CPD inte ser.

## Planerade delade delar

Finns **inte** än – låtsas inte att de gör det. Flyttas upp till tabellerna ovan i PR:en som
bygger dem (med mockup, test och skärmdump ljust + mörkt).

| Del | Vad |
|---|---|
| *(inga just nu – `EntityListScreen` flyttades upp i 0.75.0)* | |

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
| Listans tillstånd (laddning, tomt, fel, innehåll) | android-ui:s `ListUiState` (`se.partee71.ui:ui-common`, transitivt via `ui-components`) som fält i skärmens tillstånd – Händelser, Beslut, Facit, Fondsök, Portfölj (NFR-6). Raderna med `toListUiState()`, `itemsOrEmpty()`/`errorOrNull()` och ett fel i källan med `catchAsListError` (`ui/common/ListStates.kt`). Detaljskärmarna (Hem, Fonddetalj, Pågående byte) har det lästa som `DetailUiState`-fält på samma sätt, med `valueOrNull()`/`errorOrNull()` och `catchAsDetailError` (`ui/common/DetailStates.kt`); Riskprofilen `EditorState`/`EditorLoader`. `shownAfter` finns i samma modul men används inte än (den skulle fördröja dagens indikatorer) – bygg ingen egen |
| Fel från datalagret | `DataError` + `DataError.of` (Room/SQLite → `Database`, I/O → `Network`, importernas fel) och `AppErrors : ErrorMapper` i `data/common/`; texten `DataError.toMessage()` i `ui/common/DataErrorMessage.kt`. Inloggningens, backupens och Drive-svarens fel (`SignInException.Reason`, `BackupFormatException.Reason`, `DriveResult`) går också via `DataError.of`. `suspendRunCatching` gör ett `Result` av en suspend-åtgärd utan att svälja avbrott. Ett `UiError` visas med `EmptyState(error)` eller dess `message` |
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
  `TopAppBar`, avdelare – `HorizontalDivider`/`VerticalDivider`/`Divider` → `AppDivider`). Kvar: `Scaffold` i `AppNavigation`;
  hårdkodade `Color(…)`, `RoundedCornerShape(…)`, `fontSize`/`lineHeight`/`letterSpacing`,
  `TextStyle(…)`, `.shadow(…)`, literal dp för höjd och literala avstånds-dp i `padding(…)`,
  `PaddingValues(…)` och `spacedBy(…)` (använd `Spacing`). Befintliga träffar är skuld, inte förebild –
  avstånden utan token (2, 6, 10, 0 dp) står i baslinjen tills deras skärm ses över.
- **All kod utom `ui/diagram/`:** Vico.
- **All kod utom `ui/components/`:** Material3:s `PullToRefreshBox`, `ExposedDropdownMenuBox`.
- **All kod utom `di/`:** `Dispatchers.IO`/`Default`/`Main`/`Unconfined`. Klassen tar en
  `CoroutineDispatcher` med `@IoDispatcher`, `@DefaultDispatcher` eller `@MainDispatcher` från
  `di/DispatchersModule` (skill `android:kotlin-coroutines`).
- **All kod i `ui/`** – även `ui/components/`, `ui/diagram/` och `ui/theme/`: `Channel(` och
  `.receiveAsFlow(` för engångshändelser. Händelsen blir ett fält i tillståndet som skärmen agerar
  på i en `LaunchedEffect` nyckelad på värdet och kvitterar till ViewModel:n (`saved` +
  `onSavedHandled()`, `startedSwitchWatchId` + `XxxEvent.SwitchWatchOpened`; skill `android-projekt`, Flöden).
- **UiState-typer** (icke-privata data classes som heter `*State` och sealed interfaces `*UiState` i `ui/`)
  utan `@Immutable` eller `@Stable`. Listor, kartor och mängder i dem är `ImmutableList`/`ImmutableMap`/`ImmutableSet`;
  domänmodellerna, de beräkningsresultat som ligger i tillstånden och datum-/tidstyperna räknas som stabila via `app/compose-stability.conf` (skill `android:compose-expert`).
- **En kontroll utan något att skriva mot** – visa den inte alls (ANA-4-principen).

## Kontroller

| Kontroll | När | Vad |
|---|---|---|
| Hooken `regel4-check` (`.claude/hooks/regel4-check.mjs`) | direkt efter varje `Edit`/`Write` av en `.kt`-fil i `app/src/main` | samma mönster och undantag som testet; exit 2 med rad, träff och ersättning. Kontrollerar bara Fonder (ett grannrepo i samma session har sin egen hook) |
| `UiConsistencyTest` (Konsist) | `testDebugUnitTest` | hela `app/src/main` mot `ui-forbidden.txt` minus `ui-allowlist.txt`; exemplen i `ui-forbidden-examples.txt` körs både här och i hooktestet, så att hook och bygge tolkar lika; dessutom (bara här) att UiState-typerna är `@Immutable`/`@Stable` |
| `./gradlew cpdCheck` (PMD CPD, 80 tokens) | `build-and-test` | kopierad Kotlin i `core/src` och `app/src/{main,test,androidTest}`; varje kopia fäller bygget (ingen baslinje) |

**Baslinjen** (skuld från före kontrollerna, får bara krympa):

- `ui-allowlist.txt`: rader med motiveringen "baslinje 2026-10 – rättas i skärm-PR:erna" (19 när kontrollen infördes, 0 sedan M3 Expressive steg 2b)
  genereras av `node tools/regel4-baseline.mjs` – kör det när en skärm rättats. Ett undantag gäller
  fil + symbol, så en baslinjerad för `Card(` i `HemScreen.kt` släpper igenom *alla* `Card(` i den
  filen tills den rättats. Hooktestet fäller en inaktuell baslinje. Handskrivna undantag står kvar.

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
- Interaktiva element har `contentDescription` och minst 48 dp (UI-3). android-ui:s `DateField` och
  `FollowedToggleRow` visar mönstret: en nod med roll, etikett och värde.

## Anti-mönster

- Lokal `Card { }` med egen padding "bara här" – eller en egen kopia av `TotalCard`.
- Egen tom-vy i stället för `EmptyState`; egen `Canvas`-graf vid sidan av `FundLineChart`.
- Kopierad "Genomförd"-kryssruta i stället för `FollowedToggleRow` – en kopia som inte skriver
  mot det inspelade förslaget ser ut att fungera utan att göra något.
- En "nästan likadan" komponent i en feature-mapp som borde bo i `ui/components/`.
- Beräkning i en ViewModel eller composable i stället för i `domain/usecase/`.
- Samma text formulerad på två sätt i `strings.xml`.
- En regeltabell kopierad till en annan fil i stället för en länk hit.
