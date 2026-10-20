"""Finansinspektionens fondinnehav per kvartal: genomlysning och överlapp mellan fonder.

FI publicerar varje svenskregistrerad värdepappersfonds innehav som XML i en ZIP per kvartal
(https://www.fi.se/sv/vara-register/fondinnehav-per-kvartal/), ungefär 3,5 månader efter
kvartalsslut. Utländska fonder (LU, IE …) finns inte med. Körs bara i utvecklingen.

    python tools/fondmatte/fi_innehav.py hamta 2026Q2 --dir /tmp/fi
    python tools/fondmatte/fi_innehav.py fond /tmp/fi/Fondinnehav_2026Q2.zip SE0000000000
    python tools/fondmatte/fi_innehav.py overlapp /tmp/fi/Fondinnehav_2026Q2.zip ISIN1 ISIN2
    python tools/fondmatte/fi_innehav.py genomlysning /tmp/fi/Fondinnehav_2026Q2.zip ISIN1=0.6 ISIN2=0.4

Lägg ZIP-filen i en egen katalog utanför repot; innehållet är okontrollerad data. Fråga aldrig
om användarens riktiga innehav här – använd fonder ur katalogen eller påhittade vikter.
"""

from __future__ import annotations

import argparse
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


@dataclass
class Holding:
    key: str  # instrumentets ISIN, eller namnet när ISIN saknas (derivat, likvida medel)
    name: str
    weight: float  # andel av fondförmögenheten, 0–1
    country: str
    sector: str


@dataclass
class Fund:
    isin: str
    name: str
    company: str
    quarter_end: str
    std_24m: float | None
    holdings: list[Holding] = field(default_factory=list)

    @property
    def weights(self) -> dict[str, float]:
        out: dict[str, float] = {}
        for h in self.holdings:
            out[h.key] = out.get(h.key, 0.0) + h.weight
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
        name = _text(item, "Instrumentnamn") or ""
        weight = _float(_text(item, "Andel_av_fondförmögenhet_instrument"))
        if weight is None:
            continue
        fund.holdings.append(
            Holding(
                key=_text(item, "ISIN-kod_instrument") or name.upper(),
                name=name,
                weight=weight / 100.0,
                country=_text(item, "Landkod_Emittent") or "?",
                sector=_text(item, "Bransch/Bransch_namn_instrument") or "Okänd",
            )
        )
    return fund


def load_funds(zip_path: str) -> dict[str, Fund]:
    funds = {}
    with zipfile.ZipFile(zip_path) as z:
        for name in z.namelist():
            if name.lower().endswith(".xml"):
                fund = parse_fund(z.read(name))
                if fund.isin:
                    funds[fund.isin] = fund
    return funds


def overlap(a: dict[str, float], b: dict[str, float]) -> float:
    """Överlapp = Σ min(vikt i A, vikt i B) över gemensamma innehav (0 = inget, 1 = identiska)."""
    return sum(min(w, b[k]) for k, w in a.items() if k in b)


def look_through(funds: dict[str, Fund], portfolio: dict[str, float], attribute: str) -> dict[str, float]:
    out: dict[str, float] = {}
    for isin, portfolio_weight in portfolio.items():
        for h in funds[isin].holdings:
            key = getattr(h, attribute)
            out[key] = out.get(key, 0.0) + portfolio_weight * h.weight
    return dict(sorted(out.items(), key=lambda kv: -kv[1]))


def download(quarter: str, directory: str) -> Path:
    with urllib.request.urlopen(LIST_URL, timeout=60) as r:
        page = r.read().decode("utf-8", "replace")
    links = re.findall(r'href="(/FondInnehavLista/download\?filnamn=Fondinnehav_' + re.escape(quarter) + r'_[^"]+\.zip)"', page)
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


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    h = sub.add_parser("hamta", help="ladda ned ett kvartal, t.ex. 2026Q2")
    h.add_argument("quarter")
    h.add_argument("--dir", required=True)
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

    funds = load_funds(args.zip)
    missing = [i for i in (args.isins if args.command == "overlapp" else [args.isin] if args.command == "fond" else [w.split("=")[0] for w in args.weights]) if i not in funds]
    if missing:
        print(f"Saknas i filen (inte svenskregistrerad, eller annat kvartal): {', '.join(missing)}", file=sys.stderr)
        return 1

    if args.command == "fond":
        fund = funds[args.isin]
        print(f"{fund.name} ({fund.company}), kvartalsslut {fund.quarter_end}, std 24 mån {fund.std_24m} %")
        for holding in sorted(fund.holdings, key=lambda x: -x.weight)[: args.top]:
            print(f"  {_pct(holding.weight)}  {holding.name} [{holding.country}, {holding.sector}]")
    elif args.command == "overlapp":
        for i, a in enumerate(args.isins):
            for b in args.isins[i + 1 :]:
                print(f"{_pct(overlap(funds[a].weights, funds[b].weights))}  {funds[a].name} ↔ {funds[b].name}")
    else:
        portfolio = {k: float(v) for k, v in (w.split("=") for w in args.weights)}
        # Innehaven summeras på ISIN (namnen stavas olika mellan fondbolagen), och visas med namn.
        names = {h.key: h.name for isin in portfolio for h in funds[isin].holdings}
        for title, attribute in (("Land", "country"), ("Bransch", "sector"), ("Största innehav", "key")):
            print(title)
            for key, weight in list(look_through(funds, portfolio, attribute).items())[: args.top]:
                print(f"  {_pct(weight)}  {names.get(key, key) if attribute == 'key' else key}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
