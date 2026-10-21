"""Referensberäkningar för Fonders fondmatematik (skill `fondmatematik`).

Räknar fram förväntade värden ("golden values") som checkas in i appens JUnit-tester, med
oberoende implementeringar: XIRR via pyxirr, volatilitet/Sharpe/Sortino/drawdown via
empyrical-reloaded, och tidsviktad avkastning, genomsnittsmetoden, FIFO och ISK-skatt
skrivna här efter definitionerna i skillen. Körs bara i utvecklingen, aldrig i appen eller CI.

    python tools/fondmatte/fondmatte.py golden tools/fondmatte/exempel.json

Konventionerna är appens (FundAnalysisCalc): 252 handelsdagar per år, urvalsstandardavvikelse
(n−1), riskfri ränta 0 %, volatilitet och Sharpe i ett fast fönster bakåt från senaste kurs
(3 år, annars 1 år, annars inget) på kurspar högst 5 dagar isär, där fönstret väljs bara om minst
60 % av ≈ 252 × år handelsdagar finns som sådana dagsavkastningar, Sharpe-täljaren som fönstrets
totala kursutveckling i årstakt (365,2425 dagar), ingen årstakt under 365 dagar och 365,2425 dagar per år. XIRR räknas som Excel (ACT/365F).
Testdata är alltid påhittad – aldrig riktiga innehav (skill `data-privacy-security`).
"""

from __future__ import annotations

import argparse
import bisect
import json
import math
import sys
from dataclasses import dataclass
from datetime import date, timedelta

TRADING_DAYS_PER_YEAR = 252
DAYS_PER_YEAR = 365.2425
XIRR_DAYS_PER_YEAR = 365  # pyxirr DayCount.ACT_365F, samma som Excels XIRR
ANNUALIZE_MIN_DAYS = 365
MIN_WINDOW_COVERAGE = 0.6  # FundAnalysisCalc.MIN_WINDOW_COVERAGE: andel av 252 × år
STALE_MARGIN_DAYS = 4  # FundAnalysisCalc.STALE_MARGIN_DAYS: längsta tillåtna ålder för slutkursen mot förväntad senaste NAV-dag
MAX_NAV_GAP_DAYS = 5  # NavCalendar.MAX_NAV_GAP_DAYS: helg + helgdag, t.ex. påsk – periodstart, dagsavkastning och ankare
MAX_WINDOW_GAP_DAYS = 31  # FundAnalysisCalc.MAX_WINDOW_GAP_DAYS: största glapp i riskfönstret
RISK_WINDOW_YEARS = (3, 1)  # FundAnalysisCalc.RISK_WINDOW_YEARS, längsta först
EPSILON = 1e-9

# Statslåneräntan den 30 november året före och skattefri nivå per person (ISK + KF + PEPP
# sammanlagt). Skatteverket: investeringssparkonto och "Belopp och procent".
ISK_PARAMETERS = {
    2025: {"government_bond_rate": 0.0196, "tax_free_amount": 150_000},
    2026: {"government_bond_rate": 0.0255, "tax_free_amount": 300_000},
}
ISK_RATE_FLOOR = 0.0125
CAPITAL_INCOME_TAX = 0.30


@dataclass(frozen=True)
class Transaction:
    """Samma fält som appens `Transaction`: beloppet är alltid andelar × kurs."""

    day: date
    fund: str
    kind: str  # "BUY" eller "SELL"
    units: float
    price: float  # kurs per andel vid transaktionen (appens pricePerShare)
    fee: float = 0.0

    @property
    def amount(self) -> float:
        return self.units * self.price


@dataclass(frozen=True)
class Sale:
    day: date
    fund: str
    units: float
    proceeds: float  # andelar × kurs, före avgift (appens RealizedSale.proceeds)
    fee: float
    cost_basis: float  # bara för andelar som matchats mot kända köp
    uncovered_units: float  # sålda andelar utan känt köp – resultatet är då överskattat

    @property
    def gain(self) -> float:
        return self.proceeds - self.fee - self.cost_basis


class Prices:
    """Kurser per fond med uppslag "senast kända NAV på eller före dagen" i O(log n)."""

    def __init__(self, raw: dict[str, list[tuple[date, float]]]):
        # Sortera bara på datum (stabilt), som appens sortedBy { epochDay }: vid två kurser samma
        # dag gäller den sista i indata.
        self.series = {fund: sorted(points, key=lambda p: p[0]) for fund, points in raw.items()}
        self._days = {fund: [d for d, _ in points] for fund, points in self.series.items()}

    def nav(self, fund: str, day: date) -> float | None:
        days = self._days.get(fund)
        if not days:
            return None
        i = bisect.bisect_right(days, day)
        return self.series[fund][i - 1][1] if i else None

    def days(self) -> set[date]:
        return {d for days in self._days.values() for d in days}


