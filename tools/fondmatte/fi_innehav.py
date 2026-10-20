"""Finansinspektionens fondinnehav per kvartal: genomlysning och överlapp mellan fonder.

FI publicerar varje svenskregistrerad värdepappersfonds innehav som XML i en ZIP per kvartal
(https://www.fi.se/sv/vara-register/fondinnehav-per-kvartal/), ungefär 3,5 månader efter
kvartalsslut. Utländska fonder (LU, IE …) finns inte med. Körs bara i utvecklingen.

    python tools/fondmatte/fi_innehav.py hamta 2026Q2 --dir /tmp/fi
    python tools/fondmatte/fi_innehav.py sok /tmp/fi/Fondinnehav_2026Q2.zip "sverige index"
    python tools/fondmatte/fi_innehav.py fond /tmp/fi/Fondinnehav_2026Q2.zip SE0000000000
    python tools/fondmatte/fi_innehav.py overlapp /tmp/fi/Fondinnehav_2026Q2.zip ISIN1 ISIN2
    python tools/fondmatte/fi_innehav.py genomlysning /tmp/fi/Fondinnehav_2026Q2.zip ISIN1=0.6 ISIN2=0.4

Lägg ZIP-filen i en egen katalog utanför repot; innehållet är okontrollerad data. Fråga aldrig
om användarens riktiga innehav här – använd fonder ur katalogen eller påhittade vikter.
"""

from __future__ import annotations

import argparse
import math
import re
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

LIST_URL = "https://www.fi.se/sv/vara-register/fondinnehav-per-kvartal/"
BASE_URL = "https://www.fi.se"
ISIN_PATTERN = re.compile(r"[A-Z]{2}[A-Z0-9]{9}[0-9]")

# Fondbolagen namnger branscherna olika (GICS på engelska eller egna svenska namn), men
# Branschkod_instrument är GICS-sektorns kod hos alla. Kod 0 och okända koder blir "Övrigt".
GICS_SECTORS = {
    "10": "Energi",
    "15": "Material",
    "20": "Industri",
    "25": "Sällanköpsvaror",
    "30": "Dagligvaror",
    "35": "Hälsovård",
    "40": "Finans",
    "45": "Informationsteknik",
    "50": "Kommunikationstjänster",
    "55": "Kraftförsörjning",
    "60": "Fastigheter",
}
OTHER_SECTOR = "Övrigt"
NO_ISIN = "Utan ISIN (derivat, likvida medel m.m.)"
UNREPORTED = "Ej redovisat som innehav (likvida medel, övriga tillgångar och skulder)"
LEVERAGE = "Skulder och belåning (innehaven summerar över 100 %)"
# FI avrundar varje vikt; en fond som summerar till upp till 101 % är avrundning, inte belåning.
ROUNDING_TOLERANCE = 0.01


@dataclass
class Holding:
    isin: str | None  # None för derivat, valutaterminer, likvida medel och liknande
    name: str
    weight: float  # andel av fondförmögenheten, 0–1; negativ för t.ex. sålda terminer
    country: str
    sector: str

    @property
    def counts_for_overlap(self) -> bool:
        return self.isin is not None and self.weight > 0.0


@dataclass
class Fund:
    isin: str  # tom när FI-filen saknar Fond_ISIN-kod
    name: str
    company: str
    quarter_end: str
    std_24m: float | None
    holdings: list[Holding] = field(default_factory=list)

    @property
    def overlap_weights(self) -> dict[str, float]:
        """Vikt per värdepapper med ISIN och positiv vikt – det som kan överlappa."""
        out: dict[str, float] = {}
        for h in self.holdings:
            if h.counts_for_overlap:
                out[h.isin] = out.get(h.isin, 0.0) + h.weight
        return out


def _text(element, path: str) -> str | None:
    found = element.find(path)
    return found.text.strip() if found is not None and found.text else None


def _float(value: str | None) -> float | None:
    try:
        return float(value) if value is not None else None
    except ValueError:
        return None


