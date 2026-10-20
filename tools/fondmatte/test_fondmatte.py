"""Handräknade fall för fondmatte.py och fi_innehav.py – kör med
`python -m unittest discover -s tools/fondmatte` i en miljö med requirements.txt."""

import json
import math
import unittest
from datetime import date, timedelta
from pathlib import Path

import fi_innehav
import fondmatte as fm


def tx(day, kind, units, price, fee=0.0, fund="A"):
    return fm.Transaction(date.fromisoformat(day), fund, kind, units, price, fee)


def prices(**series):
    return fm.Prices({f: [(date.fromisoformat(d), v) for d, v in points] for f, points in series.items()})


class ReturnTest(unittest.TestCase):
    def test_twr_ignores_deposits(self):
        # NAV 100 → 110 → 121: två dagar med +10 %, köp båda dagarna. TWR = 1,1² − 1.
        p = prices(A=[("2025-01-01", 100.0), ("2025-01-02", 110.0), ("2025-01-03", 121.0)])
        txs = [tx("2025-01-01", "BUY", 10, 100), tx("2025-01-02", "BUY", 100, 110)]
        twr, partial = fm.time_weighted_return(txs, p, date(2025, 1, 3))
        self.assertAlmostEqual(twr, 0.21, places=12)
        self.assertFalse(partial)

    def test_twr_excludes_holding_without_nav_and_marks_partial(self):
        # B köps innan den har någon kurs: dagen B får sin första kurs ska inte räknas som en
        # uppgång för hela portföljen (B saknade gårdagens NAV), bara A:s +10 % ska synas.
        p = prices(A=[("2025-01-01", 100.0), ("2025-01-02", 100.0), ("2025-01-03", 110.0)], B=[("2025-01-03", 50.0)])
        txs = [tx("2025-01-01", "BUY", 10, 100), tx("2025-01-02", "BUY", 100, 50, fund="B")]
        twr, partial = fm.time_weighted_return(txs, p, date(2025, 1, 3))
        self.assertAlmostEqual(twr, 0.10, places=12)
        self.assertTrue(partial)

    def test_xirr_is_act_365f(self):
        # 1 000 kr in, 1 100 kr 365 dagar senare ger 10 % per år (ACT/365F, som Excel).
        p = prices(A=[("2025-01-01", 100.0), ("2026-01-01", 110.0)])
        xirr, partial = fm.money_weighted_return([tx("2025-01-01", "BUY", 10, 100)], p, date(2026, 1, 1))
        self.assertAlmostEqual(xirr, 0.10, places=9)
        self.assertFalse(partial)

    def test_behaviour_gap_is_zero_without_timing_even_with_fees(self):
        # Ett köp med courtage och sedan inga kassaflöden: inget att tajma, gapet ska vara noll,
        # medan XIRR med avgift blir lägre än den utan.
        start = date(2024, 3, 1)
        series = [((start + timedelta(n)).isoformat(), 100.0 * 1.0003**n) for n in range(800)]
        fixture = {"end": series[-1][0], "transactions": [{"date": start.isoformat(), "fund": "A", "kind": "BUY", "units": 10, "price": 100.0, "fee": 39.0}], "prices": {"A": series}}
        portfolio = fm.golden(fixture)["portfolio"]
        self.assertAlmostEqual(portfolio["behaviour_gap"], 0.0, places=9)
        self.assertAlmostEqual(portfolio["behaviour_gap"], portfolio["xirr_excluding_fees"] - portfolio["twr_annualized_act365"], places=15)
        self.assertLess(portfolio["xirr"], portfolio["xirr_excluding_fees"])

    def test_fixture_errors_are_reported_as_fixture_errors(self):
        with self.assertRaises(fm.FixtureError):
            fm.golden({"end": "2025-01-01", "transactions": [], "prices": {}})
        with self.assertRaises(fm.FixtureError):
            fm.golden({"end": "2025-01-01", "transactions": [{"date": "2025-01-01", "fund": "A", "kind": "BUY", "units": 1, "amount": 5}], "prices": {"A": []}})

    def test_oversold_units_leave_no_negative_position(self):
        # Som appens remainingPositions: säljet tar innehavet till noll, inte till −5.
        p = prices(A=[("2025-01-01", 100.0), ("2025-03-01", 110.0)])
        txs = [tx("2025-01-01", "BUY", 10, 100), tx("2025-02-01", "SELL", 15, 100), tx("2025-03-01", "BUY", 10, 110)]
        self.assertEqual(fm.market_value(txs, p, date(2025, 3, 1)), (1100.0, False))

    def test_missing_end_nav_marks_xirr_partial_and_drops_the_gap(self):
        start = date(2024, 1, 1)
        a = [((start + timedelta(n)).isoformat(), 100.0 + n) for n in range(500)]
        fixture = {
            "end": a[-1][0],
            "transactions": [
                {"date": start.isoformat(), "fund": "A", "kind": "BUY", "units": 10, "price": 100.0},
                {"date": start.isoformat(), "fund": "B", "kind": "BUY", "units": 10, "price": 100.0},
            ],
            "prices": {"A": a, "B": []},
        }
        portfolio = fm.golden(fixture)["portfolio"]
        self.assertTrue(portfolio["xirr_partial"])
        self.assertIsNone(portfolio["behaviour_gap"])

    def test_no_annualizing_under_a_year(self):
        self.assertIsNone(fm.annualized(0.05, 364))
        self.assertAlmostEqual(fm.annualized(0.21, 730), 1.21 ** (fm.DAYS_PER_YEAR / 730) - 1, places=12)


