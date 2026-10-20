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

`golden` läser en fixtur (JSON: `transactions`, `prices` per fond, `end`) och skriver ut:

| Del | Innehåll | Oberoende källa |
|---|---|---|
| `portfolio` | marknadsvärde, kedjad TWR (total och årstakt), XIRR, beteendegapet (XIRR − TWR) | pyxirr för XIRR; TWR skriven efter definitionen i skillen |
| `funds` | total avkastning, CAGR, volatilitet, Sharpe, Sortino, max drawdown per fond | empyrical-reloaded |
| `realized_average_cost` | realiserat resultat per sälj med genomsnittsmetoden | Skatteverkets regel |
| `realized_fifo` | samma med FIFO, som appen räknar i dag | – |

Värdena klistras in i JUnit-testet för motsvarande objekt i `domain/usecase/` med fixturen som
indata (tolerans `1e-9`). `exempel.json` är en påhittad portfölj med två fonder, köp, delsälj och
avgifter – den visar bland annat att FIFO och genomsnittsmetoden ger olika resultat på andra
säljet. Nya fixturer har alltid påhittad data, aldrig riktiga innehav.

## `fi_innehav.py` – Finansinspektionens fondinnehav

```bash
python tools/fondmatte/fi_innehav.py hamta 2026Q2 --dir /tmp/fi       # ≈ 7 MB
python tools/fondmatte/fi_innehav.py fond /tmp/fi/Fondinnehav_2026Q2.zip SE0003788587 --top 10
python tools/fondmatte/fi_innehav.py overlapp /tmp/fi/Fondinnehav_2026Q2.zip SE0001466368 SE0003788587
python tools/fondmatte/fi_innehav.py genomlysning /tmp/fi/Fondinnehav_2026Q2.zip SE0001466368=0.5 SE0024245476=0.5
```

Överlapp = Σ min(vikt i A, vikt i B) över innehav med samma ISIN. Genomlysning väger ihop
fondernas innehav per land, bransch och värdepapper.

Kända begränsningar: bara **svenskregistrerade** fonder (inte LU/IE), ett kvartal i taget med
ca 3,5 månaders fördröjning, fondens ISIN är fondens (inte andelsklassens), och branschnamnen
skiljer mellan fondbolag (vissa GICS på engelska, andra på svenska). Lägg ZIP-filen utanför repot.