def parse_transactions(raw: list[dict]) -> list[Transaction]:
    txs = []
    for t in raw:
        if t["kind"] not in ("BUY", "SELL"):
            raise ValueError(f"okänd transaktionstyp: {t['kind']}")
        txs.append(
            Transaction(
                day=date.fromisoformat(t["date"]),
                fund=t["fund"],
                kind=t["kind"],
                units=float(t["units"]),
                price=float(t["price"]),
                fee=float(t.get("fee", 0.0)),
            )
        )
    if not txs:
        raise ValueError("fixturen har inga transaktioner")
    # Stabil sortering: transaktioner samma dag behåller fixturens ordning.
    return sorted(txs, key=lambda t: t.day)


def parse_prices(raw: dict[str, list]) -> Prices:
    return Prices({fund: [(date.fromisoformat(d), float(v)) for d, v in points] for fund, points in raw.items()})


def apply_transaction(units: dict[str, float], t: Transaction) -> None:
    """Uppdaterar innehavet som appens remainingPositions: ett sälj kan inte ta innehavet under
    noll – andelar utöver innehavet saknar känt köp (`uncovered_units`) och ignoreras här."""
    held = units.get(t.fund, 0.0) + (t.units if t.kind == "BUY" else -t.units)
    if held > EPSILON:
        units[t.fund] = held
    else:
        units.pop(t.fund, None)


def units_held(transactions: list[Transaction], day: date) -> dict[str, float]:
    units: dict[str, float] = {}
    for t in transactions:
        if t.day <= day:
            apply_transaction(units, t)
    return units


# --- Avkastning -----------------------------------------------------------------------------


def daily_returns(points: list[tuple[date, float]], max_gap_days: int | None = None) -> list[float]:
    """Enkla avkastningar mellan på varandra följande kurspunkter. Med `max_gap_days` räknas bara
    par högst så många kalenderdagar isär (FundAnalysisCalc: längre luckor hoppas över)."""
    navs = [(d, v) for d, v in points if v > 0.0]
    return [b / a - 1.0 for (da, a), (db, b) in zip(navs, navs[1:]) if max_gap_days is None or (db - da).days <= max_gap_days]


def min_window_returns(years: int) -> int:
    """Minsta antal giltiga dagsavkastningar för ett fönster på `years` år (FundAnalysisCalc.minWindowReturns)."""
    return math.ceil(MIN_WINDOW_COVERAGE * TRADING_DAYS_PER_YEAR * years)


def risk_window(points: list[tuple[date, float]]) -> tuple[int, list[tuple[date, float]]] | None:
    """Riskmåttens fönster som i FundAnalysisCalc: 3 år bakåt från senaste kurs om fönstret är
    giltigt, annars 1 år på samma villkor, annars None. Fönstret börjar i ankaret, den senaste
    kursen på eller före startdagen. Giltigt: ankaret högst MAX_NAV_GAP_DAYS före startdagen,
    inget glapp mellan på varandra följande punkter över MAX_WINDOW_GAP_DAYS och minst
    `min_window_returns(år)` dagsavkastningar högst MAX_NAV_GAP_DAYS isär. Punkterna sorteras
    stabilt på datum först (som appens sortedBy { epochDay }), så att osorterad indata ger samma
    fönster."""
    navs = sorted(((d, v) for d, v in points if v > 0.0), key=lambda p: p[0])
    if not navs:
        return None
    latest = navs[-1][0]
    for years in RISK_WINDOW_YEARS:
        start = minus_months(latest, 12 * years)
        anchor = max((i for i, (d, _) in enumerate(navs) if d <= start), default=None)
        if anchor is None or (start - navs[anchor][0]).days > MAX_NAV_GAP_DAYS:
            continue
        window = navs[anchor:]
        if any((b[0] - a[0]).days > MAX_WINDOW_GAP_DAYS for a, b in zip(window, window[1:])):
            continue
        if len(daily_returns(window, MAX_NAV_GAP_DAYS)) >= min_window_returns(years):
            return years, window
    return None


def annualized(total_return: float, days: int, days_per_year: float = DAYS_PER_YEAR, min_days: int = ANNUALIZE_MIN_DAYS) -> float | None:
    """Årstakt bara från ett år (GIPS); kortare perioder visas som total avkastning.

    `min_days` sänks bara av facit, som räknar årstakt redan över sex månader (issue #197).
    """
    if days < max(min_days, 1):
        return None
    return (1.0 + total_return) ** (days_per_year / days) - 1.0


