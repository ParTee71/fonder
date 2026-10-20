"""Handräknade fall för fondmatte.py och fi_innehav.py – kör med
`python -m unittest discover -s tools/fondmatte` i en miljö med requirements.txt."""

import math
import unittest
from datetime import date

import fi_innehav
import fondmatte as fm


def tx(day, kind, units, amount, fee=0.0, fund="A"):
    return fm.Transaction(date.fromisoformat(day), fund, kind, units, amount, fee)


class ReturnTest(unittest.TestCase):
    def test_twr_ignores_deposits(self):
        # NAV 100 → 110 → 121: två dagar med +10 %, köp båda dagarna. TWR = 1,1² − 1.
        prices = {"A": [(date(2025, 1, 1), 100.0), (date(2025, 1, 2), 110.0), (date(2025, 1, 3), 121.0)]}
        txs = [tx("2025-01-01", "BUY", 10, 1000), tx("2025-01-02", "BUY", 100, 11000)]
        self.assertAlmostEqual(fm.time_weighted_return(txs, prices, date(2025, 1, 3)), 0.21, places=12)

    def test_xirr_one_year(self):
        # 1 000 kr in, 1 100 kr ett år senare (365 dagar) ger 10 % per år.
        prices = {"A": [(date(2025, 1, 1), 100.0), (date(2026, 1, 1), 110.0)]}
        txs = [tx("2025-01-01", "BUY", 10, 1000)]
        self.assertAlmostEqual(fm.money_weighted_return(txs, prices, date(2026, 1, 1)), 0.10, places=9)

    def test_no_annualizing_under_a_year(self):
        self.assertIsNone(fm.annualized(0.05, 364))
        self.assertAlmostEqual(fm.annualized(0.21, 730), 1.21 ** (fm.DAYS_PER_YEAR / 730) - 1, places=12)


class RiskTest(unittest.TestCase):
    def test_volatility_is_sample_std_times_sqrt_252(self):
        navs = [100.0, 101.0, 99.0, 102.0, 101.5]
        prices = [(date(2025, 1, d + 1), v) for d, v in enumerate(navs)]
        r = fm.daily_returns(prices)
        mean = sum(r) / len(r)
        expected = math.sqrt(sum((x - mean) ** 2 for x in r) / (len(r) - 1)) * math.sqrt(252)
        self.assertAlmostEqual(fm.risk_figures(prices)["annualized_volatility"], expected, places=12)

    def test_max_drawdown(self):
        prices = [(date(2025, 1, d + 1), v) for d, v in enumerate([100.0, 120.0, 90.0, 130.0])]
        self.assertAlmostEqual(fm.risk_figures(prices)["max_drawdown"], 90 / 120 - 1, places=12)


class CostBasisTest(unittest.TestCase):
    TXS = [
        tx("2025-01-01", "BUY", 100, 10000),
        tx("2025-02-01", "BUY", 100, 11900, fee=100),
        tx("2025-03-01", "SELL", 50, 6050, fee=50),
    ]

    def test_average_cost(self):
        # Snitt (10 000 + 12 000) / 200 = 110 kr; 50 andelar kostar 5 500, pris 6 000 efter avgift.
        sale = fm.average_cost_sales(self.TXS)[0]
        self.assertAlmostEqual(sale.cost_basis, 5500.0)
        self.assertAlmostEqual(sale.gain, 500.0)

    def test_fifo_differs(self):
        sale = fm.fifo_sales(self.TXS)[0]
        self.assertAlmostEqual(sale.cost_basis, 5000.0)
        self.assertAlmostEqual(sale.gain, 1000.0)


class IskTest(unittest.TestCase):
    def test_2026(self):
        # (4 × 400 000) / 4 − 300 000 = 100 000; 100 000 × 3,55 % × 30 % = 1 065 kr.
        self.assertAlmostEqual(fm.isk_tax(2026, [400_000] * 4, 0), 1065.0)

    def test_below_tax_free_amount(self):
        self.assertEqual(fm.isk_tax(2026, [200_000] * 4, 50_000), 0.0)


FI_XML = """<?xml version="1.0" encoding="utf-8"?>
<VärdepappersfondInnehav xmlns="http://schemas.fi.se/publika/vardepappersfonder/20200331">
  <Rapportinformation><Kvartalsslut>2026-06-30</Kvartalsslut></Rapportinformation>
  <Bolagsinformation><Fondbolag_namn>Påhittade Fonder AB</Fondbolag_namn></Bolagsinformation>
  <Fondinformation>
    <Fond_ISIN-kod>SE0000000001</Fond_ISIN-kod>
    <Fond_namn>Påhittad Fond</Fond_namn>
    <Standardavvikelse_24_månader>12.5</Standardavvikelse_24_månader>
    <FinansiellaInstrument>
      <FinansielltInstrument>
        <Instrumentnamn>BOLAG A</Instrumentnamn><ISIN-kod_instrument>SE0000000002</ISIN-kod_instrument>
        <Landkod_Emittent>SE</Landkod_Emittent><Andel_av_fondförmögenhet_instrument>6.5</Andel_av_fondförmögenhet_instrument>
        <Bransch><Bransch_namn_instrument>Industri</Bransch_namn_instrument></Bransch>
      </FinansielltInstrument>
      <FinansielltInstrument>
        <Instrumentnamn>Terminskontrakt</Instrumentnamn><Andel_av_fondförmögenhet_instrument>-0.4</Andel_av_fondförmögenhet_instrument>
      </FinansielltInstrument>
    </FinansiellaInstrument>
  </Fondinformation>
</VärdepappersfondInnehav>""".encode()


class FiParseTest(unittest.TestCase):
    def test_parses_namespaced_xml(self):
        fund = fi_innehav.parse_fund(FI_XML)
        self.assertEqual((fund.isin, fund.name, fund.company, fund.quarter_end, fund.std_24m), ("SE0000000001", "Påhittad Fond", "Påhittade Fonder AB", "2026-06-30", 12.5))
        self.assertEqual([(h.key, h.weight, h.country, h.sector) for h in fund.holdings], [("SE0000000002", 0.065, "SE", "Industri"), ("TERMINSKONTRAKT", -0.004, "?", "Okänd")])


class OverlapTest(unittest.TestCase):
    def test_overlap_is_sum_of_minimum_weights(self):
        a = {"X": 0.5, "Y": 0.3, "Z": 0.2}
        b = {"X": 0.2, "Y": 0.4, "W": 0.4}
        self.assertAlmostEqual(fi_innehav.overlap(a, b), 0.2 + 0.3)


if __name__ == "__main__":
    unittest.main()
