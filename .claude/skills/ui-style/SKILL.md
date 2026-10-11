---
name: ui-style
description: 'Fonders designspråk — grön petrol med sparsam mässingsaccent, rubriker och UI i Space Grotesk, belopp med tabulära siffror, fast palett utan dynamisk färg, ljust/mörkt/auto. Ladda denna när du bygger eller ändrar tema, färger, typografi, former, texter, ikoner, tomma tillstånd eller en komponents utseende, och tillsammans med skill mockup. Trigger-ord: design, stil, utseende, färg, palett, tema, mörkt läge, ljust läge, petrol, mässing, guld, vinst, förlust, grön, röd, typsnitt, font, Space Grotesk, tabulära siffror, tnum, belopp, hörn, rundad, form, kort, ikon, text, formulering, tomt tillstånd, kontrast, snyggt.'
---

# Designspråket · grön petrol

**Känsla:** lugn, saklig ekonomi. Petrol bär identiteten, mässing används sparsamt som accent,
siffror radar upp sig i kolumner. Krav: UI-1 (identitet, fast palett, ljust + mörkt), UI-2
(Space Grotesk, tabulära siffror), UI-3 (vinst/förlust aldrig enbart färg), UI-4 (Ljust/Mörkt/Auto).

**Var saker står (enda källor):** värdena i `ui/theme/` – `Color.kt` (paletten och
färgscheman), `Theme.kt` (`FonderTheme`, `ReturnColors`, `StatusColors`, `ChartSeriesColors`),
`Type.kt` (`FonderTypography`, `MonoAmountStyle`), `Shape.kt` (`FonderShapes`). Vilken komponent som
används för vad → skill `shared-ui-components`. Koden är facit; tabellerna nedan speglar den.

## Tema

- `FonderTheme` → `MaterialTheme` med `FonderLightColorScheme`/`FonderDarkColorScheme`,
  `FonderTypography`, `FonderShapes`. **Ingen dynamisk färg** – identiteten ska vara konsekvent.
- `LocalDarkTheme` bär det läge appen **faktiskt** valt (Inställningar, UI-4), inte systemets.
  Semantiska färger utanför `MaterialTheme.colorScheme` läser alltid den – aldrig
  `isSystemInDarkTheme()` direkt i en komponent.

### Palett (`Color.kt`)

| Token | Värde | Roll |
|---|---|---|
| `Petrol900`…`Petrol300` | `#06302B` · `#0A403A` · `#0E5249` · `#12655A` · `#167C6E` · `#2E9B8A` · `#5FBDAE` | skalan; `Petrol500` är kärnan |
| primär | ljust `Petrol700` `#0E5249` · mörkt `Petrol300` `#5FBDAE` | knappar, valt, aktiv flik |
| tertiär | ljust `Petrol500` · mörkt `Petrol400` | |
| sekundär (mässing) | ljust `Brass` `#B8862F` · mörkt `BrassBright` `#E3C05A` | sparsam accent, köpmarkörer, gul status |
| vinst | `GainLight` `#2FA36B` · `GainDark` `#5FD39B` | via `ReturnColors.gain` |
| förlust | `LossLight` `#C0574E` · `LossDark` `#E58C82` | via `ReturnColors.loss`; mörkt temas `error` = `LossDark` |
| bakgrund / yta | ljust `#F6F9F8` / `#FBFDFC` · mörkt `#0F1513` / `#121A18` | |
| text / dämpad | ljust `#0F1513` / `#3F4B48` · mörkt `#DDE4E1` / `#BFC9C5` | `onSurface` / `onSurfaceVariant` |

> **Mässingens värde är avgjort:** kodens `Brass = #B8862F` (ljust) och `BrassBright = #E3C05A`
> (mörkt) gäller (beslut 2026-10-11, mockup i
> https://claude.ai/artifact/XJoun6DFZqTdKTRF9gmYWB). `#C9A227` valdes bort: vit text på den når
> bara 2,4:1 och markörerna syns sämre mot ljus yta. Vit text på `#B8862F` når 3,2:1 – räcker för
> stor text och ikoner, inte för brödtext; mörkt tema har `onSecondary` `#3A2E00` (7,6:1).
> Petrol `#167C6E` är `Petrol500`; primärfärgen i ljust tema är `Petrol700` `#0E5249`.

### Semantiska färger (`Theme.kt`)

| Objekt | Medlemmar | Regel |
|---|---|---|
| `ReturnColors` | `gain`, `loss`, `forAmount(amount)` (≥ 0 → vinst) | alltid tillsammans med tecken eller pil (UI-3); belopp med `MoneyFormat.percentSigned` ger äkta minustecken |
| `StatusColors` | `gron`, `gul`, `rod` | säljsignaler (ANA-3); grön/röd återanvänder `ReturnColors`, gul är mässingen – ingen egen trafikljuspalett |
| `ChartSeriesColors` | `holding` (= `onSurface`, "bläck"), `candidate` (ljust `Petrol400`, mörkt `Petrol300`) | serierna skiljs på **ljushet**, inte bara kulör; mässing är reserverad för köpmarkörer |

En ny semantisk färg läggs som ett nytt fält i ett av dessa objekt (eller ett nytt objekt i
`Theme.kt`) med värden för båda temana – aldrig `Color(…)` i feature-kod.

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

`FonderShapes`: `extraSmall` 6 dp · `small` 12 dp · `medium` 18 dp · `large` 26 dp ·
`extraLarge` 26 dp. Hämtas via `MaterialTheme.shapes`, aldrig `RoundedCornerShape(…)` i feature-kod.

## Layout

- Tryckytor minst 48 dp (skill `accessibility-compose`).
- Etiketten kapas, aldrig värdet (UI-6); allt som kan växa skrollar (UI-5).
- Bakgrundsarbete visas med den delade väntesnurran i rubrikraden (`CardTitleRow`/`WorkingIndicator`,
  NAV-6), aldrig som ett blockerande överlägg.

## Texter

- Korta, sakliga, svenska. Belopp alltid med enhet ("kr", "%"). Fondens namn skrivs ut, aldrig
  bara ISIN.
- "Vet inte" sägs rakt ut ("Risk okänd", ANA-4) i stället för att ett värde döljs eller visas som 0.
- Återkommande texter finns en gång i `strings.xml`.

## Planerad tokenstruktur *(kommer)*

Som i Dagboken: `AppColors`, `AppTypography`, `AppShapes` och `Spacing` i `ui/theme`, så att
feature-koden aldrig behöver `MaterialTheme.colorScheme`/`.typography` direkt eller literala dp.
I dag används `MaterialTheme.*`, objekten ovan och literala `dp` för avstånd. I `ui/` dominerar `4.dp`, `8.dp` och `16.dp` (därefter `12.dp`,
`24.dp`, enstaka `2/3/6/10/14/18/20.dp`) – `Spacing` ska namnge de vanliga och avveckla de udda.
Ny kod väljer bland 4/8/12/16/24 dp tills `Spacing` finns.

## Tillgänglighet

Enligt skill `accessibility-compose`.