def time_weighted_return(transactions: list[Transaction], prices: Prices, end: date) -> tuple[float, bool]:
    """Kedjad tidsviktad avkastning med gårdagens andelar, som PortfolioReturnSeriesCalc.

    Dagens faktor värderar andelarna som ägdes före dagens transaktioner till gårdagens och
    dagens NAV, så in- och utflöden faller ur måttet. Ett innehav utan känd NAV någon av
    dagarna utesluts ur dagens faktor och resultatet markeras partiellt (HEM-2); utan
    värderbar portfölj i går blir faktorn 1,0.
    """
    if not transactions:
        return 0.0, False
    start = transactions[0].day
    timeline = sorted({d for d in prices.days() if start <= d <= end} | {t.day for t in transactions if t.day <= end})
    by_day: dict[date, list[Transaction]] = {}
    for t in transactions:
        by_day.setdefault(t.day, []).append(t)

    units: dict[str, float] = {}
    index, partial, previous_day = 1.0, False, None
    for day in timeline:
        if previous_day is not None:
            before = after = 0.0
            for fund, u in units.items():
                nav_before, nav_now = prices.nav(fund, previous_day), prices.nav(fund, day)
                if nav_before is None or nav_now is None:
                    partial = True
                    continue
                before += u * nav_before
                after += u * nav_now
            if before > 0.0:
                index *= after / before
        for t in by_day.get(day, []):
            apply_transaction(units, t)
        previous_day = day
    return index - 1.0, partial


def market_value(transactions: list[Transaction], prices: Prices, day: date) -> tuple[float, bool]:
    """Värdet på dagen till senast kända NAV; innehav utan kurs utesluts och markeras."""
    value, partial = 0.0, False
    for fund, u in units_held(transactions, day).items():
        nav = prices.nav(fund, day)
        if nav is None:
            partial = True
        else:
            value += u * nav
    return value, partial


def value_change(transactions: list[Transaction], prices: Prices, fund: str, start: date, end: date) -> float | None:
    """Värdeförändringen i kr på de andelar som faktiskt ägts, dag för dag, som appens
    PositionValueChangeCalc (issue #193): från kurspunkten på eller före `start` till den sista
    på eller före `end` räknas `andelar × (NAV_i − NAV_{i−1})` på andelarna som ägdes vid
    förra kurspunkten – ett köp på en kursdag får rörelsen först från nästa punkt, ett sälj har den
    med, och en transaktion på en dag utan kurs räknas som handlad till nästa kurs. None utan kurs på eller före `start`."""
    series = [(d, v) for d, v in prices.series.get(fund, []) if d <= end]
    start_index = max((i for i, (d, _) in enumerate(series) if d <= start), default=None)
    if start_index is None:
        return None
    own = [t for t in transactions if t.fund == fund]
    units: dict[str, float] = {}
    next_tx, previous, change = 0, series[start_index][1], 0.0
    previous_day = series[start_index][0]
    for day, nav in series[start_index + 1 :]:
        # Andelarna vid förra kurspunkten; en transaktion på en dag utan kurs handlas till nästa kurs.
        while next_tx < len(own) and own[next_tx].day <= previous_day:
            apply_transaction(units, own[next_tx])
            next_tx += 1
        change += units.get(fund, 0.0) * (nav - previous)
        previous, previous_day = nav, day
    return change


def minus_months(day: date, months: int) -> date:
    """Som java.time LocalDate.minusMonths: samma dag i månaden, klippt till månadens sista dag."""
    month_index = day.year * 12 + day.month - 1 - months
    year, month = divmod(month_index, 12)
    month += 1
    last = (date(year + (month == 12), month % 12 + 1, 1) - timedelta(days=1)).day
    return date(year, month, min(day.day, last))


def is_stale_end(end_day: date, today: date) -> bool:
    """Slutkursen är inaktuell när den är äldre än senaste förväntade NAV-dagen (NavCalendar.expectedLatestNavDay
    före kl. 18: föregående vardag) minus STALE_MARGIN_DAYS (helgdagar, t.ex. jul och påsk)."""
    expected = today - timedelta(days=1)
    while expected.weekday() >= 5:
        expected -= timedelta(days=1)
    return (expected - end_day).days > STALE_MARGIN_DAYS


