---
name: shared-ui-components
description: Fonders återbruksregel (regel 4) — använd de delade komponenterna i ui/components/ och ui/diagram/ i stället för att bygga nya varianter av samma sak. Ladda denna ALLTID när du bygger eller ändrar UI: ett kort, ett diagram, en tom-tillståndsvy, en datumrad, en utfällbar sektion, en väljare, en badge eller en dialog. Trigger-ord: composable, UI, komponent, kort, card, diagram, graf, chart, tomt tillstånd, empty state, datum, picker, foldout, utfällbar, badge, chip, dialog, ny skärm, ny rad.
---

# Återbruk av delade UI-komponenter

**Regel:** Innan du bygger en UI-byggsten — sök i `ui/components/` och `ui/diagram/`. Finns
något som löser samma sak: **använd det, eller utöka det.** Bygg inte en ny, nästan likadan
variant. Konsistens i utseende och beteende är ett krav, inte en bonus.

```
Grep i app/src/main/kotlin/se/partee71/fonder/ui/components/ och ui/diagram/
```

## Katalog (`ui/components/`)

| Komponent | Använd för |
|---|---|
| `EmptyState` | Tomt tillstånd på en skärm utan innehåll. Rita aldrig en egen variant. |
| `ChoiceChipRow` | Rad med valchips över en `enum` e.dyl. `optionLabel` är `@Composable` så anropsstället kan slå upp strängen med `stringResource`. Används för tema, kontotyp, perioder. |
| `SelectField` | Dropdown-väljare. `null`-alternativ när "alla/inget valt" ska gå att välja. |
| `DateField` | Datumväljare. Presenteras som **en** knapp i semantikträdet, med etikett och värde. |
| `PeriodRow` | Rad med värde per period (dag/vecka/månad). `stackValue` för mått som inte ska tecken-/färgkodas som vinst/förlust (volatilitet, Sharpe). `null` markerar otillräcklig data. |
| `ValueAsOfRow` | "Värde per <datum>". Renderar ingenting om datumet är okänt. Delad mellan Portfölj och Hem. |
| `ExpandableSection` | Ihopfällbar sektion. `onExpand` körs vid utfällning — för innehåll som är dyrt att hämta och inte ska laddas i förväg. |
| `ExpandableInfoRow` | En utfällbar **rad** (namn + nyckeltal hopfälld, motivering + diagram utfälld). Bytesförslagen i Fonddetalj. |
| `FollowedToggleRow` | "Genomförd"-kvittering av ett inspelat förslag. Samma komponent på Hem, Fonddetalj och Facit. |
| `RiskBadge` | Risknivå. `toLevel` ger "Risk 5 → 4" så ett bytesförslag går att bedöma på raden. |
| `ProfitTakeBadge` | Neutral markering för vinsthemtagningsläge. Aldrig ett köp-/säljråd. |
| `AnalysisStatus` | Säljsignal-status med förklarande text. |
| `ExposureBar` | Andelsfördelning per kategori. Restposter får `outline`-färg så de syns som skilda från riktiga kategorier. |
| `WorkerStatusIcon` | Bakgrundsjobb pågår. Renderar ingenting i vila, så den aldrig stjäl utrymme. |
| `ImportCompleteDialog` | Resultatdialog efter import. Delad mellan Excel- och PDF-flödena. |

## Diagram (`ui/diagram/`)

| Komponent | Använd för |
|---|---|
| `FundLineChart` | **All** linjediagramsritning — kurshistorik, jämförelsediagram, flera serier. Rita inte en egen `Canvas`. |

Nytt diagrambehov → utöka `FundLineChart`. Det är precis så jämförelsediagrammet (ANA-11)
byggdes: komponenten fick stöd för flera serier i stället för att en ny diagramvariant
skapades.

## När en delad komponent inte räcker

1. **Utöka den först.** Lägg en parameter med ett default som bevarar nuvarande beteende
   (jfr `PeriodRow.stackValue`, `RiskBadge.toLevel`). Befintliga anropare ska inte behöva
   ändras — och ska renderas exakt som förut.
2. **Bara om det är en genuint annan byggsten** skapar du en ny komponent — och då i
   `ui/components/` (eller `ui/diagram/`), så att även den blir delad i stället för gömd i
   en feature-mapp.
3. Håll komponenten **stateless**: ta emot värde + callback, hoista state till anroparen.

## Tillgänglighet och tema

- Avkastning visas med semantisk färg **och** tecken/pil — aldrig färg ensam.
- Belopp med tabulära siffror; rubriker i Space Grotesk. Fast palett, ingen dynamisk färg.
- Interaktiva element behöver `contentDescription` och tillräcklig träffyta. `DateField`
  visar mönstret: de inre delarna tas ur semantikträdet så hela komponenten läses som en
  knapp med etikett och värde.

## Anti-mönster

- Egen `Card` med egen padding i stället för samma kortform som resten av skärmen använder.
- Egen tom-vy i stället för `EmptyState`.
- Egen `Canvas`-graf vid sidan av `FundLineChart`.
- Kopierad "Genomförd"-kryssruta i stället för `FollowedToggleRow` — den skriver mot den
  inspelade raden, och en kopia som inte gör det ser ut att fungera utan att göra något.
- En "nästan likadan" komponent i en feature-mapp som borde bo i `ui/components/`.
- En kryssruta eller knapp som visas trots att den inte har något att skriva mot. Visa den
  inte alls (ANA-4-principen) — det är bättre än en kontroll som tyst inte gör något.
