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
        # 400 dagliga kurser från 2025-01-01: fönstret är 1 år bakåt från sista kursen (index 399),
        # alltså från index 34 – de äldre kurserna räknas inte.
        navs = [100.0 * (1.01 if d % 3 else 0.985) ** d for d in range(400)]
        r = fm.daily_returns(self.series(navs[34:]))
        mean = sum(r) / len(r)
        expected = math.sqrt(sum((x - mean) ** 2 for x in r) / (len(r) - 1)) * math.sqrt(252)
        self.assertAlmostEqual(fm.risk_figures(self.series(navs))["annualized_volatility"], expected, places=12)

    def test_short_series_gives_no_volatility_like_the_app(self):
        figures = fm.risk_figures(self.series([100.0, 101.0, 99.0, 102.0]))
        self.assertEqual((figures["annualized_volatility"], figures["sharpe"], figures["sortino"]), (None, None, None))

    def test_zero_volatility_is_zero_and_sharpe_none_like_the_app(self):
        figures = fm.risk_figures(self.series([100.0] * 400))
        self.assertEqual((figures["annualized_volatility"], figures["sharpe"], figures["sortino"]), (0.0, None, None))

    def test_under_a_year_gives_no_volatility_even_with_many_samples(self):
        # 365 dagliga kurser når inte senaste − 1 år (2025-12-31 − 1 år = 2024-12-31).
        figures = fm.risk_figures(self.series([100.0 + d % 7 for d in range(365)]))
        self.assertEqual((figures["annualized_volatility"], figures["risk_window_years"], figures["sharpe"]), (None, None, None))

    def test_window_is_three_years_and_older_prices_do_not_count(self):
        # Fyra år där det äldsta året är vilt: samma volatilitet som bara de tre senaste åren.
        start = date(2022, 1, 1)
        calm = [(start + timedelta(d), 100.0 + d % 7) for d in range(364, 4 * 365 + 1)]
        wild = [(start + timedelta(d), 40.0 if d % 2 else 160.0) for d in range(364)]
        latest = calm[-1][0]
        window = [(d, v) for d, v in calm if d >= fm.minus_months(latest, 36)]
        self.assertEqual(window[0][0], fm.minus_months(latest, 36))
        full, only_window = fm.risk_figures(wild + calm), fm.risk_figures(window)
        self.assertEqual(full["risk_window_years"], 3)
        self.assertAlmostEqual(full["annualized_volatility"], only_window["annualized_volatility"], places=12)
        self.assertAlmostEqual(full["sharpe"], only_window["sharpe"], places=12)

    def test_gap_over_five_days_is_skipped_and_five_days_is_counted(self):
        # Hopp på +100 % över en lucka: 10 dagar hoppas över (ingen skillnad), 5 dagar räknas.
        def series(gap_days, jump):
            base = [(date(2025, 1, 1) + timedelta(d), 99.0 if d % 2 else 101.0) for d in range(400)]
            kept = [(d, v) for d, v in base if not 200 <= (d - date(2025, 1, 1)).days < 200 + gap_days - 1]
            return [(d, v * jump if (d - date(2025, 1, 1)).days >= 200 + gap_days - 1 else v) for d, v in kept]

        self.assertAlmostEqual(fm.risk_figures(series(10, 2.0))["annualized_volatility"], fm.risk_figures(series(10, 1.0))["annualized_volatility"], places=12)
        self.assertGreater(fm.risk_figures(series(5, 2.0))["annualized_volatility"], 2 * fm.risk_figures(series(5, 1.0))["annualized_volatility"])

    def test_sharpe_keeps_the_move_over_a_skipped_gap(self):
        # Kursen faller 30 % över en lucka på 10 dagar: volatiliteten ser inte luckan, men Sharpe
        # räknas på fönstrets totala utveckling i årstakt och blir lägre än utan fallet.
        def series(drop):
            base = [(date(2025, 1, 1) + timedelta(d), 100.0 + d % 7) for d in range(400)]
            kept = [(d, v) for d, v in base if not 200 <= (d - date(2025, 1, 1)).days < 209]
            return [(d, v * drop if (d - date(2025, 1, 1)).days >= 209 else v) for d, v in kept]

        flat, fallen = fm.risk_figures(series(1.0)), fm.risk_figures(series(0.7))
        self.assertAlmostEqual(flat["annualized_volatility"], fallen["annualized_volatility"], places=12)
        self.assertLess(fallen["sharpe"], flat["sharpe"])
        self.assertLess(fallen["sharpe"], 0.0)
        _, window = fm.risk_window(series(0.7))
        (d0, v0), (d1, v1) = window[0], window[-1]
        expected = ((v1 / v0) ** (fm.DAYS_PER_YEAR / (d1 - d0).days) - 1.0) / fallen["annualized_volatility"]
        self.assertAlmostEqual(fallen["sharpe"], expected, places=12)

    def test_unsorted_points_give_the_same_window_and_figures(self):
        with open(Path(__file__).with_name("riskfonster.json"), encoding="utf-8") as f:
            fixture = json.load(f)
        points = [(date.fromisoformat(d), v) for d, v in fixture["prices"]["A"]]
        shuffled = points[1::2] + points[0::2][::-1]
        self.assertNotEqual(shuffled, points)
        self.assertEqual(fm.risk_window(shuffled), fm.risk_window(points))
        self.assertEqual(fm.risk_figures(shuffled), fm.risk_figures(points))
        unsorted = dict(fixture, prices={"A": [[d.isoformat(), v] for d, v in shuffled]})
        self.assertEqual(fm.golden(unsorted)["funds"], fm.golden(fixture)["funds"])

    def test_min_window_returns_is_60_percent_of_252_per_year(self):
        self.assertEqual((fm.min_window_returns(1), fm.min_window_returns(3)), (152, 454))

    def test_sparse_three_years_falls_back_to_one_year(self):
        # Veckokurser de två äldsta åren (inga giltiga par) och dagliga det senaste: treårsfönstret
        # har för få dagsavkastningar, så etiketten blir 1 år och talen bara det senaste årets.
        start = date(2022, 1, 1)
        latest = start + timedelta(3 * 365 + 1)
        one_year = fm.minus_months(latest, 12)
        weekly = [(start + timedelta(d), 100.0 + d % 11) for d in range(0, (one_year - start).days, 7)]
        daily = [(one_year + timedelta(d), 100.0 + d % 7) for d in range((latest - one_year).days + 1)]
        figures, only_daily = fm.risk_figures(weekly + daily), fm.risk_figures(daily)
        self.assertEqual(figures["risk_window_years"], 1)
        self.assertAlmostEqual(figures["annualized_volatility"], only_daily["annualized_volatility"], places=12)

    def test_too_sparse_one_year_gives_none(self):
        # Ankare exakt ett år bakåt, veckokurser (inga avkastningar, inget glapp över 31 dagar) och
        # sedan n dagliga avkastningar: 152 räcker, 151 inte.
        latest = date(2026, 1, 1)
        one_year = (latest - fm.minus_months(latest, 12)).days

        def series(n):
            weekly = [(latest - timedelta(d), 100.0 + d % 11) for d in range(one_year, n + 5, -7)]
            return weekly + [(latest - timedelta(d), 100.0 + d % 7) for d in range(n, -1, -1)]

        self.assertEqual(fm.risk_figures(series(152))["risk_window_years"], 1)
        none = fm.risk_figures(series(151))
        self.assertEqual((none["risk_window_years"], none["annualized_volatility"], none["sharpe"]), (None, None, None))

    def test_anchor_at_most_five_days_before_window_start(self):
        latest = date(2026, 1, 1)
        one_year = (latest - fm.minus_months(latest, 12)).days

        def series(anchor_before):
            return [(latest - timedelta(one_year + anchor_before), 100.0)] + [(latest - timedelta(d), 100.0 + d % 7) for d in range(one_year - 1, -1, -1)]

        self.assertEqual(fm.risk_window(series(5))[0], 1)
        self.assertIsNone(fm.risk_window(series(6)))

    def test_gap_over_31_days_invalidates_the_window(self):
        latest = date(2026, 1, 1)
        one_year = (latest - fm.minus_months(latest, 12)).days

        def series(gap):
            return [(latest - timedelta(d), 100.0 + d % 7) for d in range(one_year, -1, -1) if not 150 <= d < 150 + gap - 1]

        self.assertEqual(fm.risk_window(series(31))[0], 1)
        self.assertIsNone(fm.risk_window(series(32)))

    def test_riskfonster_fixture_uses_one_year_and_skips_the_july_gap(self):
        with open(Path(__file__).with_name("riskfonster.json"), encoding="utf-8") as f:
            fixture = json.load(f)
        a = fm.golden(fixture)["funds"]["A"]
        points = [(date.fromisoformat(d), v) for d, v in fixture["prices"]["A"]]
        years, window = fm.risk_window(points)
        self.assertEqual((years, a["risk_window_years"]), (1, 1))
        self.assertEqual(window[0][0], date(2025, 3, 13))
        gaps = [(b[0] - p[0]).days for p, b in zip(window, window[1:])]
        self.assertIn(17, gaps)  # 4/7 → 21/7 hoppas över
        self.assertIn(5, gaps)  # påsken 17/4 → 22/4 räknas
        self.assertEqual(a["samples"], sum(1 for g in gaps if g <= 5))
        self.assertEqual(a["samples"], len(window) - 2)

    def test_duplicate_date_uses_last_price_like_the_app(self):
        p = prices(A=[("2025-03-03", 101.0), ("2025-03-03", 100.5)])
        self.assertEqual(p.nav("A", date(2025, 3, 3)), 100.5)

    def test_max_drawdown(self):
        self.assertAlmostEqual(fm.risk_figures(self.series([100.0, 120.0, 90.0, 130.0]))["max_drawdown"], 90 / 120 - 1, places=12)

    def test_output_is_valid_json_without_negative_days(self):
        # Bara uppgångar ger oändlig Sortino i empyrical; utskriften ska ha null, inte Infinity.
        figures = fm.finite_or_none(fm.risk_figures(self.series([100.0 * 1.001**d for d in range(400)])))
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
        # Genomsnittsmetoden: (10 000 + 10) / 100 = 100,1 kr per andel, 50 kvar.
        self.assertAlmostEqual(fm.average_cost_remaining(trades), 5005.0)