def period_rows(transactions: list[Transaction], prices: Prices, fund: str, end: date) -> dict:
    """Periodraderna för ett innehav som appen visar dem (POR-5, ANA-1): `fraction` är fondens
    kursutveckling NAV mot NAV, `amount` värdeförändringen på ägda andelar (`value_change`), och
    för `since_purchase` positionens vinst mot anskaffningsvärdet med genomsnittsmetoden. Dag,
    vecka och månad ankras i senaste NAV-dag på eller före `end` och kräver en startkurs högst
    fem dagar före måldagen (PortfolioPerformanceCalc); i år/3 mån/1 år/3 år/sedan köp gäller samma
    gräns för startkursen (FundAnalysisCalc, issue #198) och slutkursen är senaste NAV på eller före
    `end`. En rad med data har `start_day`, `end_day` och `end_is_stale` (slutkursen äldre än
    förväntad senaste NAV-dag minus STALE_MARGIN_DAYS, `is_stale_end`); `since_purchase` har också `cagr` mellan de två NAV-dagarna
    (årstakt från ett år). None = otillräcklig historik."""
    own = [t for t in transactions if t.fund == fund]
    series = [(d, v) for d, v in prices.series.get(fund, []) if d <= end]
    if not series:
        return {}
    reference_day, latest_nav = series[-1]
    units = units_held(own, end).get(fund, 0.0)

    def row(start: date, max_gap_days: int | None = None, amount: float | None = None) -> dict:
        start_point = next(((d, v) for d, v in reversed(series) if d <= start), None)
        if start_point is None or start_point[1] <= 0.0 or (max_gap_days is not None and (start - start_point[0]).days > max_gap_days):
            # "Sedan köp" i kr är vinsten mot anskaffningsvärdet och finns utan startkurs, som i appen.
            return {"amount": amount, "fraction": None}
        if amount is None:
            amount = value_change(transactions, prices, fund, start_point[0], reference_day)
        return {
            "amount": amount,
            "fraction": latest_nav / start_point[1] - 1.0,
            "start_day": start_point[0].isoformat(),
            "end_day": reference_day.isoformat(),
            "end_is_stale": is_stale_end(reference_day, end),
        }

    # Positionens start: första köpet efter att innehavet senast var noll (POR-6).
    held: dict[str, float] = {}
    position_start = None
    for t in own:
        if t.kind == "BUY" and held.get(fund, 0.0) <= EPSILON:
            position_start = t.day
        apply_transaction(held, t)
    rows = {
        "day": row(reference_day - timedelta(days=1), max_gap_days=MAX_NAV_GAP_DAYS),
        "week": row(reference_day - timedelta(days=7), max_gap_days=MAX_NAV_GAP_DAYS),
        "month": row(reference_day - timedelta(days=30), max_gap_days=MAX_NAV_GAP_DAYS),
        "ytd": row(date(end.year, 1, 1), max_gap_days=MAX_NAV_GAP_DAYS),
        "3m": row(minus_months(end, 3), max_gap_days=MAX_NAV_GAP_DAYS),
        "1y": row(minus_months(end, 12), max_gap_days=MAX_NAV_GAP_DAYS),
        "3y": row(minus_months(end, 36), max_gap_days=MAX_NAV_GAP_DAYS),
    }
    if position_start is not None and units > EPSILON:
        gain = units * latest_nav - _average_cost(transactions)[1].get(fund, 0.0)
        rows["since_purchase"] = row(position_start, max_gap_days=MAX_NAV_GAP_DAYS, amount=gain)
        sp = rows["since_purchase"]
        if sp.get("fraction") is not None:
            sp["cagr"] = annualized(sp["fraction"], (reference_day - date.fromisoformat(sp["start_day"])).days)
    return rows


def money_weighted_return(transactions: list[Transaction], prices: Prices, end: date, include_fees: bool = True) -> tuple[float | None, bool]:
    """XIRR (ACT/365F) ur användarens kassaflöden: köp inklusive avgift ut, sälj minus avgift
    in, slutvärdet in. None när ekvationen saknar lösning; partiell när slutvärdet saknar ett
    innehav utan kurs. Utan avgifter (`include_fees=False`) är den jämförbar med TWR, som inte
    heller ser courtage."""
    from pyxirr import DayCount, xirr

    dates, amounts = [], []
    for t in transactions:
        if t.day > end:
            continue
        dates.append(t.day)
        fee = t.fee if include_fees else 0.0
        amounts.append(-(t.amount + fee) if t.kind == "BUY" else t.amount - fee)
    value, partial = market_value(transactions, prices, end)
    dates.append(end)
    amounts.append(value)
    return xirr(dates, amounts, day_count=DayCount.ACT_365F, silent=True), partial


# --- Glidande medelvärde ---------------------------------------------------------------------

SMA_WINDOW_DAYS = 290  # MovingAverageCalc.WINDOW_DAYS: kalenderdagar ≈ 200 handelsdagar


def sma_200(points: list[tuple[date, float]], window_days: int = SMA_WINDOW_DAYS) -> dict:
    """"200-dagarssnittet" (200 handelsdagar) som kalenderfönster, som MovingAverageCalc: för en
    kurspunkt d är snittet medel av kurserna i [d − window_days, d], men bara om serien börjar på
    eller före d − window_days (annars None, ANA-4). Kalenderfönster eftersom kurscachen kan vara
    gles. `series` är [[datum, medel], …]; `count` 0 och `latest` None när historiken inte räcker."""
    series = []
    for d, _ in points:
        start = d - timedelta(window_days)
        if points[0][0] > start:
            continue
        inside = [v for day, v in points if start <= day <= d]
        series.append((d, sum(inside) / len(inside)))
    return {
        "window_days": window_days,
        "count": len(series),
        "first_date": series[0][0].isoformat() if series else None,
        "first": series[0][1] if series else None,
        "latest": series[-1][1] if series else None,
        "series": [[d.isoformat(), v] for d, v in series],
    }


# --- Risk -----------------------------------------------------------------------------------


