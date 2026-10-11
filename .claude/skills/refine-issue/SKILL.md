---
name: refine-issue
description: 'Turn a rough idea, bug report, or feature request into a well-refined, planned GitHub issue for Fonder — clarify intent, research the codebase, define scope and acceptance criteria grounded in the project''s five non-negotiable rules, make a mockup for new GUI, name Agent · modell · effort, and create or update the issue on GitHub after confirmation. Use when the user wants to "create an issue", "update an issue", "plan a feature", "write up a bug", "refine this idea into a ticket". Svenska trigger-ord: skapa issue, nytt issue, uppdatera issue, planera, förfina, skriv upp en bugg, idé, önskemål, ticket. Pairs with implement-issue.'
---

# Förfina och planera ett issue

Gör en halvfärdig idé till ett GitHub-issue som en annan session (med `implement-issue`)
kan genomföra utan fler frågor.

Gå igenom stegen i ordning. Samla frågor, förhör inte. **Skapa eller uppdatera inte
issuet förrän i steg 7.**

Definition of Done (steg 5) vilar på de fem reglerna i [CLAUDE.md](../../../CLAUDE.md).
Ett förfinat issue gör reglerna konkreta för *just den här* ändringen.

### Nytt eller befintligt issue
- **Inget nummer** → alla steg, skapa nytt issue i steg 7.
- **Nummer angivet** → hämta med `mcp__github__issue_read` (owner `ParTee71`, repo `fonder`),
  visa titel och innehåll, förfina och **uppdatera** i steg 7.

---

## Steg 1 – Fånga idén

Återge begäran i en eller två meningar. Klassa den:

| Typ | Signaler |
|---|---|
| **Funktion** | ny skärm, ny förmåga, "lägg till", "stöd för" |
| **Bugg** | "fel", "kraschar", "räknar fel", "fungerar inte" |
| **Underhåll** | refaktorering, beroende, bygg/CI, städning, ingen synlig ändring |

Sök efter dubbletter först: `mcp__github__search_issues` (`repo:ParTee71/fonder <ord>`).

## Steg 2 – Klargör avsikt

Fråga bara det som inte går att härleda ur begäran eller koden. `AskUserQuestion` vid
konkreta val. Bugg: steg, förväntat/faktiskt, enhet/Android-version, logcat. Hoppa över
steget om begäran redan är entydig.

## Steg 3 – Undersök koden

- Vilka skärmar (`ui/hem`, `ui/portfolj`, `ui/fond`, `ui/fondsok`, `ui/transaktioner`,
  `ui/imports`, `ui/facit`, `ui/bytesfonster`, `ui/riskprofil`, `ui/settings`), ViewModels,
  repositories, use cases (`domain/usecase/`) och workers berörs?
- **Återbruk (regel 4):** vilka komponenter och byggstenar ur skill `shared-ui-components`
  används? Berörs en känd dubblett (`TotalCard`, `ComparisonChart`, importens
  laddningsdialoger, egna listrader)? Då slås den ihop först. Behövs något **nytt delat**? Då
  byggs det först.
- **Data (regel 1):** nytt eller ändrat persisterat fält eller ny entitet? Då krävs Room-migrering
  med `MigrationXYTest` (skill `room-migrations`) och plats i `BackupPayload`, fältvakten och
  rundturen (skill `data-safety-backup`) – eller ett uttryckligt motiv till att det är cache.
- **Externa källor:** `data/network/` (Avanza, Handelsbanken, Riksbanken) är odokumenterade
  (TP-10, TP-14) – flagga skörheten.
- **Import:** `data/imports/` (`HoldingsImportParser`, `AvrakningsnotaPdfParser`) och
  matchningen (`FundCompanyMatcher`, `FundNameMatcher`, `ImportFundMatcher`)?
- **Inloggning och molnbackup:** `AuthRepository` (TP-6), `DriveBackupRepository` (TP-7 steg 2,
  SET-7) och påminnelsen `BackupStatusRepository` (HEM-12)?
- **Krav (regel 3):** vilka ID:n i KRAVLISTA.md berörs, eller behövs nya?
- **Tester (regel 2):** vilka tester finns i dag, vilka nivåer berörs?
- **CI (regel 5):** nytt beroende eller nytt steg? Byggtidseffekt?
- **Agent:** vilken del tar `byggare`, `arkitekt`, `testskrivare` (steg 6)?

Anteckna konkreta filsökvägar.

## Steg 4 – Mockup (endast ny GUI)

Syns förändringen i en tumnagel (ny skärm, ny delad komponent, tydligt ändrat
utseende/beteende): bygg mockupen nu (skill `mockup`) av befintliga komponenter, i Fonders
designspråk, med svenska texter och påhittad data – aldrig användarens riktiga innehav. Visa
länken och vänta på ok innan issuet skapas. Länken går in under **Design**. Padding, ordval
eller ikonbyte → ingen mockup.

