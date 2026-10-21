---
name: ui-style
description: 'Fonders designspråk — nordisk blå med sparsam kopparaccent i Material 3 Expressive, Space Grotesk, belopp med tabulära siffror, fast palett, ljust/mörkt/auto. Ladda när du bygger eller ändrar tema, färger, typografi, former, texter, ikoner, tomma tillstånd eller en komponents utseende, och tillsammans med skill android:mockup och android-projekt. Trigger-ord: design, stil, utseende, färg, palett, tema, mörkt läge, ljust läge, blå, koppar, vinst, förlust, typsnitt, Space Grotesk, tabulära siffror, tnum, belopp, hörn, ikon, formulering, tomt tillstånd, kontrast.'
---

# Designspråket · nordisk blå och koppar

**Känsla:** lugn, saklig ekonomi. Blått bär identiteten, koppar används sparsamt som accent,
siffror radar upp sig i kolumner. Krav: UI-1 (identitet, fast palett, ljust + mörkt), UI-2
(Space Grotesk, tabulära siffror), UI-3 (vinst/förlust aldrig enbart färg), UI-4 (Ljust/Mörkt/Auto), UI-12 (M3 Expressive: former, knappgrupper, laddningsindikator).

**Var saker står (enda källor):** temats API kommer från biblioteket android-ui (`se.partee71.ui.theme`:
`AppTheme`, `ExtendedColors`/`Tone`, `AppShapes`, `AppTypography`, `Spacing`); Fonders egna värden står i
`ui/theme/` – `Color.kt` (paletten, färgscheman, `FonderLight/DarkExtendedColors`, tonerna `Tone.Gain`,
`Tone.Copper`, `Tone.Tertiary`, `Tone.Neutral`, `Tone.NeutralPrimary`, `Tone.Filled`, `Tone.Error`, `FonderColors`), `Theme.kt` (`FonderTheme`, kortstilarna `flushCardStyle`/`nestedCardStyle`/`ListFrameCards`, `ReturnColors`, `StatusColors`, `ChartSeriesColors`),
`Type.kt` (`FonderTypography`, `MonoAmountStyle`), `Shape.kt` (`FonderShapes`, `FonderAppShapes`). Vilken
komponent som används för vad → skill `shared-ui-components`. Koden är facit; tabellerna nedan speglar den.

## Tema

- `FonderTheme(darkTheme)` är en tunn omslutning av android-ui:s `AppTheme` (`MaterialExpressiveTheme` +
  `MotionScheme.expressive()`) med `FonderLightColorScheme`/`FonderDarkColorScheme`,
  `FonderLight/DarkExtendedColors`, `FonderAppShapes` och typskalan `FonderTypography`. **Ingen dynamisk färg** – identiteten ska vara konsekvent.
- Bibliotekets tema tar inte Material 3:s `Shapes`, så en inre `MaterialExpressiveTheme(shapes = FonderShapes)`
  lägger Fonders hörn på M3-komponenterna och ärver färger, typografi och rörelse. Ta inte bort den –
  då får knappar, fält och dialoger M3:s standardhörn.
- `FonderTheme` ger `FonderColors` (vinst/förlust) för det läge appen **faktiskt** valt (Inställningar,
  UI-4), inte systemets; `ReturnColors` läser dem därifrån, och övriga semantiska färger kommer ur
  `MaterialTheme.colorScheme` eller `AppTheme.tone(…)`. Aldrig `isSystemInDarkTheme()` direkt i en komponent.
- **Kontrast:** `ThemeContrastTest` prövar varje textbärande par mot 4,5:1 i båda temana (toner, kort,
  knappar, snackbar, vinst/förlust – UI-3). Ny färg eller ton → nytt par där.

### android-ui-mappningen (`Color.kt`, `Shape.kt`)