def risk_figures(points: list[tuple[date, float]]) -> dict:
    """Nyckeltal för en kursserie. Volatilitet, Sharpe och Sortino räknas som i FundAnalysisCalc:
    i fönstret från `risk_window` (`risk_window_years`), bara på kurspar högst MAX_NAV_GAP_DAYS
    isär (`samples` = antalet sådana avkastningar), och är None utan fönster (för kort eller för
    glest, se `risk_window`) och när de inte är ändliga (t.ex. nollvolatilitet). Sharpe-täljaren är
    fönstrets totala kursutveckling i årstakt, så att en rörelse över en överhoppad lucka inte
    tappas. Totalavkastning, CAGR och max drawdown gäller hela serien, första till sista punkt."""
    import empyrical
    import numpy as np

    navs = sorted(((d, v) for d, v in points if v > 0.0), key=lambda p: p[0])
    all_returns = np.array(daily_returns(navs))
    window = risk_window(navs)
    returns = np.array(daily_returns(window[1], MAX_NAV_GAP_DAYS)) if window else np.array([])
    out: dict = {"samples": int(returns.size)}
    if all_returns.size == 0:
        return out
    total = navs[-1][1] / navs[0][1] - 1.0
    out["total_return"] = total
    out["series_cagr"] = annualized(total, (navs[-1][0] - navs[0][0]).days)
    out["max_drawdown"] = float(empyrical.max_drawdown(all_returns))
    # empyrical: std med ddof=1 × √252, som FundAnalysisCalc.annualizedVolatility.
    # Nollvolatilitet är 0,0 som i appen; Sharpe och Sortino är då meningslösa och blir None.
    vol = float(empyrical.annual_volatility(returns, period="daily")) if window else None
    out["annualized_volatility"] = vol
    out["risk_window_years"] = window[0] if window else None
    has_risk = vol is not None and math.isfinite(vol) and vol > 0.0
    # Sharpe som i appen: fönstrets totala kursutveckling i årstakt (365,2425 dagar, som CAGR) /
    # volatiliteten, riskfri ränta 0. Inte empyrical.sharpe_ratio (medel × 252), som tappar
    # rörelsen över luckor längre än MAX_NAV_GAP_DAYS.
    if has_risk:
        (d0, v0), (d1, v1) = window[1][0], window[1][-1]
        window_return = (v1 / v0) ** (DAYS_PER_YEAR / (d1 - d0).days) - 1.0
        out["window_annualized_return"] = window_return
        out["sharpe"] = window_return / vol
    else:
        out["window_annualized_return"] = None
        out["sharpe"] = None
    # Nedsidesrisk över alla perioder: √(medel(min(r, 0)²)) × √252; oändlig utan negativa dagar.
    out["sortino"] = float(empyrical.sortino_ratio(returns, required_return=0.0, period="daily")) if has_risk else None
    return out


# --- Anskaffningsvärde ----------------------------------------------------------------------


def average_cost_sales(transactions: list[Transaction]) -> list[Sale]:
    """Genomsnittsmetoden (Skatteverket): köpavgiften ingår i anskaffningsvärdet, ett sälj
    ändrar inte snittet per andel, säljavgiften dras från försäljningspriset. Andelar som
    säljs utöver innehavet räknas som `uncovered_units`, som i appen."""
    return _average_cost(transactions)[0]


def average_cost_remaining(transactions: list[Transaction]) -> float:
    """Kvarvarande anskaffningsvärde med genomsnittsmetoden, som appens Holding.netInvested
    (sedan issue #192): summan av vad som är kvar av varje fonds anskaffningsvärde."""
    return sum(_average_cost(transactions)[1].values())


def _average_cost(transactions: list[Transaction]) -> tuple[list[Sale], dict[str, float]]:
    """En genomgång som ger både säljen och det kvarvarande anskaffningsvärdet per fond."""
    units: dict[str, float] = {}
    cost: dict[str, float] = {}
    sales = []
    for t in transactions:
        held = units.get(t.fund, 0.0)
        if t.kind == "BUY":
            units[t.fund] = held + t.units
            cost[t.fund] = cost.get(t.fund, 0.0) + t.amount + t.fee
            continue
        covered = min(t.units, held)
        sold_cost = cost[t.fund] * covered / held if held > EPSILON else 0.0
        sales.append(Sale(t.day, t.fund, t.units, t.amount, t.fee, sold_cost, t.units - covered))
        units[t.fund] = held - covered
        cost[t.fund] = cost.get(t.fund, 0.0) - sold_cost
        if units[t.fund] <= EPSILON:
            units[t.fund], cost[t.fund] = 0.0, 0.0
    return sales, cost


def fifo_sales(transactions: list[Transaction]) -> list[Sale]:
    """FIFO som appens RealizedGainCalculator räknade före issue #192: äldsta lott säljs först,
    lottens kostnad är andelar × kurs (köpavgiften räknas inte in), säljavgiften dras från
    resultatet. Kvar som referens för att visa skillnaden mot genomsnittsmetoden."""
    return _fifo(transactions)[0]


def fifo_remaining_cost(transactions: list[Transaction]) -> float:
    """Kvarvarande lotters anskaffningsvärde med FIFO (referens, appens regel före issue #192)."""
    return sum(units * price for queue in _fifo(transactions)[1].values() for units, price in queue)


