---
name: fondmatematik
description: 'Fonders fondmatematik — definitionerna appen räknar efter (tidsviktad och pengaviktad avkastning, årstakt, volatilitet, Sharpe, Sortino, drawdown, genomsnittsmetoden, ISK/KF-skatt, depåskatt, SRI, avgifter, facit för fondbyten) och hur nya formler bevisas med golden-värden ur tools/fondmatte. Ladda alltid när du ändrar eller lägger till en beräkning i domain/usecase, ett nyckeltal, en jämförelse eller en text som förklarar en siffra. Trigger-ord: avkastning, TWR, XIRR, IRR, pengaviktad, tidsviktad, årstakt, CAGR, volatilitet, Sharpe, Sortino, drawdown, beteendegap, anskaffningsvärde, genomsnittsmetoden, FIFO, realiserat, K4, ISK, KF, schablonskatt, depå, skatt, SRI, risknivå, avgift, OCF, årlig avgift, facit, meravkastning, överlapp, genomlysning, ombalansering, golden, referensvärde.'
---

# Fondmatematik

**Invariant:** varje siffra appen visar har **en** definition, och den står här med källa. Den
räknas i ett rent objekt i `domain/usecase/` med JVM-test, och testets förväntade värden kommer
från en **oberoende** beräkning (`tools/fondmatte`), inte från koden som testas. En siffra som
inte är vad användaren tror (t.ex. ett värdeviktat snitt som inte är portföljens risk) säger det
i UI-texten. Kraven står i KRAVLISTA; vid skillnad gäller kravlistan, och skillnaden rättas här.

## Konventioner (gäller alla beräkningar)

| Val | Värde | Var i koden |
|---|---|---|
| Handelsdagar per år (annualisering av dagsdata) | 252 | `FundAnalysisCalc.TRADING_DAYS_PER_YEAR` |
| Kalenderdagar per år (årstakt) | 365,2425 | `FundAnalysisCalc.DAYS_PER_YEAR` |
| Minsta period för årstakt | 365 dagar – kortare visas som total avkastning (GIPS) | `FundAnalysisCalc.CAGR_MIN_DAYS` |
| Standardavvikelse | urval, n − 1 | `FundAnalysisCalc.annualizedVolatility` |
| Riskfri ränta | 0 % | `FundAnalysisCalc.RISK_FREE_RATE` |
| Minsta underlag för volatilitet och Sharpe | 60 dagsavkastningar, annars `null`; volatilitet 0 → `0.0`, Sharpe och Sortino → `null` | `FundAnalysisCalc.MIN_RETURN_SAMPLES` |
| Dagräkning för XIRR | ACT/365F (som Excel och pyxirr); löses med inringning + bisektion i [−99,99 %, +1 000 %] | `PortfolioTotalCalc.XIRR_DAYS_PER_YEAR`, `PortfolioTotalCalc.xirr` |
| Dagsavkastning | `nav_t / nav_{t−1} − 1` mellan på varandra följande kända kurser, NAV ≤ 0 hoppas över | `FundAnalysisCalc.dailyReturns` |
| Okänt värde | `null` och en räknare för det som uteslöts – aldrig noll eller en gissning | t.ex. `PortfolioFeeCalc.Result.unknownFeeCount` |

Belopp är `Double` i kronor; avrundning görs bara vid formatering (`MoneyFormat`).

## Avkastning