class PeriodRowsTest(unittest.TestCase):
    """Periodradernas kronor = värdeförändring på ägda andelar, dag för dag (issue #193)."""

    P = prices(A=[("2026-01-01", 100.0), ("2026-01-02", 110.0), ("2026-01-03", 99.0), ("2026-01-04", 120.0)])

    def test_buy_on_a_day_gets_the_move_from_the_next_point(self):
        # Köp 10 à 110 den 2:a: 110 → 99 → 120 på 10 andelar = +100 kr, inte 10 × (120 − 100).
        change = fm.value_change([tx("2026-01-02", "BUY", 10, 110.0)], self.P, "A", date(2026, 1, 1), date(2026, 1, 4))
        self.assertAlmostEqual(change, 100.0, places=12)

    def test_sell_on_a_day_keeps_the_move_up_to_that_day(self):
        # 10 andelar sedan före perioden, 4 säljs den 3:e: 10 × (110 − 100) + 10 × (99 − 110) + 6 × (120 − 99).
        trades = [tx("2025-12-01", "BUY", 10, 90.0), tx("2026-01-03", "SELL", 4, 99.0)]
        change = fm.value_change(trades, self.P, "A", date(2026, 1, 1), date(2026, 1, 4))
        self.assertAlmostEqual(change, 100.0 - 110.0 + 126.0, places=12)

    def test_trade_on_a_day_without_price_is_traded_at_the_next_price(self):
        # Kurs bara fredag den 2:a (100) och måndag den 5:e (120): ett köp på lördagen till
        # måndagens kurs får ingen rörelse, ett sälj på lördagen behåller rörelsen till måndagen.
        gap = prices(A=[("2026-01-02", 100.0), ("2026-01-05", 120.0)])
        buy = fm.value_change([tx("2026-01-03", "BUY", 10, 120.0)], gap, "A", date(2026, 1, 2), date(2026, 1, 5))
        self.assertAlmostEqual(buy, 0.0, places=12)
        sell = [tx("2025-12-01", "BUY", 10, 90.0), tx("2026-01-03", "SELL", 10, 120.0)]
        self.assertAlmostEqual(fm.value_change(sell, gap, "A", date(2026, 1, 2), date(2026, 1, 5)), 200.0, places=12)

    def test_nothing_held_is_zero_and_no_start_price_is_none(self):
        self.assertEqual(fm.value_change([tx("2026-02-01", "BUY", 10, 120.0)], self.P, "A", date(2026, 1, 1), date(2026, 1, 4)), 0.0)
        self.assertIsNone(fm.value_change([tx("2025-12-01", "BUY", 10, 90.0)], self.P, "A", date(2025, 12, 31), date(2026, 1, 4)))

    def test_value_change_is_end_value_minus_net_purchases_when_starting_empty(self):
        # Oberoende kontroll: från ett tomt innehav är värdeförändringen slutvärdet minus nettoköpen
        # (andelar × kurs, avgifter utanför) när kursen på transaktionsdagen är transaktionens kurs.
        trades = [tx("2026-01-02", "BUY", 10, 110.0, fee=5.0), tx("2026-01-03", "SELL", 4, 99.0, fee=5.0)]
        change = fm.value_change(trades, self.P, "A", date(2026, 1, 1), date(2026, 1, 4))
        self.assertAlmostEqual(change, 6 * 120.0 - (10 * 110.0 - 4 * 99.0), places=12)

    def test_minus_months_clips_to_month_end_like_java_time(self):
        self.assertEqual(fm.minus_months(date(2026, 5, 31), 3), date(2026, 2, 28))
        self.assertEqual(fm.minus_months(date(2026, 1, 15), 1), date(2025, 12, 15))
        self.assertEqual(fm.minus_months(date(2026, 10, 16), 36), date(2023, 10, 16))

    def test_periodkop_fixture_matches_hand_calculation(self):
        # periodkop.json: köp 100 à 101,5 (1/9), köp 50 à 106 + 10 kr (1/10), sälj 30 à 105,3 − 10 kr (9/10).
        with open(Path(__file__).with_name("periodkop.json"), encoding="utf-8") as f:
            rows = fm.golden(json.load(f))["funds"]["A"]["periods"]
        self.assertAlmostEqual(rows["day"]["amount"], 120 * (110.2 - 108.9), places=9)
        self.assertAlmostEqual(rows["week"]["amount"], 120 * (110.2 - 105.3), places=9)
        self.assertAlmostEqual(rows["month"]["amount"], 100 * (106 - 102.25) + 150 * (105.3 - 106) + 120 * (110.2 - 105.3), places=9)
        # 3 mån börjar utan innehav: slutvärde 13 224 minus nettoköp 10 150 + 5 300 − 3 159.
        self.assertAlmostEqual(rows["3m"]["amount"], 13224 - (10150 + 5300 - 3159), places=9)
        self.assertAlmostEqual(rows["3m"]["fraction"], 110.2 / 98.0 - 1, places=12)
        # Sedan köp: 120 × 110,2 − 120/150 × (10 150 + 5 310) = 856 kr (genomsnittsmetoden, köpavgift inräknad).
        self.assertAlmostEqual(rows["since_purchase"]["amount"], 856.0, places=9)
        self.assertAlmostEqual(rows["since_purchase"]["fraction"], 110.2 / 101.5 - 1, places=12)
        for period in ("ytd", "1y", "3y"):
            self.assertEqual(rows[period], {"amount": None, "fraction": None})