def _fifo(transactions: list[Transaction]) -> tuple[list[Sale], dict[str, list[list[float]]]]:
    lots: dict[str, list[list[float]]] = {}
    sales = []
    for t in transactions:
        if t.kind == "BUY":
            lots.setdefault(t.fund, []).append([t.units, t.price])
            continue
        remaining, sold_cost = t.units, 0.0
        queue = lots.get(t.fund, [])
        while remaining > EPSILON and queue:
            take = min(remaining, queue[0][0])
            sold_cost += take * queue[0][1]
            queue[0][0] -= take
            remaining -= take
            if queue[0][0] <= EPSILON:
                queue.pop(0)
        sales.append(Sale(t.day, t.fund, t.units, t.amount, t.fee, sold_cost, max(remaining, 0.0)))
    return sales, lots


def net_deposits(transactions: list[Transaction]) -> float:
    """Insatt netto: köp med avgift minus sälj efter avgift."""
    return sum(t.amount + t.fee if t.kind == "BUY" else -(t.amount - t.fee) for t in transactions)


# --- Skatt ----------------------------------------------------------------------------------


def isk_tax(year: int, quarter_start_values: list[float], deposits: float) -> float:
    """Schablonskatt på ISK/KF för hela personens konton: kapitalunderlaget är kvartalens
    ingående värden plus årets insättningar delat med fyra, minus den skattefria nivån."""
    if len(quarter_start_values) != 4:
        raise ValueError("fyra kvartalsvärden krävs")
    p = ISK_PARAMETERS[year]
    base = max(0.0, (sum(quarter_start_values) + deposits) / 4 - p["tax_free_amount"])
    rate = max(ISK_RATE_FLOOR, p["government_bond_rate"] + 0.01)
    return base * rate * CAPITAL_INCOME_TAX


# --- Facit för bytesförslag (SET-5, issue #197) --------------------------------------------

FACIT_SHORT_MAX_DAYS = 29  # SwitchOutcomeCalc.SHORT_HORIZON_MAX_DAYS: "under 1 månad" (< 30 dagar)
FACIT_MEDIUM_MAX_DAYS = 182  # SwitchOutcomeCalc.MEDIUM_HORIZON_MAX_DAYS: "1–6 månader"; längre i årstakt


def latest_common_nav(sell: list[tuple[date, float]], buy: list[tuple[date, float]], earliest: date | None = None) -> tuple[date, float, float] | None:
    """Senaste dag (≥ earliest) där båda fonderna har en kurs > 0: (dag, säljkurs, köpkurs)."""
    sell_by_day = {d: v for d, v in sell if v > 0 and (earliest is None or d >= earliest)}
    buy_by_day = {d: v for d, v in buy if v > 0 and (earliest is None or d >= earliest)}
    common = sorted(set(sell_by_day) & set(buy_by_day))
    if not common:
        return None
    day = common[-1]
    return day, sell_by_day[day], buy_by_day[day]


def facit_horizon(days: int) -> str:
    if days <= FACIT_SHORT_MAX_DAYS:
        return "UNDER_ONE_MONTH"
    if days <= FACIT_MEDIUM_MAX_DAYS:
        return "ONE_TO_SIX_MONTHS"
    return "OVER_SIX_MONTHS"


def facit_relative(sell_return: float, buy_return: float) -> float:
    """Köpfondens relativa utveckling mot säljfonden: (1 + köp) / (1 + sälj) − 1.

    Det är den storheten som kan kompounderas i årstakt; den aritmetiska meravkastningen
    köp − sälj kan inte det (SwitchOutcomeCalc.relativeReturn).
    """
    return (1.0 + buy_return) / (1.0 + sell_return) - 1.0


def facit_annualized(relative: float, days: int) -> float | None:
    """Relativ utveckling i årstakt (1 + r)^(365,2425 / dagar) − 1 via `annualized`; −100 % eller sämre ger −100 %.

    Facit räknar årstakt redan över sex månader (issue #197, användarens beslut) – därför
    `min_days=1` i stället för GIPS-gränsen på ett år.
    """
    if days <= 0:
        return None
    if 1.0 + relative <= 0:
        return -1.0
    return annualized(relative, days, min_days=1)


def median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    return ordered[mid] if len(ordered) % 2 else (ordered[mid - 1] + ordered[mid]) / 2.0