class RiskTest(unittest.TestCase):
    @staticmethod
    def series(navs):
        return [(date(2025, 1, 1) + timedelta(d), v) for d, v in enumerate(navs)]

    def test_volatility_is_sample_std_times_sqrt_252(self):
        navs = [100.0 * (1.01 if d % 3 else 0.985) ** d for d in range(61)]
        r = fm.daily_returns(self.series(navs))
        mean = sum(r) / len(r)
        expected = math.sqrt(sum((x - mean) ** 2 for x in r) / (len(r) - 1)) * math.sqrt(252)
        self.assertAlmostEqual(fm.risk_figures(self.series(navs))["annualized_volatility"], expected, places=12)

    def test_short_series_gives_no_volatility_like_the_app(self):
        figures = fm.risk_figures(self.series([100.0, 101.0, 99.0, 102.0]))
        self.assertEqual((figures["annualized_volatility"], figures["sharpe"], figures["sortino"]), (None, None, None))

    def test_zero_volatility_is_zero_and_sharpe_none_like_the_app(self):
        figures = fm.risk_figures(self.series([100.0] * 70))
        self.assertEqual((figures["annualized_volatility"], figures["sharpe"], figures["sortino"]), (0.0, None, None))

    def test_duplicate_date_uses_last_price_like_the_app(self):
        p = prices(A=[("2025-03-03", 101.0), ("2025-03-03", 100.5)])
        self.assertEqual(p.nav("A", date(2025, 3, 3)), 100.5)

    def test_max_drawdown(self):
        self.assertAlmostEqual(fm.risk_figures(self.series([100.0, 120.0, 90.0, 130.0]))["max_drawdown"], 90 / 120 - 1, places=12)

    def test_output_is_valid_json_without_negative_days(self):
        # Bara uppgångar ger oändlig Sortino i empyrical; utskriften ska ha null, inte Infinity.
        figures = fm.finite_or_none(fm.risk_figures(self.series([100.0 * 1.001**d for d in range(70)])))
        self.assertIsNone(figures["sortino"])
        json.dumps(figures, allow_nan=False)


