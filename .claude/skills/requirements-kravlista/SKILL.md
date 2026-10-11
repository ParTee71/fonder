---
name: requirements-kravlista
description: 'Fonders kravregel (regel 3) — KRAVLISTA.md ska alltid spegla appens faktiska beteende. Ladda denna ALLTID när du lägger till, ändrar eller tar bort synligt beteende, en funktion, en skärm, en inställning, en beräkning, ett UI-flöde eller en regel för data/backup/tester/CI. Trigger-ord: krav, kravlista, KRAVLISTA, specifikation, requirement, feature, funktion, beteende, formel, beräkning, nyckeltal, versionsbump, versionName, ändra UI, ta bort funktion, historik, NFR, TP, ANA, POR, HEM, SET.'
---

# Hålla kraven aktuella (KRAVLISTA.md)

**Regel:** varje ändring av användarsynligt beteende – och av icke-funktionella regler som
tester, CI och backup – speglas i [KRAVLISTA.md](../../../KRAVLISTA.md) i **samma** PR. Kraven
är projektets sanning om vad appen gör; kod och krav får aldrig glida isär.

## Format och konventioner

KRAVLISTA.md består av numrerade avsnitt med tabeller `| ID | Krav |`. Varje krav har ett
**stabilt ID** med prefix per område:

| Prefix | Område (avsnitt) |
|---|---|
| `ÖV` | Översikt och syfte (§1) |
| `TP` | Teknisk plattform – kurskällor, inloggning, backup, SDK-nivåer (§2) |
| `UI` | Utseende och tema (§3) |
| `NAV` | Navigation och skärmar, grund (§4) |
| `POR` | Portfölj (§4) |
| `TRX` | Transaktioner (§4) |
| `IMP` | Import (§4) |
| `SET` | Inställningar, inklusive säkerhetskopiering (§4) |
| `NFR` | Datasäkerhet och tester – NFR-1 rundtur, NFR-2 tester, NFR-3 migrering (§5) |
| `SLD` | Sålda fonder – säljen i Händelser (§6) |
| `HEM` | Hem (dashboard) (§7) |
| `ANA` | Analys – nyckeltal och säljsignaler (§8) |

| Du gör | Så här |
|---|---|
| **Nytt beteende** | Ny rad i rätt tabell med **nästa lediga ID** i serien. Återanvänd aldrig ett ID. |
| **Ändrat beteende** | Redigera texten på befintligt ID. Behåll ID:t. |
| **Borttaget beteende** | Behåll raden och stryk **hela** kravtexten: `~~…~~ *(borttaget)*` – med hänvisning till det som ersätter den när det finns (`*(borttaget – se POR-9)*`). Radera aldrig raden. |
| **Planerat men ej byggt** | Markera `*(planerad)*` sist i raden; ta bort markeringen i PR:en som bygger det. Hellre ett issue än en planerad rad. |
| **Helt nytt område** | Nytt numrerat avsnitt och nytt prefix – lägg även prefixet i tabellen ovan. |

## En kravrad beskriver beteende

En rad säger **vad appen gör** – vad användaren ser, kan göra och vilken regel som gäller –
så att det går att pröva med ett test eller i appen. Skriv kort och sakligt.

**Hör inte hemma i raden:**
- **Motiv och bortvalda alternativ** ("valdes i stället för …", "eftersom …") → PR- eller
  issuebeskrivningen.
- **Implementation** (klassnamn, Room-tabeller, workers, bibliotek) → README:s arkitekturavsnitt,
  KDoc i koden eller PR:en. Undantag: ett namn som *är* beteendet för användaren eller ett
  extern kontrakt (t.ex. filprefixet `fonder-backup-` i Drive, eftersom det skyddar Dagbokens filer).
- **Issue-historik** ("sedan issue #93", "tidigare gjorde den …") → git-historiken och PR:en.

**Hör hemma i raden:** gränsfall som ändrar vad användaren ser (saknad data ger "kunde inte
beräknas", aldrig 0 – ANA-4), och för **rådgivande** funktioner (ANA-9, HEM-8) hur säkert rådet
är och vad det inte täcker – det är en del av beteendet, inte ett motiv.

Många befintliga rader är längre än så. Rör du en sådan rad: skriv om den till beteende och
flytta motivet till PR-beskrivningen – men tappa inget beteende på vägen.

## Gemensamma beteenden beskrivs en gång

Beteenden som delade komponenter ger alla skärmar – väntesnurran i kortets rubrik (NAV-6),
dra för att uppdatera (UI-11), tryckytor och skärmläsartext (UI-3), utfällt läge som överlever
rotation (UI-9), risknivåns märkning (UI-10), tom data som "kunde inte beräknas" (ANA-4) – står
som **ett** krav. Funktionskraven refererar till dem (`Korten följer NAV-6 och UI-11.`) i
stället för att upprepa texten – regel 4 gäller även kravlistan.

## Beräkningar är krav

Regler som räknas – avkastning och realiserad vinst (POR, SLD), avgifter, risk och exponering
(POR, HEM), säljsignaler och bytesförslag (ANA, HEM), handelsdagar – står i kravlistan **som de
implementeras** i `domain/usecase/`. Ändras en regel ändras kravraden i samma PR, och testet
som bevisar den.

## Spårbarhet

- Varje krav ska kunna bevisas av ett test (regel 2). Skriv krav-ID i testnamnet eller en
  kommentar när det inte är uppenbart (`// ANA-4`).
- **Krav-ID:n står i PR-beskrivningen** – nya, ändrade, strukna och de som uppfylls.
- Krav om persisterad data och backup (NFR-1, NFR-3, TP-7, SET-6, SET-7) hänger ihop med skills
  `data-safety-backup` och `room-migrations`.
- Krav om utseende (UI) hänger ihop med skills `ui-style` och `shared-ui-components`.
- Krav om tester och CI (NFR-2) hänger ihop med skills `testing-strategy` och `ci-budget`.

## Historik-avsnittet

`## Historik` längst ned beskriver i löptext **bara rättade antaganden**: när ändringen visar
att något KRAVLISTA tidigare påstod var fel, eller river upp ett tidigare beslut. En ren nyhet
eller en vanlig ändring får ingen historikpost – det är vad git och README:s versionshistorik
är till för.

## Följdartefakter

- **README** – funktionslistan under "Status" bockas av när en funktion landar;
  arkitekturavsnittet uppdateras när en ny klass eller ett nytt paket tillkommit.
- **`versionName`** i `app/build.gradle.kts` följer med när funktionsomfånget ändras (CLAUDE.md
  regel 3). Själva releasen görs separat och bara på uttrycklig begäran.
- **Versionshistorik-tabellen i README** fylls vid releasen av skill `release`, inte av
  funktions-PR:en.

**Ändra aldrig KRAVLISTA.md på eget initiativ** utanför den PR som ändrar beteendet – en
omskrivning av befintliga rader är ett eget issue.

## Checklista

- [ ] Rätt tabell och ID-serie.
- [ ] Nytt ID / redigerat / helt struket med `~~…~~ *(borttaget)*`; `*(planerad)*` borttaget när det byggts.
- [ ] Inga ID:n återanvända eller raderade.
- [ ] Raden beskriver beteende – motiv och implementation ligger i PR/issue/README.
- [ ] Gemensamma beteenden refererade, inte upprepade.
- [ ] README och `versionName` uppdaterade om funktionsomfånget ändrats.
- [ ] Historikpost bara om ett tidigare antagande rättas.
- [ ] Ett test bevisar det nya/ändrade kravet.
- [ ] Krav-ID:n står i PR-beskrivningen.
