# Kravlista – Fonder (Android)

> App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut
> värde och visa utveckling i tabell och diagram, med molnbackup och Google-inloggning.
>
> Version: 0.7.4 · Paket: `se.partee71.fonder` · Språk: Svenska

---

## 1. Översikt och syfte

| ID | Krav |
|----|------|
| ÖV-1 | Appen ska låta användaren **bevaka fonder** och se innehav samlat i en portfölj. |
| ÖV-2 | Appen ska hämta **fondkurser (NAV) från Handelsbanken utan inloggning**, från `handelsbanken.fondlista.se` (beslutad i spike #2, implementerad i #3), cachat lokalt och uppdaterat dagligen. |
| ÖV-2b | Användaren ska kunna **söka bland fonder på namn och lägga till dem** i sin bevakning, filtrerat på valt **fondbolag** (dropdown, förvalt Handelsbanken). |
| ÖV-3 | Appen ska låta användaren **registrera fondtransaktioner** (köp/sälj) mot en redan bevakad fond, med förifylld kurs från senast kända NAV, samt ta bort en felregistrerad transaktion (bekräftelsedialog). |
| ÖV-4 | Appen ska räkna ut **nuvarande värde** utifrån transaktioner och senast kända kurs (issue #6). Historisk värdeutveckling i diagram är fortfarande *(planerad)*. |
| ÖV-5 | Appen ska visa fonders **utveckling i tabell och diagram** — kurshistorik senaste året i Fonddetalj (issue #7). |
| ÖV-6 | Appen ska fungera **offline-först**; data lagras lokalt och backas upp till molnet. |
| ÖV-7 | Hela gränssnittet ska vara på **svenska**. |
| ÖV-8 | Användaren ska kunna **importera befintliga fondinnehav** från Handelsbankens Excel-export ("Innehav Fonder") — automatisk fondmatchning och uppskattat inköpsdatum, med manuell bekräftelse/korrigering innan import (issue #8). |

---

## 2. Teknisk plattform (förutsättningar)

| ID | Krav |
|----|------|
| TP-1 | Android, **minSdk 30**, targetSdk 35, **compileSdk 36** (höjt för Vico-beroendet, issue #7), JDK 17. Paket `se.partee71.fonder`. |
| TP-2 | UI byggt med **Jetpack Compose** + Material 3. |
| TP-3 | Arkitektur: **MVVM** med Hilt (DI), Repository-mönster, ViewModels med `StateFlow`. |
| TP-4 | Lokal lagring i **Room** (`exportSchema = true`); inställningar i **DataStore (Preferences)**. |
| TP-5 | Bakgrundsjobb via **WorkManager** (Hilt-integrerad worker): daglig kursuppdatering för bevakade fonder. |
| TP-6 | Inloggning via **Firebase Auth + Google Credential Manager**. *(planerad)* |
| TP-7 | Molnbackup via **Google Drive (appDataFolder)**. *(planerad)* |
| TP-8 | Krävd behörighet: `INTERNET`. |
| TP-9 | Fondidentitet: **`FundId`** (Handelsbankens fondlista-plattforms egen kod, t.ex. `SHB0000442`) — källan exponerar inget ISIN. |
| TP-10 | Fondkurs-HTML parsas med **Jsoup**; HTTP via **OkHttp**. Parsern är isolerad (`HandelsbankenHtmlParser`) — se risknotis i #2/#3 (odokumenterad, inofficiell källa). |
| TP-11 | Fondbolag ↔ fond saknar maskinläsbar koppling i källan; appens **`FundCompanyMatcher`** approximerar kopplingen (Handelsbanken via `FundId`-prefix `SHB`, övriga bolag via namnprefix). Ungefärligt — se KDoc i koden. |
| TP-12 | Diagram med **Vico** (`com.patrykandpatrick.vico:compose-m3`), wrappat i delad `FundLineChart` (`ui/diagram/`) — resten av appen rör aldrig Vico-API:t direkt (regel 4). |
| TP-13 | Innehavsimport (`HoldingsImportParser`, `data/imports/`) parsar Handelsbankens "Innehav Fonder"-export utan extra bibliotek (ren DOM-parsning). Exporten visade sig i praktiken vara kalkylbladets råa XML direkt, inte en riktig zip-baserad `.xlsx` — parsern hanterar båda formaten (zip-magibyte avgör). Identifierar fonder med **ISIN**, till skillnad från appens `FundId` (TP-9); matchas mot katalogen på fondnamn (`FundNameMatcher`), med fondbolagsnamnet som ledtråd vid annars jämna träffar (via `FundCompanyMatcher`, TP-11) — inte ISIN. Inköpsdatum uppskattas mot fem års kurshistorik (`refresh()`); saknas en tillförlitlig träff antas datumet vara fem år tillbaka i tiden i stället för dagens datum. Isolerad, odokumenterat exportformat — se risknotis i #8. |

---

## 3. Utseende och tema

| ID | Krav |
|----|------|
| UI-1 | Visuell identitet **grön petrol** med mässings-/guldaccent, fast palett (ej dynamisk färg), ljust + mörkt tema. |
| UI-2 | Rubriker/UI i **Space Grotesk**; belopp och sifferkolumner med **tabulära siffror**. |
| UI-3 | Avkastning (vinst/förlust) visas med **semantisk färg + tecken/pil**, aldrig färg ensam. |
| UI-4 | Tema kan väljas: **Ljust / Mörkt / Auto** (sparas i DataStore). |

---

## 4. Navigation och skärmar (grund)

| ID | Krav |
|----|------|
| NAV-1 | Toppnivå med navigeringsrad: **Portfölj**, **Transaktioner**, **Inställningar**. |
| NAV-2 | Från Portfölj kan man öppna **Fonddetalj** — kurshistorik senaste året i diagram (`FundLineChart`, `ui/diagram/`) och tabell (datum + kurs), med tomt-tillstånd om ingen historik finns än. |
| NAV-3 | Från Portfölj kan man via en flytande knapp öppna **fondsök** och lägga till en fond i bevakningen, med **fondbolags-filter** (dropdown, förvalt Handelsbanken, "Alla fondbolag" som alternativ). |
| NAV-4 | Från Transaktioner kan man via en flytande knapp öppna **transaktionsformuläret** (fond, köp/sälj, datum, antal andelar, kurs/andel) — endast bland redan bevakade fonder. Utan bevakade fonder visas ett tomt-tillstånd som pekar till fondsök. |
| POR-1 | Portföljen visar innehav per fond och **totalt nettoinvesterat belopp**. |
| POR-2 | Tom portfölj visar ett tomt-tillstånd som uppmanar att lägga till en transaktion. |
| POR-3 | Har en fond känd kurs visas **nuvarande värde och vinst/förlust** (kr + %, semantisk färg) per innehav och totalt, i stället för nettoinvesterat. Saknas kurs visas nettoinvesterat + texten "Kurs saknas ännu" — aldrig ett felaktigt eller krashande värde (issue #6). |
| POR-4 | Läggs en fond utan cachad kurs till bevakningen hämtas dess kurs automatiskt en gång (utöver den dagliga bakgrundsuppdateringen, TP-5). |
| TRX-1 | Transaktionslistan visar fondnamn, köp/sälj, datum, antal andelar och kurs/andel per rad. |
| TRX-2 | Långtryck på en transaktionsrad visar en bekräftelsedialog innan den tas bort permanent. |
| IMP-1 | Från Inställningar kan man öppna **Importera innehav**: väljer en `.xlsx`-fil (Handelsbankens "Innehav Fonder"-export), granskar/korrigerar föreslagen fondmatchning och uppskattat inköpsdatum per rad, väljer bort enskilda rader, och importerar de bekräftade raderna som transaktioner (ÖV-8). |
| IMP-2 | Rader med osäker fondmatchning eller osäkert uppskattat inköpsdatum markeras tydligt (text, inte enbart färg) — användaren väljer fond/datum manuellt (samma delade komponenter som transaktionsformuläret, regel 4). |
| IMP-3 | En importrad kan delas upp i **flera inköpstillfällen** (eget datum + antal andelar per tillfälle) eftersom en exportrad ofta är ett aggregerat innehav byggt av flera köp — varje tillfälle blir en egen transaktion vid import. Summan av tillfällenas andelar måste matcha radens totala antal (tydlig felmarkering annars) innan raden går att importera. |
| IMP-4 | Under fondmatchning och kursuppdatering (kan ta en stund) visas en overlay-modal ("Importerar…") som inte kan avfärdas förrän steget är klart — ingen tom eller ointeraktiv vy under tiden. |

---

## 5. Datasäkerhet och tester (icke förhandlingsbart)

| ID | Krav |
|----|------|
| NFR-1 | All persisterad användardata ska överleva en **backup → restore-rundtur** utan förlust. *(backup planerad)* |
| NFR-2 | Ingen beteendeändring utan **tester** på berörd nivå (enhet/instrument/migrering). |
| NFR-3 | Room-schemaändring kräver **migrering** utan dataförlust. |

---

## Följdkrav (planerade — se GitHub-issues)

Drive-backup och Google-inloggning läggs till som egna krav i respektive avsnitt när de
implementeras — väntar på att ett Firebase-projekt sätts upp för fonder (`google-services.json`).

## Historik

- **Kurskälla (#2, #3):** Handelsbankens fondkurser hämtas från `handelsbanken.fondlista.se`
  (offentlig, ingen inloggning). Identitet: plattformens `FundId`, inte ISIN — se TP-9.
  Cache i Room, daglig uppdatering via WorkManager (TP-5), fondsök i UI (NAV-3, ÖV-2b).
- **Fondbolagsfilter (#3-uppföljning):** sidans eget "Fondbolag"-filter visade sig inte
  filtrera fondlistan i praktiken (verifierat manuellt) — appen bygger därför en egen
  klient-side-filtrering (`FundCompanyMatcher`, TP-11) istället för att lita på servern.
- **Fondtransaktioner (#4):** ÖV-3 klar — transaktionsformulär (NAV-4), delade
  komponenter `SelectField`/`DateField` i `ui/components/` (regel 4), förbättrad
  transaktionsrad och ta-bort-flöde (TRX-1, TRX-2).
- **Värdeberäkning (#6):** ÖV-4 delvis klar — Portfölj visar nuvarande värde och
  vinst/förlust per innehav och totalt när kursen är känd, med grafiskt tydlig
  "kurs saknas ännu"-fallback annars (POR-3). Kurser hålls reaktiva via
  `FundPriceDao.observeLatest` (Room `Flow`) genom repository och ViewModel, så
  portföljen uppdateras direkt när en kurs cachas (POR-4). Historisk
  värdeutveckling/diagram är fortsatt planerat.
- **Diagram/kurshistorik (#7):** ÖV-5 klar — Fonddetalj visar senaste årets kurshistorik
  i diagram (`FundLineChart`, TP-12) och tabell (NAV-2), reaktivt via en ny
  `FundPriceDao.observeRange`/`observePriceHistory`-kedja (samma reaktiva mönster som
  #6). Google Drive-backup och Google-inloggning (kvarstår i följdkraven) väntar på att
  ett Firebase-projekt sätts upp för fonder.
- **Innehavsimport (#8):** ÖV-8 klar — import av Handelsbankens "Innehav Fonder"-Excel
  (IMP-1, IMP-2, TP-13). Exportens `.xlsx` identifierar fonder med ISIN, som saknas i
  fondlista-katalogen (TP-9) — `FundNameMatcher` föreslår i stället en katalogfond genom
  ordbaserad namnlikhet, och `PurchaseDateEstimator` uppskattar inköpsdatum genom att
  hitta dagen i kurshistoriken vars NAV ligger närmast radens snittkurs
  (anskaffningsvärde / antal). Båda kräver alltid en bekräftelse/korrigering-vy innan
  import — automatiken kan missa fonder som inte säljs via Handelsbankens plattform, och
  kurshistoriken räcker bara fem år tillbaka (se uppföljning nedan).
- **Flera inköpstillfällen per importrad (#8-uppföljning):** en exportrad är ofta ett
  aggregerat innehav byggt upp av flera köp vid olika tidpunkter, inte ett enda köp — att
  alltid skapa exakt en transaktion per rad gav en missvisande historik (IMP-3). Raden kan
  nu delas i flera inköpstillfällen (`ImportOccasion`), vart och ett med eget
  (uppskattat eller manuellt) datum och antal andelar; samma snittkurs (radens
  anskaffningsvärde / totalt antal) används för alla tillfällen eftersom källfilen bara
  ger en aggregerad kostnad. Summan av tillfällenas andelar valideras mot radens totala
  antal innan import tillåts för raden.
- **Fullständig kursuppdatering vid import (#8-uppföljning):** en matchad fond som redan
  bevakades sedan tidigare kunde ha en ofullständig cachad kurshistorik (t.ex. bara de
  senaste dagarna via den dagliga bakgrundsuppdateringen), vilket gjorde
  `PurchaseDateEstimator` mindre träffsäker. `refresh(fundId)` (senaste fem årens kurser)
  anropas därför alltid vid import för varje matchad rad, oavsett om fonden redan har en
  cachad kurs — inte bara första gången en fond läggs till (TP-13, jämför POR-4).
- **Stöd för ozippad exportfil (#8-uppföljning):** Handelsbankens faktiska export visade
  sig inte vara en riktig zip-baserad `.xlsx` (OOXML), utan bara kalkylbladets råa XML
  (samma schema som `xl/worksheets/sheet1.xml` i en uppackad `.xlsx`, aldrig zippad).
  `HoldingsImportParser.parse` avgör format via zip-magibytena och tolkar råXML direkt om
  filen inte är zippad (TP-13); filväljaren accepterar nu både xlsx- och XML-mimetyper.
- **Fem års kurshistorik, bättre fondmatchning och importmodal (#8-uppföljning):**
  `refresh()` hämtar nu fem års kurser i stället för ett (TP-13) — mer historik för
  `PurchaseDateEstimator` att söka i, särskilt för äldre innehav. Saknas en tillförlitlig
  träff antas inköpsdatumet vara fem år tillbaka i tiden i stället för dagens datum (samma
  gräns som söks inom, så gissningen aldrig hamnar utanför fönstret). `FundNameMatcher`
  använder nu även exportradens fondbolagsnamn som ledtråd (via `FundCompanyMatcher`,
  TP-11) — ger ett litet försprång åt kandidater från rätt bolag vid annars jämna
  namnträffar, utan att utesluta andra kandidater. En overlay-modal (IMP-4) visas medan
  matchning/kursuppdatering pågår, eftersom fem års historik per fond kan ta en stund att
  hämta.
