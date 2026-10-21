"""Referensberäkningar för Fonders fondmatematik (skill `fondmatematik`).

Räknar fram förväntade värden ("golden values") som checkas in i appens JUnit-tester, med
oberoende implementeringar: XIRR via pyxirr, volatilitet/Sharpe/Sortino/drawdown via
empyrical-reloaded, och tidsviktad avkastning, genomsnittsmetoden, FIFO och ISK-skatt
skrivna här efter definitionerna i skillen. Körs bara i utvecklingen, aldrig i appen eller CI.

    python tools/fondmatte/fondmatte.py golden tools/fondmatte/exempel.json

Konventionerna är appens (FundAnalysisCalc): 252 handelsdagar per år, urvalsstandardavvikelse
(n−1), riskfri ränta 0 %, minst 60 dagsavkastningar för volatilitet och Sharpe, ingen årstakt
under 365 dagar och 365,2425 dagar per år. XIRR räknas som Excel (ACT/365F).
Testdata är alltid påhittad – aldrig riktiga innehav (skill `data-privacy-security`).
"""

from __future__ import annotations

import argparse
import bisect
import json
import math
import sys
from dataclasses import dataclass
from datetime import date

TRADING_DAYS_PER_YEAR = 252
DAYS_PER_YEAR = 365.2425
XIRR_DAYS_PER_YEAR = 365  # pyxirr DayCount.ACT_365F, samma som Excels XIRR
ANNUALIZE_MIN_DAYS = 365
MIN_RETURN_SAMPLES = 60  # FundAnalysisCalc.MIN_RETURN_SAMPLES
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


def daily_returns(points: list[tuple[date, float]]) -> list[float]:
    """Enkla avkastningar mellan på varandra följande kurspunkter, som FundAnalysisCalc."""
    navs = [v for _, v in points if v > 0.0]
    return [b / a - 1.0 for a, b in zip(navs, navs[1:])]


def annualized(total_return: float, days: int, days_per_year: float = DAYS_PER_YEAR) -> float | None:
    """Årstakt bara från ett år (GIPS); kortare perioder visas som total avkastning."""
    if days < ANNUALIZE_MIN_DAYS:
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


# --- Risk -----------------------------------------------------------------------------------


def risk_figures(points: list[tuple[date, float]]) -> dict:
    """Nyckeltal för en kursserie. Volatilitet, Sharpe och Sortino är None under
    MIN_RETURN_SAMPLES avkastningar och när de inte är ändliga (t.ex. nollvolatilitet), som
    i FundAnalysisCalc. CAGR gäller seriens första till sista punkt, inte innehavstiden."""
    import empyrical
    import numpy as np

    navs = [(d, v) for d, v in points if v > 0.0]
    returns = np.array(daily_returns(navs))
    out: dict = {"samples": int(returns.size)}
    if returns.size == 0:
        return out
    total = navs[-1][1] / navs[0][1] - 1.0
    out["total_return"] = total
    out["series_cagr"] = annualized(total, (navs[-1][0] - navs[0][0]).days)
    out["max_drawdown"] = float(empyrical.max_drawdown(returns))
    enough = returns.size >= MIN_RETURN_SAMPLES
    # empyrical: std med ddof=1 × √252, som FundAnalysisCalc.annualizedVolatility.
    # Nollvolatilitet är 0,0 som i appen; Sharpe och Sortino är då meningslösa och blir None.
    vol = float(empyrical.annual_volatility(returns, period="daily")) if enough else None
    out["annualized_volatility"] = vol
    has_risk = vol is not None and math.isfinite(vol) and vol > 0.0
    # empyrical: medel / std × √252 = (medel × 252) / volatilitet, riskfri ränta 0.
    out["sharpe"] = float(empyrical.sharpe_ratio(returns, risk_free=0.0, period="daily")) if has_risk else None
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
                "min_return_samples": MIN_RETURN_SAMPLES,
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
            "funds": {fund: risk_figures(series) for fund, series in prices.series.items()},
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
