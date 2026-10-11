---
name: accessibility-compose
description: 'Tillgänglighet (a11y) i Fonders Compose-UI — belopp, procent och vinst/förlust ska gå att läsa med TalkBack, stor text och färgblindhet, och alla klickbara ytor ska vara minst 48 dp. Ladda denna när du bygger eller ändrar UI: ikoner, knappar, klickbara ytor, kort, rader med belopp, diagram, chips, fält. Trigger-ord: tillgänglighet, accessibility, a11y, TalkBack, skärmläsare, contentDescription, semantics, mergeDescendants, stateDescription, touch target, tryckyta, dynamisk text, stor text, kontrast, färgblind, vinst, förlust, belopp, procent, diagram.'
---

# Tillgänglighet i Compose

Fonder visar mest siffror: belopp, kurser, avkastning, risknivåer och kurvor. De ska gå att
förstå med skärmläsare, med förstorad text och utan att se färg. Kraven: UI-3 (vinst/förlust
aldrig färg ensam), UI-6 (värdet kapas aldrig), UI-10 (risknivå i text). Bygg på de delade
komponenterna (skill `shared-ui-components`) så blir semantiken konsekvent – fixa i komponenten,
inte per anropare.

## Regler

### 1. Belopp och procent läses upp begripligt
- Formatera alltid via `MoneyFormat` (`kr`, `percentSigned`, `percent`) – "1 234,50 kr",
  "+12,3 %", "−5,0 %". Äkta minustecken (U+2212) och mellanslag före enheten gör att TalkBack
  läser "minus fem komma noll procent", inte "bindestreck".
- En rad med etikett och värde (`PeriodRow`, `ValueAsOfRow`) är **en** logisk enhet:
  `Modifier.semantics(mergeDescendants = true)` så att "Sedan köp, plus fyra komma två procent"
  läses i ett svep, inte som två lösryckta noder.
- Skriv aldrig ut bara en förkortning eller ett ISIN som enda bärare – fondens namn finns i
  texten eller i `contentDescription`.

### 2. Färg är aldrig ensam bärare av vinst/förlust
`ReturnColors.forAmount(...)` färgar, men **tecknet** (+/−) eller en pil bär informationen (UI-3).
Samma för `StatusColors` (statusbannern har rubrik och triggertexter, ANA-3) och risknivån
(`RiskBadge` skriver "Risk 5/7" och läses "Risknivå 5 av 7", UI-10). Kontrollera i båda temana – vinst/förlust har egna
ljusa och mörka toner (skill `ui-style`).

### 3. Diagram har en textsammanfattning
Vico-canvasen är osynlig för skärmläsare. `FundLineChart` sätter en `contentDescription` med
period, antal punkter, lägsta och högsta värde och – i procentläge – kurvornas namn
(`format_fond_chart_description*` i `strings.xml`). Ett nytt diagram eller ett nytt läge ska ha
motsvarande sammanfattning; serierna namnges i teckenförklaringen, inte bara i linjefärgen.

### 4. contentDescription – meningsbärande vs dekorativt
- Meningsbärande ikon (ikonknapp, statusikon, `WorkerStatusIcon`, väntesnurran) har en svensk
  `contentDescription` ur `strings.xml` som säger *vad* – t.ex. "Uppdaterar Fondavgifter per år"
  när flera kort kan vänta samtidigt (NAV-6).
- Rent dekorativ grafik (legendprick, avdelare) har `contentDescription = null`.
- Upprepa inte synlig text i samma nod (dubbelläsning).

### 5. Tryckytor minst 48 dp
Alla klickbara element ska ha minst **48 × 48 dp**. Använd M3:s `IconButton` eller, inne i
`ui/components`, `Modifier.minimumInteractiveComponentSize()` – aldrig en naken ikon med
`clickable`. Periodknapparna i `FundLineChart` och knappgrupperna (`ChoiceChipRow`) följer M3:s
knappstorlek och läses som radioval (`Role.RadioButton` + vald); ett eget val ska inte vara mindre.

### 6. Dynamisk text och tabulära siffror
- Text i `sp`, aldrig låsta höjder som klipper förstorad text.
- Belopp med `MonoAmountStyle` (tabulära siffror) behåller kolumnerna även vid stor text – men
  raden får inte bli bredare än skärmen: etiketten kapas med `weight` + ellips, värdet aldrig (UI-6).
- Skärmar som kan växa skrollar (UI-5).

### 7. Tillstånd har semantik
Expanderbara sektioner (`ExpandableSection`, `ExpandableInfoRow`) och växlar (`FollowedToggleRow`)
ska kommunicera läget (`stateDescription` eller `Role`) – utöka den delade komponenten om det saknas.

## Tester (regel 2)

Compose-UI-test asserterar semantik utan TalkBack (i dag instrumenttester i `app/src/androidTest`,
t.ex. `RiskBadgeTest`, `CardTitleRowTest`, och med Robolectric i JVM i `app/src/test`, t.ex. `EmptyStateTest`):
```kotlin
composeTestRule.onNodeWithContentDescription("Risknivå 5 av 7").assertExists()
composeTestRule.onNodeWithText("−5,0 %").assertIsDisplayed()
```
Lägg/utöka sådana assertions när du ändrar en interaktiv komponent.

## Checklista

- [ ] Belopp och procent formateras med `MoneyFormat`; etikett + värde är merge:ade.
- [ ] Vinst/förlust, status och risk har tecken/text, inte bara färg – i ljust och mörkt.
- [ ] Diagram har textsammanfattning och namngivna serier.
- [ ] Meningsbärande ikoner har svensk `contentDescription`; dekorativa har `null`.
- [ ] Klickbara element ≥ 48 dp.
- [ ] Text i `sp`; värdet kapas aldrig vid stor text.