def parse_fund(xml_bytes: bytes) -> Fund:
    root = ET.fromstring(xml_bytes)
    # FI:s schema har ett eget namnrymd-URI per version; taggnamnen är det som är stabilt.
    for element in root.iter():
        element.tag = element.tag.rsplit("}", 1)[-1]
    info = root.find("Fondinformation")
    if info is None:
        return Fund("", "", "", "", None)
    fund = Fund(
        isin=_text(info, "Fond_ISIN-kod") or "",
        name=_text(info, "Fond_namn") or "",
        company=_text(root, "Bolagsinformation/Fondbolag_namn") or "",
        quarter_end=_text(root, "Rapportinformation/Kvartalsslut") or "",
        std_24m=_float(_text(info, "Standardavvikelse_24_månader")),
    )
    for item in info.iterfind("FinansiellaInstrument/FinansielltInstrument"):
        weight = _float(_text(item, "Andel_av_fondförmögenhet_instrument"))
        if weight is None:
            continue
        isin = (_text(item, "ISIN-kod_instrument") or "").upper()
        fund.holdings.append(
            Holding(
                isin=isin if ISIN_PATTERN.fullmatch(isin) else None,
                name=_text(item, "Instrumentnamn") or "",
                weight=weight / 100.0,
                country=_text(item, "Landkod_Emittent") or "?",
                sector=GICS_SECTORS.get(_text(item, "Bransch/Branschkod_instrument") or "", OTHER_SECTOR),
            )
        )
    return fund


def load_funds(zip_path: str) -> list[Fund]:
    with zipfile.ZipFile(zip_path) as z:
        return [parse_fund(z.read(name)) for name in z.namelist() if name.lower().endswith(".xml")]


def by_isin(funds: list[Fund]) -> dict[str, Fund]:
    return {f.isin: f for f in funds if f.isin}


def overlap(a: dict[str, float], b: dict[str, float]) -> float:
    """Överlapp = Σ min(vikt i A, vikt i B) över gemensamma värdepapper (0 = inget, 1 = identiska).
    Vikterna kommer från [Fund.overlap_weights], så derivat och likvida medel räknas inte."""
    return sum(min(w, b[k]) for k, w in a.items() if k in b)


def normalized(portfolio: dict[str, float]) -> dict[str, float]:
    total = sum(portfolio.values())
    if total <= 0.0 or any(w <= 0.0 for w in portfolio.values()):
        raise ValueError("portföljvikterna måste vara positiva")
    return {k: w / total for k, w in portfolio.items()}


DIMENSIONS = {"country": lambda h: h.country, "sector": lambda h: h.sector, "security": lambda h: h.isin}


def look_through(funds: dict[str, Fund], portfolio: dict[str, float], dimension: str) -> dict[str, float]:
    """Portföljens andel per land, bransch eller värdepapper (ISIN), vägt med portföljvikterna.
    Innehav utan ISIN, det fonden inte redovisat som innehav och – för fonder vars innehav
    summerar över 100 % – skulder och belåning får egna rader, så att raderna blir 100 %."""
    key_of = DIMENSIONS[dimension]
    out: dict[str, float] = {}
    for isin, portfolio_weight in portfolio.items():
        fund = funds[isin]
        for h in fund.holdings:
            key = NO_ISIN if h.isin is None else key_of(h)
            out[key] = out.get(key, 0.0) + portfolio_weight * h.weight
        rest = 1.0 - sum(h.weight for h in fund.holdings)
        key = UNREPORTED if rest >= -ROUNDING_TOLERANCE else LEVERAGE
        out[key] = out.get(key, 0.0) + portfolio_weight * rest
    return dict(sorted(out.items(), key=lambda kv: -kv[1]))


def download(quarter: str, directory: str) -> Path:
    if not re.fullmatch(r"\d{4}Q[1-4]", quarter):
        raise SystemExit("kvartal skrivs som 2026Q2")
    with urllib.request.urlopen(LIST_URL, timeout=60) as r:
        page = r.read().decode("utf-8", "replace")
    links = re.findall(r'href="(/FondInnehavLista/download\?filnamn=Fondinnehav_' + quarter + r'_[^"]+\.zip)"', page)
    if not links:
        raise SystemExit(f"Hittade ingen fil för {quarter} på {LIST_URL}")
    target = Path(directory) / f"Fondinnehav_{quarter}.zip"
    target.parent.mkdir(parents=True, exist_ok=True)
    url = BASE_URL + urllib.parse.quote(links[0], safe="/?=&")
    with urllib.request.urlopen(url, timeout=300) as r, open(target, "wb") as f:
        f.write(r.read())
    return target


def _pct(x: float) -> str:
    return f"{x * 100:6.2f} %"