class CostBasisTest(unittest.TestCase):
    TXS = [
        tx("2025-01-01", "BUY", 100, 100),
        tx("2025-02-01", "BUY", 100, 119, fee=100),
        tx("2025-03-01", "SELL", 50, 121, fee=50),
    ]

    def test_average_cost(self):
        # Snitt (10 000 + 11 900 + 100) / 200 = 110 kr; 50 andelar kostar 5 500.
        # Resultat 50 × 121 − 50 − 5 500 = 500 kr.
        sale = fm.average_cost_sales(self.TXS)[0]
        self.assertAlmostEqual(sale.cost_basis, 5500.0)
        self.assertAlmostEqual(sale.gain, 500.0)

    def test_fifo_like_app_excludes_buy_fee(self):
        # Appens FIFO: äldsta lotten, 50 × 100 kr, köpavgiften räknas inte in.
        sale = fm.fifo_sales(self.TXS)[0]
        self.assertAlmostEqual(sale.cost_basis, 5000.0)
        self.assertAlmostEqual(sale.gain, 6050.0 - 50.0 - 5000.0)

    def test_oversold_units_are_uncovered_and_do_not_corrupt_the_average(self):
        txs = [tx("2025-01-01", "BUY", 10, 100), tx("2025-02-01", "SELL", 15, 100), tx("2025-03-01", "BUY", 10, 200), tx("2025-04-01", "SELL", 5, 200)]
        first, second = fm.average_cost_sales(txs)
        self.assertEqual((first.cost_basis, first.uncovered_units), (1000.0, 5.0))
        self.assertEqual((second.cost_basis, second.uncovered_units), (1000.0, 0.0))
        self.assertEqual(fm.fifo_sales(txs)[0].uncovered_units, 5.0)


class IskTest(unittest.TestCase):
    def test_2026(self):
        # (4 × 400 000) / 4 − 300 000 = 100 000; 100 000 × 3,55 % × 30 % = 1 065 kr.
        self.assertAlmostEqual(fm.isk_tax(2026, [400_000] * 4, 0), 1065.0)

    def test_2025(self):
        # 400 000 − 150 000 = 250 000; × 2,96 % × 30 % = 2 220 kr.
        self.assertAlmostEqual(fm.isk_tax(2025, [400_000] * 4, 0), 2220.0)

    def test_below_tax_free_amount(self):
        self.assertEqual(fm.isk_tax(2026, [200_000] * 4, 50_000), 0.0)


class TotalGainTest(unittest.TestCase):
    def test_total_gain_counts_realized_loss_app_card_does_not(self):
        # Köp 100 × 100 + 10 kr, sälj 50 × 80 − 10 kr: insatt netto 10 010 − 3 990 = 6 020 kr.
        # Kvar 50 andelar à 120 = 6 000 kr: totalt −20 kr, men appens kort (FIFO-kvar 5 000 kr)
        # visar +1 000 kr eftersom förlusten på säljet inte syns.
        trades = [tx("2025-01-02", "BUY", 100, 100.0, 10.0), tx("2025-03-03", "SELL", 50, 80.0, 10.0)]
        self.assertAlmostEqual(fm.net_deposits(trades), 6020.0)
        self.assertAlmostEqual(fm.fifo_remaining_cost(trades), 5000.0)


class ExampleFixtureTest(unittest.TestCase):
    def test_example_runs_and_methods_differ(self):
        with open(Path(__file__).with_name("exempel.json"), encoding="utf-8") as f:
            result = fm.golden(json.load(f))
        json.dumps(result, allow_nan=False)
        self.assertFalse(result["portfolio"]["twr_partial"])
        average, fifo = result["realized_average_cost"][1], result["realized_fifo_as_app"][1]
        self.assertNotAlmostEqual(average["gain"], fifo["gain"], places=0)
        portfolio = result["portfolio"]
        self.assertLess(portfolio["total_gain"], portfolio["unrealized_fifo_as_app"])


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
        <Bransch><Branschkod_instrument>20</Branschkod_instrument><Bransch_namn_instrument>Industrials</Bransch_namn_instrument></Bransch>
      </FinansielltInstrument>
      <FinansielltInstrument>
        <Instrumentnamn>FXFW_EUR/SEK_20260713</Instrumentnamn><Andel_av_fondförmögenhet_instrument>-0.4</Andel_av_fondförmögenhet_instrument>
        <Bransch><Branschkod_instrument>0</Branschkod_instrument><Bransch_namn_instrument>Other</Bransch_namn_instrument></Bransch>
      </FinansielltInstrument>
    </FinansiellaInstrument>
  </Fondinformation>
