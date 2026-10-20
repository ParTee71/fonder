"""Referensberäkningar för Fonders fondmatematik (skill `fondmatematik`).

Räknar fram förväntade värden ("golden values") som checkas in i appens JUnit-tester, med
oberoende implementeringar: XIRR via pyxirr, volatilitet/Sharpe/Sortino/drawdown via
empyrical-reloaded, och tidsviktad avkastning, genomsnittsmetoden, FIFO och ISK-skatt
skrivna här efter definitionerna i skillen. Körs bara i utvecklingen, aldrig i appen eller CI.

    python tools/fondmatte/fondmatte.py golden tools/fondmatte/exempel.json

Konventionerna är appens (FundAnalysisCalc): 252 handelsdagar per år, urvalsstandardavvikelse
(n−1), riskfri ränta 0 %, ingen årstakt under 365 dagar och 365,2425 dagar per år.
Testdata är alltid påhittad – aldrig riktiga innehav (skill `data-privacy-security`).
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from datetime import date

TRADING_DAYS_PER_YEAR = 252
DAYS_PER_YEAR = 365.2425
ANNUALIZE_MIN_DAYS = 365

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
    day: date
    fund: str
    kind: str  # "BUY" eller "SELL"
    units: float
    amount: float  # kronor exklusive avgift
    fee: float = 0.0


@dataclass(frozen=True)
class Sale:
    day: date
    fund: str
    units: float
    proceeds: float
    cost_basis: float

    @property
    def gain(self) -> float:
        return self.proceeds - self.cost_basis


def parse_transactions(raw: list[dict]) -> list[Transaction]:
    txs = [
        Transaction(
            day=date.fromisoformat(t["date"]),
            fund=t["fund"],
            kind=t["kind"],
            units=float(t["units"]),
            amount=float(t["amount"]),
            fee=float(t.get("fee", 0.0)),
        )
        for t in raw
    ]
    return sorted(txs, key=lambda t: t.day)


def parse_prices(raw: dict[str, list]) -> dict[str, list[tuple[date, float]]]:
    return {fund: sorted((date.fromisoformat(d), float(v)) for d, v in points) for fund, points in raw.items()}


def nav_on_or_before(prices: list[tuple[date, float]], day: date) -> float | None:
    known = [v for d, v in prices if d <= day]
    return known[-1] if known else None


# --- Avkastning -----------------------------------------------------------------------------


def daily_returns(prices: list[tuple[date, float]]) -> list[float]:
    """Enkla avkastningar mellan på varandra följande kurspunkter, som FundAnalysisCalc."""
    navs = [v for _, v in prices if v > 0.0]
    return [b / a - 1.0 for a, b in zip(navs, navs[1:])]


def annualized(total_return: float, days: int) -> float | None:
    """Årstakt bara från ett år (GIPS); kortare perioder visas som total avkastning."""
    if days < ANNUALIZE_MIN_DAYS:
        return None
    return (1.0 + total_return) ** (DAYS_PER_YEAR / days) - 1.0


def time_weighted_return(transactions: list[Transaction], prices: dict[str, list[tuple[date, float]]], end: date) -> float:
    """Kedjad tidsviktad avkastning med gårdagens andelar, som PortfolioReturnSeriesCalc.

    Dagens faktor värderar andelarna som ägdes före dagens transaktioner till gårdagens och
    dagens NAV, så in- och utflöden faller ur måttet.
    """
    if not transactions:
        return 0.0
    start = transactions[0].day
    timeline = sorted({d for series in prices.values() for d, _ in series if start <= d <= end} | {t.day for t in transactions if t.day <= end})
    units: dict[str, float] = {}
    index = 1.0
    previous_day = None
    for day in timeline:
        if previous_day is not None:
            before = sum(u * (nav_on_or_before(prices[f], previous_day) or 0.0) for f, u in units.items())
            after = sum(u * (nav_on_or_before(prices[f], day) or 0.0) for f, u in units.items())
            if before > 0.0:
                index *= after / before
        for t in transactions:
            if t.day == day:
                units[t.fund] = units.get(t.fund, 0.0) + (t.units if t.kind == "BUY" else -t.units)
        previous_day = day
    return index - 1.0


def market_value(transactions: list[Transaction], prices: dict[str, list[tuple[date, float]]], day: date) -> float:
    units: dict[str, float] = {}
    for t in transactions:
        if t.day <= day:
            units[t.fund] = units.get(t.fund, 0.0) + (t.units if t.kind == "BUY" else -t.units)
    return sum(u * (nav_on_or_before(prices[f], day) or 0.0) for f, u in units.items() if abs(u) > 1e-12)


def money_weighted_return(transactions: list[Transaction], prices: dict[str, list[tuple[date, float]]], end: date) -> float | None:
    """XIRR ur användarens kassaflöden: köp (inkl. avgift) ut, sälj (minus avgift) in, slutvärdet in."""
    from pyxirr import xirr

    dates, amounts = [], []
    for t in transactions:
        if t.day > end:
            continue
        dates.append(t.day)
        amounts.append(-(t.amount + t.fee) if t.kind == "BUY" else t.amount - t.fee)
    dates.append(end)
    amounts.append(market_value(transactions, prices, end))
    return xirr(dates, amounts)


# --- Risk -----------------------------------------------------------------------------------


def risk_figures(prices: list[tuple[date, float]]) -> dict:
    import empyrical
    import numpy as np

    returns = np.array(daily_returns(prices))
    if returns.size < 2:
        return {"samples": int(returns.size)}
    days = (prices[-1][0] - prices[0][0]).days
    total = prices[-1][1] / prices[0][1] - 1.0
    return {
        "samples": int(returns.size),
        "total_return": total,
        "cagr": annualized(total, days),
        # empyrical: std med ddof=1 × √252, som FundAnalysisCalc.annualizedVolatility.
        "annualized_volatility": float(empyrical.annual_volatility(returns, period="daily")),
        # empyrical: medel / std × √252 = (medel × 252) / volatilitet, riskfri ränta 0.
        "sharpe": float(empyrical.sharpe_ratio(returns, risk_free=0.0, period="daily")),
        # Nedsidesrisk över alla perioder: √(medel(min(r, 0)²)) × √252.
        "sortino": float(empyrical.sortino_ratio(returns, required_return=0.0, period="daily")),
        "max_drawdown": float(empyrical.max_drawdown(returns)),
    }


# --- Anskaffningsvärde ----------------------------------------------------------------------


def average_cost_sales(transactions: list[Transaction]) -> list[Sale]:
    """Genomsnittsmetoden (Skatteverket): köpavgiften ingår i anskaffningsvärdet, ett sälj
    ändrar inte snittet per andel, säljavgiften dras från försäljningspriset."""
    units: dict[str, float] = {}
    cost: dict[str, float] = {}
    sales = []
    for t in transactions:
        if t.kind == "BUY":
            units[t.fund] = units.get(t.fund, 0.0) + t.units
            cost[t.fund] = cost.get(t.fund, 0.0) + t.amount + t.fee
        else:
            held = units.get(t.fund, 0.0)
            average = cost[t.fund] / held if held > 0 else 0.0
            sold_cost = average * min(t.units, held)
            sales.append(Sale(t.day, t.fund, t.units, t.amount - t.fee, sold_cost))
            units[t.fund] = held - t.units
            cost[t.fund] = cost.get(t.fund, 0.0) - sold_cost
    return sales


def fifo_sales(transactions: list[Transaction]) -> list[Sale]:
    """FIFO som jämförelse (appens RealizedGainCalculator i dag): äldsta lott säljs först."""
    lots: dict[str, list[list[float]]] = {}
    sales = []
    for t in transactions:
        if t.kind == "BUY":
            lots.setdefault(t.fund, []).append([t.units, (t.amount + t.fee) / t.units])
        else:
            remaining, sold_cost = t.units, 0.0
            queue = lots.get(t.fund, [])
            while remaining > 1e-12 and queue:
                take = min(remaining, queue[0][0])
                sold_cost += take * queue[0][1]
                queue[0][0] -= take
                remaining -= take
                if queue[0][0] <= 1e-12:
                    queue.pop(0)
            sales.append(Sale(t.day, t.fund, t.units, t.amount - t.fee, sold_cost))
    return sales


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


def golden(fixture: dict) -> dict:
    transactions = parse_transactions(fixture["transactions"])
    prices = parse_prices(fixture["prices"])
    end = date.fromisoformat(fixture["end"])
    days = (end - transactions[0].day).days
    twr = time_weighted_return(transactions, prices, end)
    twr_annual = annualized(twr, days)
    mwr = money_weighted_return(transactions, prices, end)

    def sale_rows(sales: list[Sale]) -> list[dict]:
        return [{"date": s.day.isoformat(), "fund": s.fund, "units": s.units, "proceeds": s.proceeds, "cost_basis": s.cost_basis, "gain": s.gain} for s in sales]

    return {
        "conventions": {"trading_days_per_year": TRADING_DAYS_PER_YEAR, "days_per_year": DAYS_PER_YEAR, "annualize_min_days": ANNUALIZE_MIN_DAYS, "risk_free_rate": 0.0, "volatility_ddof": 1},
        "portfolio": {
            "days": days,
            "market_value": market_value(transactions, prices, end),
            "twr_total": twr,
            "twr_annualized": twr_annual,
            "xirr": mwr,
            # Beteendegapet: pengaviktad minus tidsviktad, båda i årstakt (bara från ett år).
            "behaviour_gap": (mwr - twr_annual) if (mwr is not None and twr_annual is not None) else None,
        },
        "funds": {fund: risk_figures(series) for fund, series in prices.items()},
        "realized_average_cost": sale_rows(average_cost_sales(transactions)),
        "realized_fifo": sale_rows(fifo_sales(transactions)),
    }


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
        with open(args.fixture, encoding="utf-8") as f:
            result = golden(json.load(f))
        json.dump(result, sys.stdout, indent=2, ensure_ascii=False, default=lambda x: None if isinstance(x, float) and math.isnan(x) else x)
        print()
    else:
        print(f"{isk_tax(args.year, args.values, args.deposits):.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