def _require(funds: dict[str, Fund], isins: list[str]) -> list[Fund]:
    missing = [i for i in isins if i not in funds]
    if missing:
        raise SystemExit(f"Saknas i filen (utländsk fond, saknar ISIN hos FI – prova sok – eller annat kvartal): {', '.join(missing)}")
    empty = [funds[i].name for i in isins if not funds[i].holdings]
    if empty:
        print(f"Varning: inga innehav redovisade för {', '.join(empty)} (t.ex. en fond-i-fond eller swapbaserad ETF)", file=sys.stderr)
    return [funds[i] for i in isins]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    h = sub.add_parser("hamta", help="ladda ned ett kvartal, t.ex. 2026Q2")
    h.add_argument("quarter")
    h.add_argument("--dir", required=True)
    s = sub.add_parser("sok", help="sök fonder på namn")
    s.add_argument("zip")
    s.add_argument("query")
    f = sub.add_parser("fond", help="en fonds största innehav")
    f.add_argument("zip")
    f.add_argument("isin")
    f.add_argument("--top", type=int, default=15)
    o = sub.add_parser("overlapp", help="parvis överlapp mellan fonder")
    o.add_argument("zip")
    o.add_argument("isins", nargs="+")
    g = sub.add_parser("genomlysning", help="portföljens land, bransch och största innehav")
    g.add_argument("zip")
    g.add_argument("weights", nargs="+", help="ISIN=vikt, t.ex. SE0000000000=0.6")
    g.add_argument("--top", type=int, default=10)
    args = parser.parse_args(argv)

    if args.command == "hamta":
        print(download(args.quarter, args.dir))
        return 0
    if args.command == "fond":
        args.isin = args.isin.upper()
    elif args.command == "overlapp":
        args.isins = [i.upper() for i in args.isins]

    all_funds = load_funds(args.zip)
    funds = by_isin(all_funds)

    if args.command == "sok":
        words = args.query.lower().split()
        for fund in sorted((x for x in all_funds if all(w in x.name.lower() for w in words)), key=lambda x: x.name):
            print(f"{fund.isin or '(saknar ISIN)':14}  {fund.name} ({fund.company}), {len(fund.holdings)} innehav")
    elif args.command == "fond":
        (fund,) = _require(funds, [args.isin])
        print(f"{fund.name} ({fund.company}), kvartalsslut {fund.quarter_end}, std 24 mån {fund.std_24m} %")
        for holding in sorted(fund.holdings, key=lambda x: -x.weight)[: args.top]:
            print(f"  {_pct(holding.weight)}  {holding.name} [{holding.country}, {holding.sector}]")
    elif args.command == "overlapp":
        selected = _require(funds, args.isins)
        for i, a in enumerate(selected):
            for b in selected[i + 1 :]:
                print(f"{_pct(overlap(a.overlap_weights, b.overlap_weights))}  {a.name} ↔ {b.name}")
    else:
        raw: dict[str, float] = {}
        for item in args.weights:
            isin, sep, weight = item.partition("=")
            try:
                value = float(weight) if sep else math.nan
            except ValueError:
                value = math.nan
            if not math.isfinite(value):
                raise SystemExit(f"Skriv vikten som ISIN=vikt, t.ex. SE0000000000=0.6: {item}")
            raw[isin.upper()] = raw.get(isin.upper(), 0.0) + value  # samma ISIN två gånger läggs ihop
        _require(funds, list(raw))
        try:
            portfolio = normalized(raw)
        except ValueError as e:
            raise SystemExit(str(e)) from None
        if abs(sum(raw.values()) - 1.0) > 1e-6:
            print(f"Vikterna summerade till {sum(raw.values()):g} och har skalats till 1.", file=sys.stderr)
        names = {h.isin: h.name for isin in portfolio for h in funds[isin].holdings if h.isin}
        for title, dimension in (("Land", "country"), ("Bransch", "sector"), ("Största innehav", "security")):
            print(title)
            rows = list(look_through(funds, portfolio, dimension).items())
            # Negativa rader (belåning, sålda terminer) visas alltid, så att det som står summerar rätt.
            shown = rows[: args.top] + [r for r in rows[args.top :] if r[1] < 0.0]
            for key, weight in shown:
                print(f"  {_pct(weight)}  {names.get(key, key)}")
            if len(shown) < len(rows):
                print(f"  {_pct(sum(w for k, w in rows if (k, w) not in shown))}  {len(rows) - len(shown)} rader till")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