class MovingAverageTest(unittest.TestCase):
    def test_window_is_290_calendar_days_regardless_of_gaps(self):
        # Bara vardagar har kurs. Första snittet kommer när serien nått 290 dagar bakåt.
        day = date(2025, 1, 1)
        points = []
        while len(points) < 400:
            if day.weekday() < 5:
                points.append((day, float(len(points))))
            day += timedelta(1)
        result = fm.sma_200(points)
        first = date.fromisoformat(result["first_date"])
        self.assertGreaterEqual((first - points[0][0]).days, 290)
        self.assertLess((first - points[0][0]).days, 293)
        expected = [v for d, v in points if first - timedelta(290) <= d <= first]
        self.assertAlmostEqual(result["first"], sum(expected) / len(expected), places=12)
        self.assertGreater(len(expected), 200)  # ~207 vardagar, inte 200 punkter

    def test_sparse_weekly_points_average_points_within_window(self):
        points = [(date(2025, 1, 1) + timedelta(7 * k), 100.0 + 7 * k) for k in range(101)]
        result = fm.sma_200(points)
        # Dag 294 (k = 42): punkterna k = 1…42 ligger i [4, 294] → NAV 107 … 394, medel 250,5.
        row = [r for r in result["series"] if r[0] == (date(2025, 1, 1) + timedelta(294)).isoformat()][0]
        self.assertAlmostEqual(row[1], 250.5, places=12)
        self.assertEqual(result["first_date"], (date(2025, 1, 1) + timedelta(294)).isoformat())

    def test_too_short_history_gives_none(self):
        points = [(date(2025, 1, 1) + timedelta(n), 100.0) for n in range(289)]
        result = fm.sma_200(points)
        self.assertEqual(result["count"], 0)
        self.assertIsNone(result["latest"])
        self.assertEqual(result["series"], [])

    def test_golden_exposes_sma_per_fund(self):
        with open(Path(__file__).with_name("sma.json"), encoding="utf-8") as f:
            sma = fm.golden(json.load(f))["funds"]["A"]["sma_200"]
        self.assertEqual(sma["count"], len(sma["series"]))
        self.assertGreater(sma["count"], 0)


