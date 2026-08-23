---
name: requirements-kravlista
description: Fonders kravregel (regel 3) — KRAVLISTA.md ska alltid spegla appens faktiska beteende. Ladda denna ALLTID när du lägger till, ändrar eller tar bort synligt beteende, en funktion, en skärm, en inställning eller ett UI-flöde. Trigger-ord: krav, kravlista, KRAVLISTA, specifikation, requirement, feature, funktion, beteende, versionsbump, versionName, ändra UI, ta bort funktion, historik.
---

# Hålla kraven aktuella (KRAVLISTA.md)

**Regel:** Varje ändring av användarsynligt beteende speglas i
[KRAVLISTA.md](../../../KRAVLISTA.md) i **samma** ändring/PR. Kraven är projektets sanning
om vad appen gör — kod och krav får aldrig glida isär.

## Format

KRAVLISTA.md är numrerade avsnitt med tabeller. Varje krav har ett **stabilt ID**:

| Prefix | Område |
|---|---|
| `ÖV` | Översikt och syfte |
| `TP` | Teknisk plattform (inkl. kurskällor, backup, inloggning) |
| `NAV` | Navigation och skärmar |
| `UI` | Utseende och tema |
| `NFR` | Icke-funktionellt (datasäkerhet, tester) |
| `SLD` | Sålda fonder |
| `HEM` | Hem (dashboard) |
| `ANA` | Analys — nyckeltal och säljsignaler |
| `POR` | Portfölj |
| `TRX` | Transaktioner |
| `IMP` | Import |
| `SET` | Inställningar |

| Du gör | Så här uppdaterar du |
|---|---|
| **Nytt beteende** | Ny rad i rätt tabell med **nästa lediga ID** i serien. Återanvänd aldrig ett gammalt ID. |
| **Ändrat beteende** | Redigera texten på det befintliga ID:t. Behåll ID:t. |
| **Borttaget beteende** | Stryk men **behåll raden**: `~~…~~ *(borttaget)*`. Radera aldrig raden — ID:t ska förbli spårbart. |
| **Helt nytt område** | Nytt numrerat avsnitt och ett nytt ID-prefix. |

## Hur en kravrad skrivs här

Fonders kravrader är ovanligt utförliga, och det är avsiktligt: de bär **motivet**, inte bara
beteendet. En rad förklarar typiskt vad appen gör, vilket alternativ som förkastades och
varför, och vilket issue som drev fram det. Skriv i den stilen — en rad som bara säger vad
knappen gör är en sämre rad än den den ersatte.

Konkret, det som ska med när det är relevant:

- **Vad** användaren ser eller kan göra.
- **Varför just så** — särskilt när ett rimligt alternativ valdes bort. ("Källan fail:ar
  *closed* på ett okänt filtervärde men *open* på ett okänt filternamn" är den sortens sak
  som annars måste återupptäckas.)
- **Vilka klasser** som äger logiken (`FundScreenFilter`, `NavCalendar`, …), så raden går att
  följa in i koden.
- **Issue-referensen** (`issue #93`).
- **Osäkerheten**, när kravet gäller ett råd. Rådgivande funktioner (ANA-9, HEM-8) anger
  uttryckligen hur ofta de har rätt och vad mätningen inte täcker.

## Följdartefakter

- **Backup:** rör ändringen persisterad data ska SET-6:s uppräkning och NFR-1 stämma —
  skill `data-safety-backup`.
- **Tester:** varje nytt eller ändrat krav ska ha ett test som bevisar det — skill
  `testing-strategy`.
- **README:** funktionslistan under "Status" bockas av när en funktion landar.
  Arkitekturträdet uppdateras om en ny klass eller ett nytt paket tillkommit.
- **Versionshistorik-tabellen i README** skrivs **inte** av feature-PR:en — den fylls vid
  releasen, av skill `release`. Blanda inte ihop de två.
- **`versionName`** höjs när funktionsomfånget ändras. Själva releasen görs separat och bara
  på uttrycklig begäran.

## Historik-avsnittet

KRAVLISTA har ett `## Historik`-avsnitt längst ned som beskriver större ändringar i löptext —
vad som var fel, vad som gjordes och varför. Lägg en post där när ändringen rättar ett
tidigare **felaktigt antagande** eller river upp ett tidigare beslut. En ren nyhet behöver
ingen historikpost; en rättelse av något KRAVLISTA tidigare påstod behöver det.

## Checklista

- [ ] Rätt avsnitt och ID-serie hittad.
- [ ] Nytt ID tillagt / befintligt redigerat / struket med `~~…~~ *(borttaget)*`.
- [ ] Inga ID:n återanvända eller raderade.
- [ ] Motivet står i raden, inte bara beteendet.
- [ ] README-status/arkitektur och `versionName` uppdaterade om omfånget ändrats.
- [ ] Historikpost om ändringen river upp ett tidigare beslut.
- [ ] Det finns test som bevisar kravet (regel 2).