## Steg 5 – Omfång och Definition of Done

- **Inom omfång** – vad issuet levererar.
- **Utanför omfång** – vad det medvetet inte gör.
- **Acceptanskriterier** – observerbara, testbara punkter
  (`- [ ] Sparar en transaktion och den finns kvar efter omstart`).

### Definition of Done – de fem reglerna konkret
1. **Datasäkerhet** – entitet, `version`, migrering + `MigrationXYTest`; fältet i
   `BackupPayload` med default, i `export()`/`restore()`, fältvakten i `BackupSerializerTest`
   uppdaterad och `BackupRoundTripTest` asserterar på det; eller "ingen persisterad ändring",
   eller motivet till att det är cache.
2. **Tester** – nivåer och klasser; bugg → regressionstest som faller före fixen.
3. **Krav** – ID:n att lägga till, ändra eller stryka (skill `requirements-kravlista`).
4. **Enhetligt** – vilka delade delar som används, vad nytt som byggs delat, vilken dubblett
   som slås ihop.
5. **CI-budget** – nya beroenden och deras byggtidseffekt; eller "ingen påverkan".

Plus **mockup** (Design) för ny GUI, **integritet** (logga aldrig innehav, belopp, tokens eller
hela HTTP-svar) och **tillgänglighet** (`contentDescription`, 48 dp) där det är relevant. Gäller
en regel inte, skriv varför. Stort arbete → föreslå uppdelning i under-issues
(`mcp__github__sub_issue_write`), var och en med egen DoD.

## Steg 6 – Utkast

```markdown
## Sammanfattning
<vad och varför>

## Design
<mockup-länk, eller "ingen GUI-ändring">

## Föreslaget upplägg
1. …

## Återbruk (regel 4)
- Komponenter: …
- Use cases/byggstenar: …
- Dubblett som slås ihop: … (eller "ingen")
- Nytt delat som byggs först: … (eller "inget")

## Berörda filer
- `app/src/main/kotlin/se/partee71/fonder/…`

## Datasäkerhet
<entitet/fält, migrering, BackupPayload och rundtur – eller "ingen persisterad ändring">

## Krav (KRAVLISTA.md)
- <ID och radtext>

## Testplan
- Enhet (`app/src/test`): …
- Instrument (`app/src/androidTest`): … (Compose, DAO, migrering, rundtur)

## Acceptanskriterier
- [ ] <beteende>
- [ ] Mockup godkänd (om GUI)
- [ ] Migrering + `MigrationXYTest` (om schemat ändras)
- [ ] Backup-kedjan + rundturstest (om användardata)
- [ ] KRAVLISTA uppdaterad
- [ ] Tester tillagda/uppdaterade och gröna

## Utanför omfång
- …

## Risker / Öppna frågor
- <migreringsrisk, skör extern källa, eller "inga">

## Agent · modell · effort
- <agent> · <modell> · <effort> – <vilken del> (t.ex. `arkitekt` · Opus · high – migrering och
  backup-kedjan; `byggare` · Opus · medium – skärmen)
```

För **buggar**: byt Föreslaget upplägg mot **Steg för att återskapa**, **Förväntat**,
**Faktiskt**, **Miljö**, och kräv regressionstestet.

**Agent · modell · effort är obligatoriskt.** Varje del anger agent (`byggare`, `arkitekt`,
`testskrivare` …), modell och effort, utifrån agenttabellen i CLAUDE.md; Room-schema,
migreringar och backup-kedjan minst `arkitekt` · high. Ett issue utan avsnittet är inte
färdigförfinat.

Titel: `<område>: <sammanfattning i imperativ>` (t.ex. `Transaktioner: bekräftelsedialog vid
borttagning av rad`). Visa hela utkastet och **vänta på bekräftelse**. Fråga om etiketter
(hämta repots verkliga etiketter om du är osäker).

## Steg 7 – Skapa eller uppdatera

- **Nytt:** `mcp__github__issue_write` (method `create`, owner `ParTee71`, repo `fonder`,
  title, body, labels). Vid uppdelning: förälder först, sedan varje under-issue med
  `mcp__github__sub_issue_write`.
- **Befintligt:** `method: update` med hela den förfinade texten – lägg inte till i slutet.
  Behåll etiketterna om användaren inte vill ändra dem.

Rapportera nummer och länk.

## Steg 8 – Nästa steg

Erbjud kort: genomför nu (`implement-issue`), skapa uppföljande issues för det som låg
utanför omfånget, eller låt det ligga planerat.