def facit_row(suggestion: dict, prices: Prices) -> dict:
    """Ett förslags utfall, mätt på den senaste dag båda fonderna har en kurs.

    Utgångsläget är NAV-dagen (`nav_date`, båda fonderna samma dag) eller förslagsdagen för
    rader utan den. Mätdagen är tidigast förslagsdagen och senare än NAV-dagen; finns ingen
    sådan gemensam dag är raden ej utvärderad.
    """
    suggested = date.fromisoformat(suggestion["suggested"])
    base = date.fromisoformat(suggestion["nav_date"]) if suggestion.get("nav_date") else suggested
    earliest = max(suggested, base + timedelta(days=1))
    pair = latest_common_nav(prices.series.get(suggestion["sell"], []), prices.series.get(suggestion["buy"], []), earliest)
    row = {"id": suggestion["id"], "measured": None, "days": None, "horizon": None, "sell_return": None, "buy_return": None, "excess": None, "excess_kr": None, "relative": None, "annualized": None, "better": False}
    if pair is None or suggestion["sell_nav"] <= 0 or suggestion["buy_nav"] <= 0:
        return row
    day, sell_nav, buy_nav = pair
    sell_return = sell_nav / suggestion["sell_nav"] - 1.0
    buy_return = buy_nav / suggestion["buy_nav"] - 1.0
    excess = buy_return - sell_return
    days = (day - base).days
    value = suggestion.get("value_kr")
    return row | {
        "measured": day.isoformat(),
        "days": days,
        "horizon": facit_horizon(days),
        "sell_return": sell_return,
        "buy_return": buy_return,
        "excess": excess,
        "excess_kr": None if value is None else value * excess,
        "relative": facit_relative(sell_return, buy_return),
        "annualized": facit_annualized(facit_relative(sell_return, buy_return), days),
        "better": excess > 0,
    }


def facit_summary(rows: list[dict]) -> dict:
    """Träffandel, median och kronsumma – aldrig ett snitt över förslag med olika ålder."""
    evaluated = [r for r in rows if r["excess"] is not None]
    kr = [r["excess_kr"] for r in evaluated if r["excess_kr"] is not None]
    return {
        "total": len(rows),
        "evaluated": len(evaluated),
        "better": sum(1 for r in evaluated if r["better"]),
        "median_excess": median([r["excess"] for r in evaluated]),
        "total_excess_kr": sum(kr) if kr else None,
    }


def facit_by_horizon(rows: list[dict]) -> list[dict]:
    """Per horisontband, kortast först; över sex månader är medianen i årstakt. Tomma band utelämnas."""
    bands = []
    for horizon in ("UNDER_ONE_MONTH", "ONE_TO_SIX_MONTHS", "OVER_SIX_MONTHS"):
        group = [r for r in rows if r["horizon"] == horizon]
        if not group:
            continue
        key = "annualized" if horizon == "OVER_SIX_MONTHS" else "excess"
        bands.append({"horizon": horizon, "count": len(group), "better": sum(1 for r in group if r["better"]), "median": median([r[key] for r in group])})
    return bands


def facit_golden(fixture: dict) -> dict:
    """Facit för en fixtur med `prices` och `suggestions` (se README)."""
    try:
        prices = parse_prices(fixture["prices"])
        suggestions = fixture["suggestions"]
        rows = [facit_row(s, prices) for s in suggestions]
    except KeyError as e:
        raise FixtureError(f"fältet {e} saknas (se README: prices, suggestions med id, suggested, sell, buy, sell_nav, buy_nav)") from None
    except (TypeError, ValueError) as e:
        raise FixtureError(str(e)) from None
    followed = [r for r, s in zip(rows, suggestions) if s.get("followed")]
    return {
        "rows": rows,
        "all": facit_summary(rows),
        "followed": facit_summary(followed),
        "by_horizon": facit_by_horizon(rows),
    }


# --- Kommandoraden --------------------------------------------------------------------------


def finite_or_none(value):
    """Ersätter NaN och ±∞ med None rekursivt, så att utskriften alltid är giltig JSON."""
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, dict):
        return {k: finite_or_none(v) for k, v in value.items()}
    if isinstance(value, list):
        return [finite_or_none(v) for v in value]
    return value


class FixtureError(Exception):
    """Fel i fixturen – skiljs från fel i beräkningskoden, som ska synas som de är."""


def parse_fixture(fixture: dict) -> tuple[list[Transaction], Prices, date]:
    try:
        transactions = parse_transactions(fixture["transactions"])
        prices = parse_prices(fixture["prices"])
        end = date.fromisoformat(fixture["end"])
    except KeyError as e:
        raise FixtureError(f"fältet {e} saknas (se README: end, prices, transactions med date, fund, kind, units, price, fee)") from None
    except (TypeError, ValueError) as e:
        raise FixtureError(str(e)) from None
    unknown = sorted({t.fund for t in transactions} - set(prices.series))
    if unknown:
        raise FixtureError(f"kurser saknas för: {', '.join(unknown)}")
    return transactions, prices, end