| Bibliotekets | Fonders värde |
|---|---|
| `ExtendedColors.card` / `toolbar` / `onToolbar` | `surface` / `surface` / `onSurface` |
| `ExtendedColors.rowTint` | `surfaceVariant` |
| `Tone.Primary` | `primaryContainer` + `onPrimaryContainer` |
| `Tone.Copper` (Fonders) | `secondaryContainer` + `onSecondaryContainer` |
| `Tone.Gain` (Fonders) | `GainContainer*` + `OnGainContainer*` (= `ReturnColors.gainContainer`/`onGainContainer`) |
| `Tone.Tertiary` (Fonders) | `tertiaryContainer` + `onTertiaryContainer` (Besluts "Avgift") |
| `Tone.Neutral` (Fonders) | `surfaceVariant` + `onSurfaceVariant` (risknivån, Avstått/Avbrutet/Utgånget) |
| `Tone.NeutralPrimary` (Fonders) | `surfaceVariant` + `primary` (Översiktens kugghjul, `AppIconButton` Tonal) |
| `Tone.Filled` (Fonders) | `surfaceContainerHigh` + `onSurface` (kort inne i ett kort, `AppCard(tone = Tone.Filled)`) |
| `Tone.Error` (Fonders) | `errorContainer` + `onErrorContainer` (farozonen i Inställningar) |
| `LocalAppButtonStyle` / `LocalAppCardStyle` | 40 dp, morf, tonad = `Tone.Primary` / 1 dp `outlineVariant`-kontur, 16 dp luft, 0 dp innehållsmellanrum (`FonderTheme`) |
| `ExtendedColors.accents` | `primary`, `secondary` (diagramseriernas kulörer) |
| `AppShapes` | kort och rad 18 dp, fält och meny 12 dp, `smallTile` 8 dp, `iconTile` 18 dp, sheet 28 dp upptill, pill rund |
| `AppTypography` | `AppTypography.from(FonderTypography)` (bibliotekets standardindelning) |

### Palett (`Color.kt`)

| Token | Värde | Roll |
|---|---|---|
| `Blue900`…`Blue100` | `#001D36` · `#00315A` · `#1F4E79` · `#2B5A87` · `#3F6E9C` · `#5A8BBE` · `#9CC4F0` · `#D2E4FF` | skalan; `Blue700` är kärnan |
| primär | ljust `Blue700` `#1F4E79` · mörkt `Blue300` `#9CC4F0` | knappar, valt, aktiv flik, laddningsindikatorn |
| primärbehållare | ljust `Blue100` `#D2E4FF` · mörkt `Blue600` `#2B5A87` | tonad yta under diagramkurvan, tomt tillstånds ikonruta, dra-för-att-uppdatera |
| tertiär | ljust `Blue500` · mörkt `Blue400` | |
| sekundär (koppar) | ljust `Copper` `#B4552F` · mörkt `CopperBright` `#FFB59A`; behållare `#FFDBCE` / `#713518` | sparsam accent, köpmarkörer, gul status |
| vinst | `GainLight` `#21724B` · `GainDark` `#5FD39B` | via `ReturnColors.gain` |
| förlust | `LossLight` `#A9453C` · `LossDark` `#E58C82` | via `ReturnColors.loss`; mörkt temas `error` = `LossDark` |
| bakgrund / yta | ljust `#F7F9FC` / `#FDFDFF` · mörkt `#101418` / `#151A1F` | ytbehållarna (`surfaceContainer*`) är satta i samma blågrå ton |
| ytvariant | ljust `#DEE3EB` · mörkt `#42474E` | knappgruppens ovalda knappar, märken, påminnelseraden |
| text / dämpad | ljust `#181C20` / `#42474E` · mörkt `#E0E2E8` / `#C2C7CF` | `onSurface` / `onSurfaceVariant` |
| kontur / svag kontur | ljust `#72777F` / `#C2C7CF` · mörkt `#8C9199` / `#42474E` | |
| invers | ljust `#2D3135` på `#EEF0F6` · mörkt `#E0E2E8` på `#2D3135` | snackbar |

> **Paletten "Nordisk blå och koppar" är avgjord** (M3 Expressive-mockupen, palett `bla`, beslut
> 2026-10-11). Vit text på `Copper` `#B4552F` når 4,9:1 och `onSecondary` `#5A1B00` på
> `CopperBright` 7,7:1. Vinst- och förlustfärgerna är oförändrade från den tidigare petrolpaletten.

### Semantiska färger (`Theme.kt`)

| Objekt | Medlemmar | Regel |
|---|---|---|
| `ReturnColors` | `gain`, `loss` (ur `FonderColors`), `gainContainer`/`onGainContainer` (= `Tone.Gain`), `forAmount(amount)` (≥ 0 → vinst) | alltid tillsammans med tecken eller pil (UI-3); belopp med `MoneyFormat.percentSigned` ger äkta minustecken |
| `StatusColors` | `gron`, `gul`, `rod` | säljsignaler (ANA-3); grön/röd återanvänder `ReturnColors`, gul är kopparen (`secondary`) – ingen egen trafikljuspalett |
| `ChartSeriesColors` | `holding` (= `primary`, heldragen), `candidate` (= `secondary`, koppar, **streckad**) | serierna skiljs på kulör **och** form; köpmarkörerna är också koppar men lodräta |