</VärdepappersfondInnehav>""".encode()


class FiParseTest(unittest.TestCase):
    def test_parses_namespaced_xml_and_normalizes_sector_by_gics_code(self):
        fund = fi_innehav.parse_fund(FI_XML)
        self.assertEqual((fund.isin, fund.name, fund.company, fund.quarter_end, fund.std_24m), ("SE0000000001", "Påhittad Fond", "Påhittade Fonder AB", "2026-06-30", 12.5))
        self.assertEqual([(h.isin, h.weight, h.country, h.sector) for h in fund.holdings], [("SE0000000002", 0.065, "SE", "Industri"), (None, -0.004, "?", "Övrigt")])
        self.assertEqual(fund.overlap_weights, {"SE0000000002": 0.065})


def holding(isin, weight, country="SE", sector="Industri"):
    return fi_innehav.Holding(isin, isin or "Likvida medel", weight, country, sector)


class OverlapTest(unittest.TestCase):
    def test_overlap_is_sum_of_minimum_weights(self):
        a = {"X": 0.5, "Y": 0.3, "Z": 0.2}
        b = {"X": 0.2, "Y": 0.4, "W": 0.4}
        self.assertAlmostEqual(fi_innehav.overlap(a, b), 0.2 + 0.3)

    def test_cash_and_derivatives_do_not_overlap(self):
        a = fi_innehav.Fund("A", "A", "", "", None, [holding("SE0000000002", 0.5), holding(None, 0.03), holding("SE0000000003", -0.004)])
        b = fi_innehav.Fund("B", "B", "", "", None, [holding("SE0000000002", 0.4), holding(None, 0.03), holding("SE0000000003", -0.004)])
        self.assertAlmostEqual(fi_innehav.overlap(a.overlap_weights, b.overlap_weights), 0.4)

    def test_look_through_sums_to_one(self):
        funds = {
            "A": fi_innehav.Fund("A", "A", "", "", None, [holding("SE0000000002", 0.6), holding(None, -0.01)]),
            "B": fi_innehav.Fund("B", "B", "", "", None, [holding("US0000000004", 0.97, "US", "Informationsteknik")]),
        }
        result = fi_innehav.look_through(funds, fi_innehav.normalized({"A": 2, "B": 2}), "country")
        self.assertAlmostEqual(sum(result.values()), 1.0)
        self.assertAlmostEqual(result["SE"], 0.3)
        self.assertAlmostEqual(result[fi_innehav.UNREPORTED], 0.5 * 0.41 + 0.5 * 0.03)

    def test_leverage_gets_its_own_row(self):
        funds = {"A": fi_innehav.Fund("A", "A", "", "", None, [holding("SE0000000002", 1.087)])}
        result = fi_innehav.look_through(funds, {"A": 1.0}, "sector")
        self.assertAlmostEqual(result[fi_innehav.LEVERAGE], -0.087)
        rounded = fi_innehav.look_through({"B": fi_innehav.Fund("B", "B", "", "", None, [holding("SE0000000002", 1.004)])}, {"B": 1.0}, "sector")
        self.assertNotIn(fi_innehav.LEVERAGE, rounded)
        self.assertNotIn(fi_innehav.UNREPORTED, result)
        self.assertAlmostEqual(sum(result.values()), 1.0)


if __name__ == "__main__":
    unittest.main()