| Mått | Definition | Svarar på | I appen |
|---|---|---|---|
| **Tidsviktad (TWR)** | Kedja av dagsfaktorer där gårdagens andelar värderas till gårdagens och dagens NAV; in- och utflöden faller ur | "Hur gick fonderna?" Jämförbart med faktablad och index | `PortfolioReturnSeriesCalc.compute` (HEM-9) |
| **Pengaviktad (XIRR)** | Räntan som nollställer köp (inkl. avgift, negativa), sälj (minus avgift, positiva) och slutvärdet, dag för dag | "Hur gick det för mina pengar?" Påverkas av när jag köpte | `PortfolioTotalCalc.compute` → `moneyWeightedAnnualReturn` (HEM-1, årstakt bara ≥ 365 dagar) |
| Orealiserat på kvarvarande andelar | `(värde − nettoinvesterat) / nettoinvesterat`, där nettoinvesterat är kvarvarande anskaffningsvärde enligt genomsnittsmetoden | Totalkortets rad "Varav orealiserat" – **inte** "vad du tjänat på insatt kapital": realiserat resultat och avgifter på sålda andelar syns inte | `PortfolioCalc.totalGainLossFraction` |
| Totalresultat | `värde + uttag − insättningar`, avgifter inräknade; procent mot insatt netto | "Hur mycket har jag tjänat?" Totalkortets huvudtal | `PortfolioTotalCalc.compute` → `totalGainKr`/`totalGainFraction` (golden: `total_gain`) |
| Periodförändring, procent | Fondens kursutveckling: NAV på referensdagen mot senaste NAV på eller före periodens start; köp och sälj påverkar inte | Dag/vecka/månad (POR-5, HEM-1) och i år/3 mån/1 år/3 år/sedan köp (ANA-1) | `PortfolioPerformanceCalc`, `FundAnalysisCalc` (golden: `funds.<fond>.periods.*.fraction`) |
| Periodförändring, kronor | Värdeförändringen på de andelar som **faktiskt ägts**, dag för dag: `Σ andelar_före_dag_i × (NAV_i − NAV_{i−1})` från startkursen till referensdagen. Ett köp på en kursdag får rörelsen först från nästa kurspunkt, ett sälj har rörelsen fram till säljdagen med; avgifter ingår inte. Startar perioden utan innehav är det slutvärdet minus nettoköpen (andelar × kurs). "Sedan köp" i kr är i stället positionens vinst mot anskaffningsvärdet (genomsnittsmetoden), samma tal som GAV-raden | Vad har mitt innehav ändrats i kronor under perioden? | `PositionValueChangeCalc.valueChange` (issue #193; golden: `funds.<fond>.periods.*.amount`, fixtur `periodkop.json`) |
| Beteendegap | XIRR utan avgifter − TWR i årstakt med samma dagräkning, ACT/365F (bara över ett år, `null` om ett innehav saknat kurs) | Kostade min tajming något? | **Saknas** |

- **Periodrader:** procent och kronor är olika mått – procenten är fondens, kronorna mina. Ett köp
  inom perioden ändrar kronorna men inte procenten; Hems portföljtotal summerar kronorna och väger
  procenten med dagens andelar × startkurs (`PortfolioPerformanceCalc.totalChange`).
- **Visa alltid vilket mått det är.** Avanzas procent är troligen TWR och kronorna orealiserat mot
  insatt; appens siffror kan skilja sig, och hjälptexten säger varför.
- **Jämförelse mot referens med samma kassaflöden:** skuggportföljen köper referensen samma dagar
  och belopp (`PortfolioReturnSeriesCalc.benchmark`, `IndexBenchmarkSelector`, HEM-10). Pengaviktad
  jämförelse görs på samma sätt (public market equivalent).
- **XIRR:** lös med en inringande lösare (Brent), inte ren Newton – extrema kassaflöden kan ge
  flera eller inga lösningar (Sharesight). Biblioteket när det behövs: Apache Commons Math 3.6.1
  (`BrentSolver`, Apache-2.0); ange byggtidseffekten i PR:en (regel 5). Ingen lösning → `null`.

## Anskaffningsvärde och skatt

- **Genomsnittsmetoden** gäller fondandelar (Skatteverket): ett genomsnittligt anskaffningsvärde
  per fond, omräknat vid varje köp; köpavgiften ingår; ett sälj ändrar inte snittet per andel;
  säljavgiften dras från försäljningspriset. Resultatet deklareras på K4. Schablonmetoden (20 %
  av priset) är tillåten för marknadsnoterade andelar.
- **I appen:** `RealizedGainCalculator` (`:core`) räknar genomsnittsmetoden sedan 0.79.1 (issue #192):
  `Holding.netInvested`, GAV, säljradernas resultat och "Realiserat resultat i år" bygger alla på
  den. Positionens startdatum (`firstPurchaseEpochDay`) är första köpet efter att innehavet senast
  var noll. FIFO finns bara kvar som referens i `tools/fondmatte` (`realized_fifo`, `unrealized_fifo`)
  för att visa skillnaden (`exempel.json`: 23 kr på första säljet, 438 kr på det andra).
- **ISK/KF** (`AccountType.ISK_KF`): ingen skatt vid sälj. Schablonskatt = kapitalunderlag ×
  max(1,25 %, statslåneräntan 30/11 året före + 1 pp) × 30 %. Kapitalunderlaget är de fyra
  kvartalens ingående värden plus årets insättningar delat med fyra, **minus den skattefria
  nivån per person** sammanlagt över ISK, KF och PEPP.

  | År | Statslåneränta | Schablonränta | Effektiv skatt | Skattefri nivå |
  |---|---|---|---|---|
  | 2025 | 1,96 % | 2,96 % | 0,888 % | 150 000 kr |
  | 2026 | 2,55 % | 3,55 % | 1,065 % | 300 000 kr |

  Nytt år läggs till här och i `ISK_PARAMETERS` i `tools/fondmatte/fondmatte.py` i samma ändring.
- **Depå/AF** (`AccountType.DEPA_AF`): 30 % på realiserad vinst vid sälj, plus schablonintäkt
  0,4 % av fondernas värde den 1 januari (skatt 0,12 %). Ett byte i depå kostar alltså skatt –
  därför ger bytesplanen inga förslag där (`SwitchPlanResolver`, `FundPriceUpdateWorker`).

## Risk

- **SRI 1–7 från fondens KID är facit** (`FundMetadata.risk`, TP-21). PRIIPs räknar den ur
  VaR-ekvivalent volatilitet; banden är < 0,5 % = 1, 0,5–5 % = 2, 5–12 % = 3, 12–20 % = 4,
  20–30 % = 5, 30–80 % = 6, > 80 % = 7. En egen beräkning används bara som varning om att
  risken glidit, aldrig i stället för KID:ens nivå.
- **Portföljens risknivå** i `PortfolioRiskCalc` är ett värdeviktat snitt av fondernas nivåer, inte
  portföljens risk (korrelation modelleras inte). Texten säger det.
- **Volatilitet** och **Sharpe** som i konventionerna (`FundAnalysisCalc`, ANA-7). **Sortino**,
  när den behövs: nedsidesrisk = √(medel(min(r, 0)²)) över **alla** perioder × √252, täljare
  medel × 252 (empyrical). **Max drawdown** = min(värde / löpande max − 1).
- **Lämplighet** (MiFID II, ESMA35-43-3172) bedöms på hela portföljen och skiljer risktolerans från
  förmåga att bära förlust. Riskprofilens målfördelning (`RiskProfileCalc`) är ett förslag
  användaren äger.
- **Ombalansering**, om den införs: 5/25-regeln – agera när en andel avviker mer än 5 pp absolut
  eller 25 % relativt sitt mål, det som inträffar först.

## Fondbyte och facit

- **Likviddagar är en egenskap per fond**, inte en konstant. Fonder handlas till okänd kurs
  (forward pricing): en order före fondens brytpunkt – ofta 15.00 en bankdag, tidigare för vissa
  utländska fonder och före helgdag – får dagens NAV, annars nästa bankdags.
- **Facit i dag** (`SwitchOutcomeCalc`, SET-5) är meravkastningen köpfond mot säljfond sedan
  förslagsdagen, två NAV-serier – medvetet inte användarens faktiska resultat.
  `SwitchWatchCalc` (ANA-12) nollställer på säljdagens NAV.
- **Fullt facit**, om det byggs: faktisk XIRR mot "behöll fonden" med samma kassaflöden, inklusive
  avgifter, dagarna utanför marknaden (kontant 0 %), skatt i depå och avgiftsskillnaden över tiden.
  Kronor alltid, årstakt bara över ett år.
- **200-dagarssnittet** (`MovingAverageCalc`, ANA-14) presenteras som riskhantering, inte som en
  avkastningsfördel: forskningen (Faber 2007; Zakamulin) visar främst lägre drawdown, och efter
  kostnader är fördelen liten. Faber kontrollerar månadsvis; en daglig signal ger fler falsklarm.

## Avgifter

- **Årlig avgift (OCF)** och **transaktionskostnader** är skilda rader i KID och hålls isär;
  jämförelser (`FeeComparisonCalc`, `PortfolioFeeCalc`) använder årlig avgift.
- Avgift i kronor per år = avgift % × aktuellt värde (`FeeComparisonCalc.annualFeeKr`). Över en
  horisont ränta-på-ränta: skillnaden i slutvärde efter n år är värde × ((1 + r − a₁)ⁿ − (1 + r − a₂)ⁿ),
  där a₁ är den lägre avgiften och den antagna avkastningen r står i texten.
- Underlag för att lyfta billigare alternativ: dyrare fonder ger inte bättre avkastning
  (Pensionsmyndigheten 2022), och de flesta aktiva fonder slår inte sitt index (SPIVA Europe 2025).

## Golden-värden: så bevisas en formel

1. Gör en fixtur i `tools/fondmatte/` (JSON som `exempel.json`) med **påhittad** data som träffar
   fallet: flera köp till olika kurs, delsälj, avgifter, helger, en lucka i kurserna. Transaktioner
   har appens fält (`units`, `price`, `fee`; beloppet är andelar × kurs), så att samma indata går
   att bygga i JUnit.
2. Kör `python tools/fondmatte/fondmatte.py golden <fixtur>` (installation i
   [tools/fondmatte/README.md](../../../tools/fondmatte/README.md)). Saknas måttet: lägg till det i
   `fondmatte.py` med ett handräknat test i `test_fondmatte.py`, helst via referensbiblioteket
   (pyxirr, empyrical-reloaded).
3. Skriv JUnit-testet för objektet i `domain/usecase/` med samma indata och de utskrivna
   värdena, tolerans `1e-9`. Kommentera vilket kommando som gav dem. `realized_average_cost` är
   appens regel (Skatteverkets), `realized_fifo` referensen för hur det räknades före #192.
4. Ändras en konvention ovan: ändra tabellen, `fondmatte.py` och koden i samma PR.

Python behövs bara i sessionen; CI kör bara JUnit-testerna (regel 5).

## Datakällor och referenser

| Källa | Används till | Villkor |
|---|---|---|
| Avanzas fond-API, Handelsbankens fondlista (`data/network`) | NAV, metadata, avgift, risknivå | Inofficiella: tolerant parsning, rimlighetskontroll, reserv |
| Riksbankens SWEA (`RiksbankFxClient`) | Valutakurser | Utan nyckel 5 anrop/min och 1 000/dag; gratis nyckel 200/min |
| ECB, serien `EXR.D.SEK.EUR.SP00.A` | Reserv för valuta | Öppen, ingen nyckel |
| FI:s fondinnehav per kvartal (`tools/fondmatte/fi_innehav.py`) | Överlapp och genomlysning | Bara svenskregistrerade fonder, ca 3,5 mån fördröjning; i 2026Q2 saknar 38 av 726 fonder ISIN och 49 redovisar inga innehav (t.ex. swapbaserade ETF:er) |
| MCP-servern `avanza` (`.mcp.json`) | Slå upp en fonds publika data i sessionen, kontrollera parsrarna | Inofficiell (avanza-mcp 2.1.0, MIT), utan inloggning; beroendena låses med `--exclude-newer` – flytta datumet bara medvetet |
| Portfolio Performance (github.com/portfolio-performance/portfolio) | Förlaga för IRR, TTWROR, drawdown, volatilitet | EPL-1.0: porta formler och testfall, kopiera inte filer |

Integritet (skill `data-privacy-security`): ingen källa eller MCP-server får användarens innehav,
belopp eller transaktioner. Slå upp fonder ur katalogen eller enstaka ISIN, aldrig "min portfölj".
MCP-serverns BankID-läge (`AVANZA_MCP_AUTH=1`) används inte.

## Källor

- GIPS, ingen årstakt under ett år: https://www.gipsstandards.org/?GIPSID=370
- Portfolio Performance, avkastningsbegrepp: https://help.portfolio-performance.info/en/concepts/performance/
- Sharesight, beräkningsmetod: https://help.sharesight.com/performance_calculation_method/
- Skatteverket, fonder och genomsnittsmetoden: https://www.skatteverket.se/privat/skatter/vardepapper/omovrigavardepapper/fonder.4.19b9f599116a9e8ef36800010782.html
- Skatteverket, investeringssparkonto: https://www.skatteverket.se/privat/etjansterochblanketter/svarpavanligafragor/investeringssparkonto.4.71004e4c133e23bf6db80001071.html
- PRIIPs bilaga II (SRI): https://handbook.fca.org.uk/techstandards/PRIIPs/2017/reg_del_2017_653_oj/annex02.html
- ESMA, MiFID II-lämplighet: https://www.esma.europa.eu/document/guidelines-certain-aspects-mifid-ii-suitability-requirements-1
- FI, fondinnehav per kvartal: https://www.fi.se/sv/vara-register/fondinnehav-per-kvartal/
- Riksbankens API: https://www.riksbank.se/en-gb/statistics/interest-rates-and-exchange-rates/retrieving-interest-rates-and-exchange-rates-via-api/faq--the-api-for-interest-rates-and-exchange-rates/
- Morningstar, Mind the Gap 2025: https://www.morningstar.com/business/insights/research/mind-the-gap-2025
- Glidande medel efter kostnader: https://www.cxoadvisory.com/technical-trading/net-performance-of-sma-and-intrinsic-momentum-timing-strategies
- Pensionsmyndigheten, dyrare fonder: https://www.pensionsmyndigheten.se/content/dam/pensionsmyndigheten/blanketter---broschyrer---faktablad/publikationer/rapporter/2022/Lonar-det-sig-att-valja-dyrare-fonder.pdf
- SPIVA Europe 2025: https://www.spglobal.com/spdji/en/documents/spiva/spiva-europe-year-end-2025.pdf
- 5/25-regeln: https://awealthofcommonsense.com/2014/03/larry-swedroe-525-rebalancing-rule/