def golden(fixture: dict) -> dict:
    if "suggestions" in fixture:
        return finite_or_none(
            {
                "conventions": {"days_per_year": DAYS_PER_YEAR, "facit_short_max_days": FACIT_SHORT_MAX_DAYS, "facit_medium_max_days": FACIT_MEDIUM_MAX_DAYS},
                "facit": facit_golden(fixture),
            }
        )
    transactions, prices, end = parse_fixture(fixture)
    days = (end - transactions[0].day).days
    twr, twr_partial = time_weighted_return(transactions, prices, end)
    value, value_partial = market_value(transactions, prices, end)
    mwr, mwr_partial = money_weighted_return(transactions, prices, end)
    # Beteendegapet ska bara mäta tajmingen: XIRR utan courtage (TWR ser inga avgifter) mot TWR i
    # samma årslängd som XIRR (ACT/365F). Då blir det noll när det inte finns något att tajma.
    mwr_ex_fees, _ = money_weighted_return(transactions, prices, end, include_fees=False)
    twr_365 = annualized(twr, days, XIRR_DAYS_PER_YEAR)
    deposited = net_deposits(transactions)
    remaining_cost = average_cost_remaining(transactions)
    remaining_cost_fifo = fifo_remaining_cost(transactions)
    comparable = mwr_ex_fees is not None and twr_365 is not None and not (mwr_partial or twr_partial)

    def sale_rows(sales: list[Sale]) -> list[dict]:
        return [
            {"date": s.day.isoformat(), "fund": s.fund, "units": s.units, "proceeds": s.proceeds, "fee": s.fee, "cost_basis": s.cost_basis, "gain": s.gain, "uncovered_units": s.uncovered_units}
            for s in sales
        ]

    return finite_or_none(
        {
            "conventions": {
                "trading_days_per_year": TRADING_DAYS_PER_YEAR,
                "days_per_year": DAYS_PER_YEAR,
                "xirr_day_count": "ACT/365F",
                "annualize_min_days": ANNUALIZE_MIN_DAYS,
                "min_window_coverage": MIN_WINDOW_COVERAGE,
                "min_window_returns": {str(y): min_window_returns(y) for y in RISK_WINDOW_YEARS},
                "max_return_gap_days": MAX_NAV_GAP_DAYS,
                "max_window_gap_days": MAX_WINDOW_GAP_DAYS,
                "risk_window_years": list(RISK_WINDOW_YEARS),
                "risk_free_rate": 0.0,
                "volatility_ddof": 1,
            },
            "portfolio": {
                "days": days,
                "market_value": value,
                "market_value_partial": value_partial,
                "twr_total": twr,
                "twr_annualized": annualized(twr, days),
                "twr_annualized_act365": twr_365,
                "twr_partial": twr_partial,
                "xirr": mwr,
                "xirr_excluding_fees": mwr_ex_fees,
                "xirr_partial": mwr_partial,
                # behaviour_gap = xirr_excluding_fees − twr_annualized_act365. Ett partiellt mått
                # jämförs inte: gapet blir None hellre än en skenbar siffra.
                "behaviour_gap": (mwr_ex_fees - twr_365) if comparable else None,
                # Totalresultat: allt som kommit tillbaka plus värdet, minus allt som satts in –
                # realiserat, orealiserat och avgifter. None när ett innehav saknat kurs.
                "net_deposits": deposited,
                "total_gain": None if value_partial else value - deposited,
                "total_gain_fraction": None if value_partial or deposited <= 0 else (value - deposited) / deposited,
                # Totalkortets "Varav orealiserat": värde mot kvarvarande anskaffningsvärde enligt
                # genomsnittsmetoden (appens regel sedan issue #192); FIFO kvar som referens.
                "remaining_cost_average": remaining_cost,
                "unrealized_average_cost_as_app": None if value_partial else value - remaining_cost,
                "unrealized_average_cost_as_app_fraction": None if value_partial or remaining_cost <= 0 else (value - remaining_cost) / remaining_cost,
                "unrealized_fifo": None if value_partial else value - remaining_cost_fifo,
            },
            "funds": {
                fund: risk_figures(series) | {"periods": period_rows(transactions, prices, fund, end), "sma_200": sma_200(series)}
                for fund, series in prices.series.items()
            },
            "realized_average_cost": sale_rows(average_cost_sales(transactions)),
            "realized_fifo": sale_rows(fifo_sales(transactions)),
        }
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    g = sub.add_parser("golden", help="förväntade värden ur en fixtur (JSON)")
    g.add_argument("fixture")
    i = sub.add_parser("isk", help="schablonskatt på ISK/KF")
    i.add_argument("year", type=int, choices=sorted(ISK_PARAMETERS))
    i.add_argument("values", type=float, nargs=4, help="ingående värde kvartal 1–4")
    i.add_argument("--deposits", type=float, default=0.0)
    args = parser.parse_args(argv)

    if args.command == "golden":
        try:
            with open(args.fixture, encoding="utf-8") as f:
                fixture = json.load(f)
            result = golden(fixture)
        except json.JSONDecodeError as e:
            raise SystemExit(f"Fixturen är inte giltig JSON: {e}") from None
        except FixtureError as e:
            raise SystemExit(f"Ogiltig fixtur: {e}") from None
        json.dump(result, sys.stdout, indent=2, ensure_ascii=False, allow_nan=False)
        print()
    else:
        print(f"{isk_tax(args.year, args.values, args.deposits):.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