En ny semantisk färg läggs som en ny `Tone` (behållare + innehåll) i `FonderLight/DarkExtendedColors`
när den är en tonad behållare, annars som ett fält i `FonderColors` och ett av objekten ovan – med värden för
båda temana och ett par i `ThemeContrastTest`. Aldrig `Color(…)` i feature-kod.

## Typografi (`Type.kt`)

- **Space Grotesk** i alla `FonderTypography`-stilar (display, headline, title, body, label),
  laddad via **Google Fonts-providern** (`GoogleFont.Provider`, `com.google.android.gms.fonts`) –
  inte bundlad i `res/font/`. Utan Play-tjänster eller före första nedladdningen faller texten
  tillbaka till systemfonten.
- **`MonoAmountStyle`**: Space Grotesk Medium med `fontFeatureSettings = "tnum"` (tabulära
  siffror). Används för belopp, kurser, procent och sifferkolumner så att siffrorna radar upp sig
  (UI-2). Kombinera med en storlek ur `MaterialTheme.typography` (`MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium)`, som i `PeriodRow`), hårdkoda inte.
- Formatering av tal sker bara i `MoneyFormat` / `SwedishNumberFormat` (`domain/usecase`):
  "1 234,50 kr", "+12,3 %", "−5,0 %" (äkta minustecken).

## Former (`Shape.kt`)

`FonderShapes` (Material 3, läses av Fonders komponenter): `extraSmall` 12 dp (textfält, menyer) · `small` 8 dp (chips, märken) · `medium` 18 dp
(kort, påminnelserad, tomt tillstånds ikonruta) · `large` 26 dp · `extraLarge` 28 dp (dialoger). `FonderAppShapes`
(android-ui:s `AppTheme.shapes`) har samma radier, ur samma konstanter i `Shape.kt`. Hämtas via `MaterialTheme.shapes`
eller `AppTheme.shapes`, aldrig `RoundedCornerShape(…)` i feature-kod.

## Layout

- Tryckytor minst 48 dp (skill `android:accessibility-compose` och `android-projekt` → "Tillgänglighet").
- Etiketten kapas, aldrig värdet (UI-6); allt som kan växa skrollar (UI-5).
- Bakgrundsarbete visas med den delade formskiftande laddningsindikatorn i rubrikraden (`CardTitleRow`/`WorkingIndicator`,
  NAV-6), aldrig som ett blockerande överlägg.

## Texter

- Korta, sakliga, svenska. Belopp alltid med enhet ("kr", "%"). Fondens namn skrivs ut, aldrig
  bara ISIN.
- "Vet inte" sägs rakt ut ("Risk okänd", ANA-4) i stället för att ett värde döljs eller visas som 0.
- Återkommande texter finns en gång i `strings.xml`.

## Avstånd (`Spacing`)

Avstånd i skärmkod – `padding(…)`, `PaddingValues(…)`, `Arrangement.spacedBy(…)`, `Spacer`-avstånd – tas ur
android-ui:s `Spacing`: `xs` 4 · `s` 8 · `m` 12 · `l` 16 · `xl` 24 · `xxl` 32 dp. Literala avstånds-dp i
feature-kod i `padding(`, `PaddingValues(` och `spacedBy(` stoppas av `UiConsistencyTest` och hooken
(`ui-forbidden.txt`). Kontrollen går rad för rad och stannar vid första `)`, så `Spacer`-bredder, dp efter en inre
parentes och anrop över flera rader fångas inte – välj ändå en token. Storlekar (ikon, höjd, bredd,
strokebredd, hörn) är inte avstånd. De udda avstånden som fanns före kontrollen (2, 6, 10 och 0 dp) står i
baslinjen i `ui-allowlist.txt` och byts först när skärmen ses över, eftersom bytet syns; ny kod väljer en token.
Delade komponenter i `ui/components/` och `ui/diagram/` får ha sina egna mått.

## Tillgänglighet

Enligt skill `android:accessibility-compose` och `android-projekt` → "Tillgänglighet".
