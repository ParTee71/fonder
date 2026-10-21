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
| `portfolio` | marknadsvärde, kedjad TWR (total, årstakt och `twr_annualized_act365`), XIRR (ACT/365F) med och utan avgifter, beteendegapet (`xirr_excluding_fees` − `twr_annualized_act365`), totalresultatet (`net_deposits`, `total_gain`) appens "Varav orealiserat" (`remaining_cost_average`, `unrealized_average_cost_as_app`) och FIFO som referens (`unrealized_fifo`), och `*_partial` när ett innehav saknat kurs | pyxirr för XIRR; TWR skriven efter `PortfolioReturnSeriesCalc` |
| `funds` | total avkastning, seriens CAGR och max drawdown över hela serien; volatilitet, Sharpe och Sortino som `FundAnalysisCalc` i fönstret `risk_window_years` (3 år bakåt från senaste kurs, annars 1, annars `null`; ett fönster väljs bara om ankaret ligger högst 5 dagar före start, inget glapp överstiger 31 dagar och minst 60 % av 252 × år finns som giltiga avkastningar) på kurspar högst 5 dagar isär (`samples`; volatilitet 0 ger Sharpe `null`). Sharpe = `window_annualized_return` (fönstrets totala utveckling i årstakt) / volatilitet. Punkterna sorteras på datum först. Fixtur `riskfonster.json` (issue #195): knappt två års vardagar, påsken 2025 (5 dagar, räknas) och en lucka på 17 dagar i juli 2025 (hoppas över) | empyrical-reloaded |
| `funds.<fond>.periods` | periodraderna som appen visar dem: `day`/`week`/`month` (POR-5, ankrade i senaste NAV-dag, startkurs högst 5 dagar före måldagen), `ytd`/`3m`/`1y`/`3y`/`since_purchase` (ANA-1, från `end`). `fraction` = fondens kursutveckling, `amount` = värdeförändringen på de andelar som faktiskt ägts, dag för dag (`value_change`, issue #193); för `since_purchase` positionens vinst mot anskaffningsvärdet med genomsnittsmetoden. `null` = otillräcklig historik | skriven efter `PositionValueChangeCalc`; oberoende kontroll i testet: från tomt innehav = slutvärde − nettoköp |
| `funds.<fond>.sma_200` | 200-dagarssnittet som kalenderfönster på 290 dagar (≈ 200 handelsdagar), som `MovingAverageCalc`: medel av kurserna i `[d − 290, d]`, bara om serien når `d − 290`; `count`, `first_date`/`first`, `latest` och hela `series`; `latest` `null` när historiken inte räcker. Fixtur `sma.json` (400 vardagar med helgluckor) | enkelt medelvärde; `MovingAverageCalcTest` |
| `facit` | bara för en fixtur med `suggestions` (och `prices`, utan `transactions`): utfallet per förslag (`rows`: mätdag = senaste dag båda fonderna har en kurs, tidigast förslagsdagen och efter `nav_date`; `days`, `horizon`, `excess`, `excess_kr`, `relative` = (1 + köp) / (1 + sälj) − 1, `annualized` = `relative` i årstakt), `all`/`followed` (träffandel, median, kronsumma) och `by_horizon` (< 30, 30–182, > 182 dagar; median, över 182 dagar i årstakt). Fixtur `facit.json` (issue #197) | `SwitchOutcomeCalcTest` |
| `realized_average_cost` | realiserat resultat per sälj med genomsnittsmetoden, köpavgiften i anskaffningsvärdet – appens regel sedan #192 | Skatteverkets regel; `RealizedGainCalculator` |
| `realized_fifo` | samma med appens regel före #192: FIFO, köpavgiften utanför – kvar som referens | – |

Sålda andelar utan känt köp redovisas i `uncovered_units`, som i appen.

Värdena klistras in i JUnit-testet för motsvarande objekt i `domain/usecase/` med fixturen som
indata (tolerans `1e-9`). `exempel.json` är en påhittad portfölj med två fonder, köp, delsälj och
avgifter – den visar att FIFO och genomsnittsmetoden skiljer 23 kr på första säljet
(köpavgiften) och 438 kr på det andra. `perioder.json` har ett köp mitt i perioden och en fond som
sålts helt och köpts igen; `periodkop.json` (issue #193) har köp och sälj inom dag/vecka/månad/3 mån –
kursen på transaktionsdagarna är transaktionens kurs, så periodradernas kronor går att räkna för hand
(månad 858 kr, vecka 588, dag 156, 3 mån 933, sedan köp 856). Nya fixturer har alltid påhittad data, aldrig riktiga innehav.

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