class FacitTest(unittest.TestCase):
    """Facit för bytesförslag (SET-5, issue #197): samma dag för båda fonderna, horisontband, median och årstakt."""

    def suggestion(self, **overrides):
        base = {"id": 1, "suggested": "2025-01-02", "nav_date": "2025-01-02", "sell": "S", "buy": "K", "sell_nav": 100.0, "buy_nav": 50.0, "value_kr": 10_000.0}
        return base | overrides

    def test_measures_on_latest_day_both_funds_have_a_price(self):
        # Säljfonden har en kurs den 10:e, köpfonden inte: mätdagen blir den 9:e för båda.
        p = prices(S=[("2025-01-02", 100.0), ("2025-01-09", 104.0), ("2025-01-10", 110.0)], K=[("2025-01-02", 50.0), ("2025-01-09", 51.0)])
        row = fm.facit_row(self.suggestion(), p)
        self.assertEqual(row["measured"], "2025-01-09")
        self.assertEqual(row["days"], 7)
        self.assertAlmostEqual(row["excess"], 0.02 - 0.04, places=12)
        self.assertAlmostEqual(row["excess_kr"], -200.0, places=9)

    def test_no_common_day_after_the_suggestion_is_not_evaluated(self):
        # Bara förslagsdagen är gemensam: noll dagar är inget utfall, och "inte bättre" vore fel.
        p = prices(S=[("2025-01-02", 100.0), ("2025-01-03", 101.0)], K=[("2025-01-02", 50.0), ("2025-01-06", 52.0)])
        row = fm.facit_row(self.suggestion(), p)
        self.assertIsNone(row["excess"])
        self.assertIsNone(row["measured"])

    def test_row_without_nav_date_is_measured_from_the_suggestion_day(self):
        p = prices(S=[("2025-01-02", 100.0), ("2025-02-01", 100.0)], K=[("2025-01-02", 50.0), ("2025-02-01", 55.0)])
        row = fm.facit_row(self.suggestion(nav_date=None), p)
        self.assertEqual(row["days"], 30)
        self.assertEqual(row["horizon"], "ONE_TO_SIX_MONTHS")

    def test_horizon_bands(self):
        self.assertEqual(fm.facit_horizon(29), "UNDER_ONE_MONTH")
        self.assertEqual(fm.facit_horizon(30), "ONE_TO_SIX_MONTHS")
        self.assertEqual(fm.facit_horizon(182), "ONE_TO_SIX_MONTHS")
        self.assertEqual(fm.facit_horizon(183), "OVER_SIX_MONTHS")

    def test_annualized_excess(self):
        # +21 % över två år (730,485 dagar) är +10 % per år; ett helt år är oförändrat.
        self.assertAlmostEqual(fm.facit_annualized(0.21, 730.485), 0.10, places=12)
        self.assertAlmostEqual(fm.facit_annualized(0.12, 365.2425), 0.12, places=12)
        self.assertEqual(fm.facit_annualized(-1.5, 400), -1.0)
        self.assertIsNone(fm.facit_annualized(0.1, 0))
        # Samma formel som GIPS-årstakten, bara utan ettårsgränsen.
        self.assertEqual(fm.facit_annualized(0.21, 730), fm.annualized(0.21, 730))

    def test_annualized_is_on_relative_return_not_excess(self):
        # Säljfonden +50 %, köpfonden +65 %: meravkastningen +15 pp men den relativa utvecklingen +10 %.
        p = prices(S=[("2025-01-02", 100.0), ("2027-01-01", 150.0)], K=[("2025-01-02", 50.0), ("2027-01-01", 82.5)])
        row = fm.facit_row(self.suggestion(), p)
        self.assertAlmostEqual(row["excess"], 0.15, places=12)
        self.assertAlmostEqual(row["relative"], 0.10, places=12)
        self.assertAlmostEqual(row["annualized"], 1.10 ** (fm.DAYS_PER_YEAR / row["days"]) - 1.0, places=12)

    def test_median(self):
        self.assertEqual(fm.median([3.0, 1.0, 2.0]), 2.0)
        self.assertEqual(fm.median([4.0, 1.0, 2.0, 3.0]), 2.5)
        self.assertIsNone(fm.median([]))

    def test_issue_example_hit_rate_instead_of_average(self):
        # Issue #197: A (300 dagar, +12 pp), B och C (3 dagar, −1 pp). Snittet +3,3 pp dolde att två av tre var sämre.
        rows = [
            {"excess": 0.12, "excess_kr": 1200.0, "better": True, "horizon": "OVER_SIX_MONTHS", "annualized": 0.147},
            {"excess": -0.01, "excess_kr": -50.0, "better": False, "horizon": "UNDER_ONE_MONTH", "annualized": None},
            {"excess": -0.01, "excess_kr": -80.0, "better": False, "horizon": "UNDER_ONE_MONTH", "annualized": None},
        ]
        summary = fm.facit_summary(rows)
        self.assertEqual((summary["better"], summary["evaluated"]), (1, 3))
        self.assertAlmostEqual(summary["median_excess"], -0.01, places=12)
        self.assertAlmostEqual(summary["total_excess_kr"], 1070.0, places=9)
        bands = fm.facit_by_horizon(rows)
        self.assertEqual([b["horizon"] for b in bands], ["UNDER_ONE_MONTH", "OVER_SIX_MONTHS"])
        self.assertAlmostEqual(bands[1]["median"], 0.147, places=12)

    def test_facit_fixture(self):
        with open(Path(__file__).with_name("facit.json"), encoding="utf-8") as f:
            result = fm.golden(json.load(f))
        json.dumps(result, allow_nan=False)
        facit = result["facit"]
        by_id = {r["id"]: r for r in facit["rows"]}
        self.assertEqual(by_id[1]["measured"], "2025-12-26")  # köpfonden saknar kurs den 29:e
        self.assertIsNone(by_id[5]["excess"])
        self.assertEqual((facit["all"]["better"], facit["all"]["evaluated"], facit["all"]["total"]), (3, 6, 7))
        self.assertAlmostEqual(facit["all"]["total_excess_kr"], 1200.0 - 50.0 - 80.0 - 4000.0 + 120.0, places=6)
        self.assertEqual([b["count"] for b in facit["by_horizon"]], [2, 2, 2])
        self.assertAlmostEqual(by_id[1]["relative"], 1.17 / 1.05 - 1.0, places=12)
        self.assertAlmostEqual(facit["by_horizon"][2]["median"], -0.0019107436090513574, places=12)


class ExampleFixtureTest(unittest.TestCase):
    def test_example_runs_and_methods_differ(self):
        with open(Path(__file__).with_name("exempel.json"), encoding="utf-8") as f:
            result = fm.golden(json.load(f))
        json.dumps(result, allow_nan=False)
        self.assertFalse(result["portfolio"]["twr_partial"])
        average, fifo = result["realized_average_cost"][1], result["realized_fifo"][1]
        self.assertNotAlmostEqual(average["gain"], fifo["gain"], places=0)
        portfolio = result["portfolio"]
        self.assertLess(portfolio["total_gain"], portfolio["unrealized_average_cost_as_app"])
        self.assertNotAlmostEqual(portfolio["unrealized_average_cost_as_app"], portfolio["unrealized_fifo"], places=0)


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
