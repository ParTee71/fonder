# fondmatte – referensberäkningar för fondlogiken

Utvecklingsverktyg till skill [`fondmatematik`](../../.claude/skills/fondmatematik/SKILL.md). Ingenting
här körs i appen eller i CI; det räknar fram förväntade värden och data som appens tester och
issues bygger på.

```bash
uv venv /tmp/fm && . /tmp/fm/bin/activate          # eller python3 -m venv
uv pip install -r tools/fondmatte/requirements.txt  # pyxirr, empyrical-reloaded (≈ 1 min)
python -m unittest discover -s tools/fondmatte      # skriptens egna tester
```

## `fondmatte.py` – golden-värden

```bash
python tools/fondmatte/fondmatte.py golden tools/fondmatte/exempel.json
python tools/fondmatte/fondmatte.py isk 2026 400000 400000 400000 400000 --deposits 0
```

`golden` läser en fixtur och skriver ut giltig JSON (`null` där ett värde saknas eller inte är
ändligt). Fixturen har `end` (värderingsdag), `prices` (`{"fond": [["2025-01-02", 100.0], …]}`) och
`transactions` med appens fält: `date`, `fund`, `kind` (`BUY`/`SELL`), `units`, `price`, `fee` –
beloppet är alltid andelar × kurs, som i appens `Transaction`.

| Del | Innehåll | Oberoende källa |
|---|---|---|
| `portfolio` | marknadsvärde, kedjad TWR (total, årstakt och `twr_annualized_act365`), XIRR (ACT/365F) med och utan avgifter, beteendegapet (`xirr_excluding_fees` − `twr_annualized_act365`), och `*_partial` när ett innehav saknat kurs | pyxirr för XIRR; TWR skriven efter `PortfolioReturnSeriesCalc` |
| `funds` | total avkastning, seriens CAGR, max drawdown, och volatilitet, Sharpe och Sortino från 60 dagsavkastningar (volatilitet 0 ger Sharpe `null`) | empyrical-reloaded |
| `realized_average_cost` | realiserat resultat per sälj med genomsnittsmetoden, köpavgiften i anskaffningsvärdet | Skatteverkets regel |
| `realized_fifo_as_app` | samma med appens nuvarande regel: FIFO, köpavgiften utanför | `RealizedGainCalculator` |

Sålda andelar utan känt köp redovisas i `uncovered_units`, som i appen.

Värdena klistras in i JUnit-testet för motsvarande objekt i `domain/usecase/` med fixturen som
indata (tolerans `1e-9`). `exempel.json` är en påhittad portfölj med två fonder, köp, delsälj och
avgifter – den visar att appens regel och genomsnittsmetoden skiljer 23 kr på första säljet
(köpavgiften) och 438 kr på det andra (FIFO). Nya fixturer har alltid påhittad data, aldrig riktiga innehav.

## `fi_innehav.py` – Finansinspektionens fondinnehav

```bash
python tools/fondmatte/fi_innehav.py hamta 2026Q2 --dir /tmp/fi       # ≈ 7 MB
python tools/fondmatte/fi_innehav.py sok /tmp/fi/Fondinnehav_2026Q2.zip "sverige index"
python tools/fondmatte/fi_innehav.py fond /tmp/fi/Fondinnehav_2026Q2.zip SE0003788587 --top 10
python tools/fondmatte/fi_innehav.py overlapp /tmp/fi/Fondinnehav_2026Q2.zip SE0001466368 SE0003788587
python tools/fondmatte/fi_innehav.py genomlysning /tmp/fi/Fondinnehav_2026Q2.zip SE0001466368=0.5 SE0024245476=0.5
```

Överlapp = Σ min(vikt i A, vikt i B) över värdepapper med samma ISIN och positiv vikt; derivat,
valutaterminer och likvida medel (rader utan ISIN) räknas inte. Genomlysning väger ihop fondernas
innehav per land, bransch och värdepapper med portföljvikterna (skalas till 1), och lägger rader
utan ISIN, det fonden inte redovisat som innehav och (för belånade fonder vars innehav summerar
över 100 %) skulder och belåning på egna rader, så att summan blir 100 %.
Branschen tas ur GICS-koden (`Branschkod_instrument`), eftersom fondbolagen skriver namnen olika.

Kända begränsningar: bara **svenskregistrerade** fonder (inte LU/IE), ett kvartal i taget med
ca 3,5 månaders fördröjning, och fondens ISIN är fondens, inte andelsklassens. I 2026Q2 saknar 38
av 726 fonder ISIN (hitta dem med `sok`, men de går inte att jämföra) och 49 redovisar inga
innehav, t.ex. swapbaserade ETF:er – skriptet varnar för dem. Lägg ZIP-filen utanför repot.
