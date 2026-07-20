# Kravlista – Fonder (Android)

> App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut
> värde och visa utveckling i tabell och diagram, med molnbackup och Google-inloggning.
>
> Version: 0.19.0 · Paket: `se.partee71.fonder` · Språk: Svenska

---

## 1. Översikt och syfte

| ID | Krav |
|----|------|
| ÖV-1 | Appen ska låta användaren **bevaka fonder** och se innehav samlat i en portfölj. |
| ÖV-2 | Appen ska hämta **fondkurser (NAV) från Handelsbanken utan inloggning**, från `handelsbanken.fondlista.se` (beslutad i spike #2, implementerad i #3), cachat lokalt och uppdaterat dagligen. |
| ÖV-2b | Användaren ska kunna **söka bland fonder på namn och lägga till dem** i sin bevakning, filtrerat på valt **fondbolag** (dropdown, förvalt Handelsbanken). |
| ÖV-3 | Appen ska låta användaren **registrera fondtransaktioner** (köp/sälj) mot en redan bevakad fond, med förifylld kurs från senast kända NAV, valfri **avgift** (kr, default 0), samt ta bort en felregistrerad transaktion (bekräftelsedialog). |
| ÖV-4 | Appen ska räkna ut **nuvarande värde** utifrån transaktioner och senast kända kurs (issue #6). Historisk värdeutveckling i diagram är fortfarande *(planerad)*. |
| ÖV-5 | Appen ska visa fonders **utveckling i tabell och diagram** — kurshistorik sedan det första köpet i Fonddetalj, inte bara senaste året (issue #7, utökat i #7-uppföljning via ISIN-baserad historik, TP-14). |
| ÖV-6 | Appen ska fungera **offline-först**; data lagras lokalt och backas upp till molnet. |
| ÖV-7 | Hela gränssnittet ska vara på **svenska**. |
| ÖV-8 | Användaren ska kunna **importera befintliga fondinnehav** från Handelsbankens Excel-export ("Innehav Fonder") — automatisk fondmatchning och uppskattat inköpsdatum, med manuell bekräftelse/korrigering innan import (issue #8). |
| ÖV-9 | Appen ska räkna ut **realiserat resultat per säljtransaktion** (manuell eller importerad), med anskaffningskostnad matchad enligt **FIFO** (äldsta köpet säljs först, en delförsäljning kan konsumera flera köp-lotter) och en eventuell **avgift** på säljtransaktionen avdragen, och visa det i en egen vy för sålda fonder (issue #10). |
| ÖV-10 | Användaren ska kunna **importera exakta fondtransaktioner** från Handelsbankens PDF-avräkningsnotor (orderbekräftelser) — en eller flera samtidigt. Till skillnad från ÖV-8 (aggregerad innehavssnapshot, uppskattat datum) är datum/kurs/antal här redan exakta; bara fondmatchningen kan behöva bekräftas (issue #8-uppföljning). |

---

## 2. Teknisk plattform (förutsättningar)

| ID | Krav |
|----|------|
| TP-1 | Android, **minSdk 30**, targetSdk 35, **compileSdk 36** (höjt för Vico-beroendet, issue #7), JDK 17. Paket `se.partee71.fonder`. |
| TP-2 | UI byggt med **Jetpack Compose** + Material 3. |
| TP-3 | Arkitektur: **MVVM** med Hilt (DI), Repository-mönster, ViewModels med `StateFlow`. |
| TP-4 | Lokal lagring i **Room** (`exportSchema = true`); inställningar i **DataStore (Preferences)**. |
| TP-5 | Bakgrundsjobb via **WorkManager** (Hilt-integrerad worker): handelsdagsmedveten kursuppdatering för bevakade fonder — en billig launch-gate vid appstart plus en gles periodisk backstop (12h), båda gated av staleness (se TP-17, issue #27; ersätter den tidigare fasta 24h-periodiken). |
| TP-6 | Inloggning via **Firebase Auth + Google Credential Manager**. *(planerad)* |
| TP-7 | Molnbackup via **Google Drive (appDataFolder)**. *(planerad)* |
| TP-8 | Krävd behörighet: `INTERNET`. |
| TP-9 | Fondidentitet: **`FundId`** (Handelsbankens fondlista-plattforms egen kod, t.ex. `SHB0000442`) — källan exponerar inget ISIN. |
| TP-10 | Fondkurs-HTML parsas med **Jsoup**; HTTP via **OkHttp**. Parsern är isolerad (`HandelsbankenHtmlParser`) — se risknotis i #2/#3 (odokumenterad, inofficiell källa). |
| TP-11 | Fondbolag ↔ fond saknar maskinläsbar koppling i källan; appens **`FundCompanyMatcher`** approximerar kopplingen (Handelsbanken via `FundId`-prefix `SHB`, övriga bolag via namnprefix). Ungefärligt — se KDoc i koden. |
| TP-12 | Diagram med **Vico** (`com.patrykandpatrick.vico:compose-m3`), wrappat i delad `FundLineChart` (`ui/diagram/`) — resten av appen rör aldrig Vico-API:t direkt (regel 4). |
| TP-13 | Innehavsimport (`HoldingsImportParser`, `data/imports/`) parsar Handelsbankens "Innehav Fonder"-export utan extra bibliotek (ren DOM-parsning). Exporten visade sig i praktiken vara kalkylbladets råa XML direkt, inte en riktig zip-baserad `.xlsx` — parsern hanterar båda formaten (zip-magibyte avgör). Identifierar fonder med **ISIN**, till skillnad från appens `FundId` (TP-9). Fondmatchning sker i prioritetsordning (#8-uppföljning): (1) redan bevakad fond med samma ISIN, (2) exakt ISIN-träff via `FundPriceRepository.findFundByIsin` (TP-14 — täcker fondbolag som saknas i Handelsbankens katalog, t.ex. AMF/Amundi/Franklin Templeton/Nordea, och undviker fel andelsklass), (3) `FundNameMatcher` mot Handelsbankens katalog på fondnamn som sista utväg, med fondbolagets **kärnnamn** (`FundCompanyMatcher.coreBrandName`) som ledtråd. Inköpsdatum uppskattas mot kurshistorik (`refresh()`/`refreshSince()`) — fem år tillbaka för fonder utan ISIN (Handelsbankens fasta fönster), men **30 år** för ISIN-matchade fonder (Avanza har normalt betydligt längre historik, se TP-14; upplösningen trappas ner ju längre tillbaka — dagligt/veckovis/månadsvis — men ger ändå en reell chans att hitta äldre köp). Saknas en tillförlitlig träff antas datumet vara sökfönstrets början i stället för dagens datum. Isolerad, odokumenterat exportformat — se risknotis i #8. |
| TP-14 | Fonder har ett valfritt **`isin`**-fält (Room-migrering 2→3, nullable) utöver `FundId` (TP-9), för att hämta kurshistorik **sedan första köpet** — inte begränsat av Handelsbankens fasta 5-årsfönster. Källa: **Avanzas odokumenterade fond-API** (`_api/fund-guide/search` för ISIN/namn → `orderbookId`, `_api/fund-guide/guide` för valuta, `_api/fund-guide/chart/{orderbookId}/{from}/{to}?raw=true` för daglig NAV, godtyckligt datumintervall) — ingen inloggning krävs, verifierat live 2026-07-05. Isolerad i `data/network` (`AvanzaSource`/`AvanzaClient`/`AvanzaJsonParser`/`AvanzaPriceSource`), samma riskprofil som TP-10 (odokumenterad källa, kan sluta fungera utan förvarning). `FundPriceRepository.refreshSince`/`suggestIsin`/`findFundByIsin` provar en **prioritetsordnad lista** av `IsinPriceHistorySource` (i dag bara Avanza) — Nordnet och Morningstar undersöktes men saknade en bekräftat inloggningsfri sökväg från ISIN till en identifierare, och är därför inte implementerade. Fonder tillagda via import får ISIN direkt från exportfilen (TP-13); fonder tillagda via fondsök saknar ISIN tills ett föreslås (namnsökning mot samma källa) och bekräftas av användaren i Fonddetalj. `findFundByIsin` slår upp en fond exakt via ISIN och ger den `Fund.fundId == isin` (inget Handelsbanken-FundId finns) — används av importflödet (TP-13) för fonder som saknas i Handelsbankens katalog. |
| TP-15 | Realiserat resultat vid försäljning beräknas av **`RealizedGainCalculator`** (`domain/usecase/`), en ren/testbar FIFO-motor (äldsta köp-lott konsumeras först) som delas mellan "Sålda fonder"-vyn (`compute`, en post per säljtransaktion) och portföljens kvarvarande anskaffningsvärde (`remainingPositions`, POR-1) — samma sanning på båda ställena. `Transaction` har ett fält **`fee`** (avgift i kr, default 0.0, Room-migrering 3→4) som dras av från säljtransaktionens eget resultat; avgift på köp räknas **inte** in i anskaffningsvärdet (medvetet avgränsat till sälj-sidan). Säljs fler andelar än historiken visar köpta flaggas den delen som `uncoveredShares` — resultatet visas ändå, men markerat som osäkert (SLD-2), i stället för att tystas ner eller gissas. |
| TP-16 | PDF-textextraktion via **PdfBox-Android** (`com.tom-roush:pdfbox-android`, Apache 2.0-licens) — kräver `PDFBoxResourceLoader.init(context)` (körs i `FonderApp.onCreate`). Abstraherat bakom `PdfTextExtractor` (`data/imports/`) så `AvrakningsnotaPdfParser` (ren textparsning, samma isoleringsprincip som TP-10/TP-13) kan enhetstestas med fixturer utan PDF-biblioteket. Layouten är odokumenterad och kan ändras — parsern matchar rader som börjar med **"In" (köp) eller "Ut"/"Avslut" (sälj)** följt av datum och fyra tal (belopp, kurs, andelar, saldo), verifierat mot en riktig köp- **och** en riktig sälj-avräkningsnota (se changelog). En sälj-rad har negativa belopp/andelar (minskar saldot) — parsern sparar magnituden, `type` bär riktningen. PdfBox-Androids egen radbrytning i praktiken kan ändå avvika något. Fondmatchning återanvänder samma prioritetsordning som TP-13 via den delade `ImportFundMatcher` (regel 4). |
| TP-17 | **`NavCalendar.expectedLatestNavDay`** (`domain/usecase/`, ren/testbar) avgör vilket datums NAV som borde vara känt — helg → senaste vardagen, vardag före kl. 18 → föregående vardag, annars dagens datum. Enda staleness-sanning (`FundPriceRepository.isPriceStale`, regel 4), ersätter den tidigare "senaste kurs < idag"-jämförelsen som gav falska hämtningar på helger och falskt "färskt" på kvällar. `FundPriceRefreshScheduler` (`worker/`) koalescerar tre triggers till samma `FundPriceUpdateWorker` via WorkManagers unika arbetsnamn: en launch-gate vid appstart (`KEEP`), en gles periodisk backstop (`UPDATE`, 12h, TP-5), och en manuell forcerad körning (`REPLACE`) från Inställningar (SET-2). `FundPriceUpdateWorker.refreshAll` hoppar över redan aktuella fonder om inte forcerad — gör backstopen och launch-gaten billiga (inget nätverksanrop när kursen redan är färsk). Senaste lyckade körning sparas som tidsstämpel i `PreferencesRepository` — ren cache-metadata, ingen persisterad användardata (NFR-1) och ingår därför medvetet inte i backup-kontraktet. |

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
| NAV-1 | Toppnivå med navigeringsrad: **Hem**, **Portfölj**, **Transaktioner**, **Sålda**, **Inställningar**. Hem är startskärmen (issue #14). |
| NAV-2 | Från Portfölj kan man öppna **Fonddetalj** — kurshistorik sedan första köpet i diagram (`FundLineChart`, `ui/diagram/`) och tabell (datum + kurs), med tomt-tillstånd om ingen historik finns än. Saknar fonden ISIN visas ett fält för att ange/bekräfta det (förifyllt med ett namnbaserat förslag om ett hittades), se TP-14. |
| NAV-3 | Från Portfölj kan man via en flytande knapp öppna **fondsök** och lägga till en fond i bevakningen, med **fondbolags-filter** (dropdown, förvalt Handelsbanken, "Alla fondbolag" som alternativ). |
| NAV-4 | Från Transaktioner kan man via en flytande knapp öppna **transaktionsformuläret** (fond, köp/sälj, datum, antal andelar, kurs/andel) — endast bland redan bevakade fonder. Utan bevakade fonder visas ett tomt-tillstånd som pekar till fondsök. |
| NAV-5 | En egen flik **Sålda** i toppnavigeringen öppnar vyn över sålda fonder (se avsnitt 6, SLD-1). |
| NAV-6 | Navigeringschromets `TopAppBar` visar en liten **bakgrundsindikator** (`WorkerStatusIcon`, `ui/components/`, regel 4) när en kursuppdatering pågår i bakgrunden (TP-5/TP-17, issue #27) — aldrig blockerande, döljs helt i vila. |
| POR-1 | Portföljen visar innehav per fond och **totalt nettoinvesterat belopp** — nettoinvesterat är det kvarvarande (ej sålda) anskaffningsvärdet enligt FIFO (TP-15), inte kassaflödet. En fond vars nettoandelar är noll (**helt avsåld**) är inte längre ett innehav och visas inte — dess realiserade resultat finns i stället i "Sålda fonder" (SLD-1). |
| POR-2 | Tom portfölj visar ett tomt-tillstånd som uppmanar att lägga till en transaktion. |
| POR-3 | Har en fond känd kurs visas **nuvarande värde och vinst/förlust** (kr + %, semantisk färg) per innehav och totalt, i stället för nettoinvesterat. Saknas kurs visas nettoinvesterat + texten "Kurs saknas ännu" — aldrig ett felaktigt eller krashande värde (issue #6). |
| POR-4 | Läggs en fond utan cachad kurs till bevakningen hämtas dess kurs automatiskt en gång (utöver den dagliga bakgrundsuppdateringen, TP-5). |
| POR-5 | Portföljens innehavsrader visar även **dag-, vecka- och månadsförändring** per fond (kr + %), utöver nuvarande värde/vinst (POR-3). Räcker inte kurshistoriken tillbaka till periodens start (t.ex. nytillagd fond) markeras just den perioden som otillräcklig data i stället för ett gissat värde (issue #14). Är fondens senast kända kurs **äldre än periodens start** (t.ex. innan dagens kursuppdatering hunnit köras) visas i stället texten "Kurs ej uppdaterad" — aldrig ett missvisande `0` (issue #18). |
| POR-6 | Varje innehavsrad visar **datum för första köp** och det kvarvarande FIFO-anskaffningsvärdet ("Inköpsvärde", TP-15) för fonden, utöver nuvarande värde/vinst (issue #18). Samma information visas överst i Fonddetalj för fonder som är kvarvarande innehav. |
| POR-7 | Totalkortet och varje innehavsrad i Portfölj och Hem visar **"Värde per \<datum\>"** — NAV-datumet värdet är räknat på (`ValueAsOfRow`, `ui/components/`, regel 4), diskret under värdet. Totalens datum är det **äldsta** bland de ingående innehavens NAV (samma "svagaste länk"-princip som "delvis osäker", HEM-2) — gör en normal endagsförskjutning mot en extern källa (t.ex. banken) begriplig i stället för att se ut som ett fel (issue #27). Visas inget om värdet är okänt. |
| POR-8 | Varje innehavsrad i Portfölj visar den befintliga säljsignal-statusen (`StatusDot`, ANA-3) och en ev. triggad vinstsignal (ANA-8) direkt på kortet, utan att behöva öppna Fonddetalj (issue #26). Visas inget om analysen saknar tillräcklig data (ANA-4). |
| TRX-1 | Transaktionslistan visar fondnamn, köp/sälj, datum, antal andelar och kurs/andel per rad. |
| TRX-2 | Långtryck på en transaktionsrad visar en bekräftelsedialog innan den tas bort permanent. |
| IMP-1 | Från Inställningar kan man öppna **Importera innehav**: väljer en `.xlsx`-fil (Handelsbankens "Innehav Fonder"-export), granskar/korrigerar föreslagen fondmatchning och uppskattat inköpsdatum per rad, väljer bort enskilda rader, och importerar de bekräftade raderna som transaktioner (ÖV-8). |
| IMP-2 | Rader med osäker fondmatchning eller osäkert uppskattat inköpsdatum markeras tydligt (text, inte enbart färg) — användaren väljer fond/datum manuellt (samma delade komponenter som transaktionsformuläret, regel 4). |
| IMP-3 | En importrad kan delas upp i **flera inköpstillfällen** (eget datum + antal andelar per tillfälle) eftersom en exportrad ofta är ett aggregerat innehav byggt av flera köp — varje tillfälle blir en egen transaktion vid import. Summan av tillfällenas andelar måste matcha radens totala antal (tydlig felmarkering annars) innan raden går att importera. |
| IMP-4 | Under fondmatchning och kursuppdatering (kan ta en stund) visas en overlay-modal ("Importerar…") som inte kan avfärdas förrän steget är klart — ingen tom eller ointeraktiv vy under tiden. Kursuppdatering hämtas bara för en fond vars cachade kurs faktiskt är inaktuell (samma princip som POR-4/POR-5, issue #19) — en redan bevakad fond med färsk kurs gör importet snabbare i stället för att hämta om hela historiken i onödan. |
| IMP-5 | Varje inköpstillfälle i en importrad kan sättas till **Köp** eller **Sälj** (samma val som transaktionsformuläret) i stället för att alltid antas vara ett köp. |
| IMP-6 | Från Inställningar kan man öppna **Importera avräkningsnotor**: väljer en eller flera PDF-filer samtidigt (SAF-filväljare med flerval), varje fil tolkas till en eller flera exakta transaktioner (datum, kurs, antal andelar — inget uppskattat), granskar/korrigerar föreslagen fondmatchning per transaktion, och importerar de bekräftade transaktionerna (ÖV-10). |
| IMP-7 | Filer som inte kan tolkas alls (t.ex. inte en avräkningsnota) räknas upp tydligt i stället för att tystas ner eller krascha importet — övriga filers transaktioner importeras ändå. |
| IMP-8 | Osäker fondmatchning markeras tydligt (samma princip som IMP-2) — användaren väljer fond manuellt bland Handelsbankens katalog. Datum/kurs/antal andelar är redan exakta från notan, men kan ändå korrigeras manuellt om tolkningen skulle träffa fel. Matchade transaktioner triggar nu även en kursuppdatering för fonden (samma "bara om inaktuell"-princip som IMP-4) — misslyckas den markeras raden ("Kurs kunde inte hämtas") i stället för att tystas ner (issue #19). |
| IMP-9 | När import är klar visas en stängbar modal (titel + antal importerade poster + en tydlig **Stäng**-knapp) i stället för en fullskärms tom-tillståndsvy — stängning återgår till Inställningar. Gäller båda importflödena (issue #19). |
| SET-1 | Från Inställningar kan man **tömma hela databasen** (alla fonder, transaktioner och cachade kurser) i en tydligt markerad "farozon", bakom en bekräftelsedialog. Irreversibelt — molnbackup (TP-7) är ännu inte byggt, så det finns inget sätt att återställa data efter en tömning. |
| SET-2 | Inställningar visar ett **kursuppdateringskort** med "Senast uppdaterad: \<tidsstämpel\>" (eller "Aldrig uppdaterad") och en **"Uppdatera nu"-knapp** som forcerar en kursuppdatering oavsett staleness-gate (TP-17, issue #27) — bypassar launch-gate/backstopens "bara om inaktuellt"-princip, för den som inte vill vänta. |

---

## 5. Datasäkerhet och tester (icke förhandlingsbart)

| ID | Krav |
|----|------|
| NFR-1 | All persisterad användardata ska överleva en **backup → restore-rundtur** utan förlust. Androids inbyggda **Auto Backup** (Google-kontots molnlagring) är påslaget som interimistiskt skydd (`allowBackup="true"`) tills en egen, testbar Drive-backup (TP-7) finns — täcker en förlorad/nollställd enhet, men är inte en app-styrd rundtur. *(fullständig Drive-backup planerad)* |
| NFR-2 | Ingen beteendeändring utan **tester** på berörd nivå (enhet/instrument/migrering). |
| NFR-3 | Room-schemaändring kräver **migrering** utan dataförlust. |

---

## 6. Sålda fonder

| ID | Krav |
|----|------|
| SLD-1 | Vyn **Sålda fonder** (NAV-5) visar **en rad per säljtransaktion** (manuell eller importerad, IMP-5) — oavsett om fonden fortfarande delvis innehas eller är helt avyttrad — med datum, sålt antal andelar, belopp, avgift, anskaffningsvärde och **realiserat resultat** (kr + %, semantisk färg) beräknat med FIFO (TP-15): äldsta köpet matchas mot sälj i tidsordning. |
| SLD-2 | Räcker inte känd köphistorik för att fullt ut matcha en sälj visas resultatet ändå, men **tydligt markerat som osäkert** (text, inte enbart färg) i stället för att tystas ner eller krascha — samma princip som IMP-2/POR-3. |
| SLD-3 | Sålda-skärmen visar en summeringsrad överst med **totalt realiserat resultat** (kr + %, semantisk färg) summerat över alla säljtransaktioner (issue #21). |
| SLD-4 | Varje sälj-kort är **hopfällbart** — stängt som standard visas bara fondnamn och realiserat resultat; expanderat visas övriga detaljer (andelar, datum, belopp, avgift, anskaffningsvärde och en ev. osäkerhetsvarning, SLD-2), issue #21. |

---

## 7. Hem (dashboard)

| ID | Krav |
|----|------|
| HEM-1 | Hem (ny startskärm, NAV-1) visar portföljens totala värde, vinst/förlust (kr + %) samt förändring för perioderna **en dag, senaste veckan och senaste månaden** för hela portföljen (dagsperioden etiketteras "En dag", tidigare "Idag") (issue #14, #31). |
| HEM-2 | Räcker inte kurshistoriken för en period (t.ex. nyligen tillagd fond) markeras den perioden tydligt som osäker/saknas i stället för att tystas ner eller visa fel värde. Har *något* innehav historik men inte alla, markeras totalen som **delvis osäker** i stället för att exkludera hela totalen eller låtsas att alla fonder är med. Beror det på att inget innehav har en tillräckligt färsk kurs för perioden visas i stället "Kurs ej uppdaterad", skilt från äkta otillräcklig historik (issue #18). |
| HEM-3 | Tom portfölj visar samma tomt-tillstånds-princip som Portfölj (POR-2), med uppmaning att lägga till en transaktion. |
| HEM-4 | Hem visar ett **analys-summeringskort**: antal fonder per säljsignal-status (avsnitt 8) och en lista över gul-/rödflaggade fonder (namn + kort triggertext), där varje rad öppnar fondens Fonddetalj. Inga flaggade fonder visar ett lugnt tomt-tillstånd ("Inga fonder flaggade") i stället för att dölja kortet (issue #16). |

---

## 8. Analys — nyckeltal och säljsignaler

| ID | Krav |
|----|------|
| ANA-1 | Fonddetalj visar en **Analys**-sektion för fonder som är kvarvarande innehav, med nyckeltal: prisutveckling **i år, 3 månader, 1 år, 3 år och sedan första köp** (kr + %), **CAGR** (årlig snittavkastning sedan första köpet, bara om innehavet är minst ett år gammalt), **GAV** (kvarvarande FIFO-anskaffningsvärde per andel, TP-15) jämfört med aktuell NAV (kr + %), samt **andel av portföljens totala värde** (%). Räcker inte kurshistoriken för ett nyckeltal markeras just det tydligt som otillräcklig data i stället för att gissas (samma princip som POR-3/POR-5/HEM-2). |
| ANA-2 | Tre säljindikatorer beräknas per innehav, med fasta trösklar (dokumenterade i `FundAnalysisCalc`): **S1** avstånd från högsta NAV senaste 52 veckorna (−10 % gul, −20 % röd), **S2** NAV under 200-dagars glidande medelvärde (gul), **S3** innehavets 3-månadersutveckling minst 5 procentenheter sämre än snittet för övriga innehav (gul). Räcker inte historiken för en enskild indikator markeras den som otillräcklig data och ingår inte i statussummeringen (ANA-4). |
| ANA-3 | Indikatorerna (ANA-2) summeras till en **status** — röd om någon indikator är röd eller minst två är gula, gul om minst en är gul, annars grön — visad som en statusbanner (färg + rubrik + triggertexter, aldrig färg ensam, jfr UI-3) ovanför kurshistoriken i Fonddetalj. Språket är alltid neutralt ("värt att se över"/"bör ses över") — appen ger aldrig finansiell rådgivning ("sälj"). |
| ANA-4 | En indikator (ANA-2) eller ett nyckeltal (ANA-1) utan tillräcklig kurshistorik markeras tydligt som otillräcklig data och exkluderas ur statussummeringen (ANA-3) i stället för att tystas ner eller gissas — samma princip som HEM-2/POR-5/IMP-2/SLD-2. Saknar *alla* tre indikatorer data visas en neutral "otillräcklig kurshistorik"-text i stället för en färgad banner. |
| ANA-5 | Varje beräknad säljindikator (ANA-2) och varje nyckeltal (ANA-1) i Analys kan **fällas ut** med en klartextförklaring på svenska av vad måttet betyder och uttryckligen vad det *inte* betyder (t.ex. att ett fall från toppen inte i sig är ett skäl att sälja). Delas via den återanvändbara `ExpandableInfoRow` (`ui/components/`, regel 4). Bara indikatorer med tillräcklig data visas (ANA-4). Språket är aldrig ett köp-/säljråd (jfr ANA-3). |
| ANA-6 | Fonddetalj visar en **neutral kontexttext** härledd ur analysen (`AnalysisGuidance`, ett rent domänlager som `FundAnalysisCalc`) som sätter signalerna i sammanhang för en nybörjare — t.ex. att kursen ligger under toppen men fortfarande över GAV, eller att en djup nedgång kan tala för att låta tiden verka snarare än att agera — samt en kort **ordlista** ("Så funkar analysen": NAV, GAV, CAGR, glidande medelvärde, avstånd från topp, tidshorisont, ränta-på-ränta, volatilitet, Sharpe-kvot). Saknar analysen beräknad status (otillräcklig data, ANA-4) visas ingen kontexttext. Språket är alltid förklarande, aldrig rådgivande (ANA-3). |
| ANA-7 | Analys visar två **riskmått** per innehav, beräknade ur NAV-historiken med fasta konstanter (dokumenterade i `FundAnalysisCalc`): **volatilitet** (annualiserad standardavvikelse på dagsavkastningar, ×√252) och **Sharpe-kvot** ((annualiserad avkastning − fast riskfri ränta 0 %) / volatilitet). Räcker inte historiken (färre än ~60 dagsavkastningar) markeras måttet som otillräcklig data i stället för att gissas (ANA-4); är volatiliteten 0 saknas Sharpe (ingen division med noll). Måtten visas via delade `PeriodRow`/`ExpandableInfoRow` med utfällbar förklaring och ordlisttermer (ANA-5/ANA-6). Neutralt språk, aldrig rådgivning (ANA-3). Inget nytt persisterat fält (härlett ur befintlig kurshistorik). |
| ANA-8 | En fjärde signal, **vinstsignal (S4)**, flaggar när ett innehavs orealiserade vinst mot GAV är minst **+50 %** (fast tröskel, dokumenterad i `FundAnalysisCalc`). Till skillnad från S1–S3 (ANA-2) är det ingen risksignal — den deltar **inte** i den sammanslagna statusen (ANA-3) och visas med en egen markering (`ProfitTakeBadge`, `ui/components/`, regel 4), skild från risk-trafikljuset, eftersom paletten (UI-1) redan har både den gula och gröna nivåfärgen upptagna. Otillräcklig data (samma gate som GAV-nyckeltalet, ANA-1) visar ingen signal (ANA-4). Aldrig ett köp-/säljråd (ANA-3), issue #26. |

---

## Följdkrav (planerade — se GitHub-issues)

Drive-backup och Google-inloggning läggs till som egna krav i respektive avsnitt när de
implementeras — väntar på att ett Firebase-projekt sätts upp för fonder (`google-services.json`).

## Historik

- **Handelsdagsmedveten kursuppdatering, bakgrundsindikator och "Värde per datum" (#27):**
  Kursuppdateringen räknas om kring handelsdagar i stället för fasta tidsintervall — ny ren
  domänfunktion `NavCalendar.expectedLatestNavDay` (TP-17) avgör vilket datums NAV som borde
  vara känt (helg → senaste vardagen, vardag före kl. 18 → föregående vardag), och
  `FundPriceRepository.isPriceStale` jämför mot den i stället för den gamla "senaste kurs <
  idag"-jämförelsen som gav falska hämtningar på helger och falskt "färskt" på kvällar. Ny
  `FundPriceRefreshScheduler` (`worker/`) koalescerar tre triggers till samma
  `FundPriceUpdateWorker` via WorkManagers unika arbetsnamn: launch-gate vid appstart (`KEEP`),
  en gles periodisk backstop (`UPDATE`, 12h i stället för de gamla 24h, TP-5), och en manuell
  forcerad körning (`REPLACE`) från Inställningar (SET-2, "Uppdatera nu" + "Senast
  uppdaterad"). `FundPriceUpdateWorker.refreshAll` hoppar nu över redan aktuella fonder om
  inte forcerad, vilket gör backstopen och launch-gaten billiga. En liten bakgrundsindikator
  (`WorkerStatusIcon`, NAV-6) visas i navigeringschromet när en uppdatering pågår. Portfölj och
  Hem visar dessutom "Värde per \<datum\>" (`ValueAsOfRow`, POR-7) bredvid värdet — `Holding`
  fick fältet `navEpochDay`, totalens datum är det äldsta bland ingående innehav (samma
  "svagaste länk"-princip som HEM-2) — så en normal endagsförskjutning mot en extern källa
  (t.ex. banken) blir begriplig i stället för att se ut som ett fel. Ingen ny persisterad
  användardata; senaste synk-tidsstämpeln i DataStore är ren cache-metadata utanför
  backup-kontraktet.
- **Volatilitet och Sharpe-kvot (#24):** Analys-sektionen fick två riskmått (ANA-7) beräknade
  ur befintlig NAV-historik utan tredjepartsbibliotek — annualiserad volatilitet
  (standardavvikelse på dagsavkastningar ×√252) och Sharpe-kvot (annualiserad avkastning delat
  på volatilitet, riskfri ränta 0 %). Ren domänlogik i `FundAnalysisCalc` (nya `KeyFigures`-fält,
  inget persisterat), otillräcklig historik och nollvolatilitet ger null i stället för gissning
  (ANA-4). UI återanvänder delade `PeriodRow` (utökad med förformaterat, neutralfärgat `valueText`)
  och `ExpandableInfoRow` från #22, plus två ordlisttermer. Nya format­hjälpmedel
  `MoneyFormat.percent`/`decimal`.

- **Pedagogiskt analyslager (#22):** Analys-sektionen fick förklaringar och kontext för
  nybörjare (ANA-5, ANA-6) utan nya råa indikatorer. Varje säljsignal och nyckeltal kan
  fällas ut med en klartextförklaring (vad måttet betyder och vad det *inte* betyder) via
  den nya delade `ExpandableInfoRow` (`ui/components/`, regel 4). Ett neutralt kontextkort
  härlett ur `AnalysisGuidance` (nytt rent domänlager, `domain/usecase/`) sätter signalerna
  i sammanhang — t.ex. "under toppen men fortfarande plus mot GAV" eller att en djup nedgång
  kan tala för att låta tiden verka snarare än att sälja i botten — plus en kort ordlista
  ("Så funkar analysen"). Ingen persisterad data, ingen migrering; språket är genomgående
  förklarande, aldrig ett köp-/säljråd (ANA-3).
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
  använder nu även exportradens fondbolagsnamn som ledtråd — ger ett litet försprång åt
  kandidater från rätt bolag vid annars jämna namnträffar, utan att utesluta andra
  kandidater. En overlay-modal (IMP-4) visas medan matchning/kursuppdatering pågår,
  eftersom fem års historik per fond kan ta en stund att hämta.
- **Robustare fondbolagsledtråd (#8-uppföljning):** den första bolagsledtråden matchade
  exportens bolagsnamn mot katalogens separata fondbolagslista (Jaccard) och gav i praktiken
  sällan något utslag — t.ex. nådde "Handelsbanken Fonder AB" inte likhetströskeln mot
  katalogbolaget "Handelsbanken". `FundNameMatcher` jämför nu i stället fondbolagets
  **kärnnamn** (början av bolagsnamnet, `FundCompanyMatcher.coreBrandName`, t.ex.
  "Handelsbanken Fonder AB" → "Handelsbanken") direkt mot början av kandidatens fondnamn —
  eftersom både export- och katalogfondnamn normalt inleds med varumärket. Ger ett
  tillförlitligt försprång åt rätt bolags fonder (TP-13).
- **Kurshistorik via ISIN, sedan första köpet (#7-uppföljning):** ÖV-5/NAV-2 utökat —
  Fonddetalj visar nu hela historiken sedan fondens första köp, inte bara senaste året.
  `PurchaseDateEstimator`s kända begränsning (Handelsbankens 5-årscache räcker inte för
  äldre köp) löstes genom ett nytt, valfritt `isin`-fält på `Fund` (Room-migrering 2→3,
  TP-14) och en ISIN-baserad källkedja (`FundPriceRepository.refreshSince`/`suggestIsin`,
  i dag bara Avanzas odokumenterade fond-API — Nordnet/Morningstar undersöktes men
  saknade en bekräftat inloggningsfri sökväg från ISIN och är inte implementerade).
  Fonder från import får ISIN direkt från exportfilen (TP-13); fonder från fondsök
  föreslås ett ISIN via namnsökning i Fonddetalj, men det sparas först när användaren
  bekräftar/rättar det (samma "föreslå men kräv bekräftelse"-princip som IMP-2).
- **Sålda fonder och realiserat resultat (#10):** ÖV-9 klar — ny flik **Sålda** (NAV-5)
  visar realiserat resultat, beräknat med FIFO (äldsta köpet säljs först). Importflödet
  (#8) kan nu även skapa säljtransaktioner: varje inköpstillfälle i en importrad kan
  sättas till Köp eller Sälj (IMP-5) i stället för att alltid antas vara ett köp. En bugg i
  `PortfolioCalc` rättades samtidigt: kvarvarande `netInvested` för ett innehav byggde
  tidigare på kassaflödet (köp minus säljintäkter till säljpris) i stället för de
  kvarvarande andelarnas verkliga anskaffningskostnad — det gjorde orealiserad
  vinst/förlust (POR-3) missvisande efter en delförsäljning, särskilt med flera olika
  inköpspriser. Ingen ny persisterad data för resultatberäkningen — den härleds alltid ur
  befintliga transaktioner.
- **Sammanslagning av två parallella implementationer av #10 (#10-uppföljning):** samma
  feature byggdes oberoende av varandra i två sessioner samtidigt och landade båda i
  `master` innan de upptäcktes — en aggregerad per-fond-vy (`SaldaFonderScreen`,
  `FifoResultCalc`, ingen avgift) och en per-transaktion-vy med avgiftsstöd
  (`SoldFundsScreen`, `RealizedGainCalculator`) verifierad mot en riktig
  Handelsbanken-sälj-avräkningsnota. Efter avstämning med användaren behölls
  per-transaktion-vyn (mer detaljerad, stödjer avgift), men den aggregerade versionens
  bättre FIFO-modell för `PortfolioCalc.computeHoldings` (sant kvarvarande
  anskaffningsvärde via lot-tracking, i stället för ett kassaflödesbaserat närmevärde som
  gav fel resultat vid flera olika inköpspriser för samma fond) togs över och slogs samman
  med `RealizedGainCalculator` till en enda delad FIFO-motor (`compute` +
  `remainingPositions`) — så portföljens orealiserade resultat och "Sålda fonder"s
  realiserade resultat alltid bygger på samma sanning. Den verifierade
  `AvrakningsnotaPdfParser`-fixen (sälj-rader börjar med "Avslut", inte "Ut", och har
  negativa belopp/andelar) och avgiftsfältet (`Transaction.fee`, Room-migrering 3→4) togs
  med från per-transaktion-versionen, eftersom den aggregerade versionen saknade båda.
  `ui/salda/SaldaFonderScreen`/`SaldaFonderViewModel`/`FifoResultCalc`/`SoldFundResult`
  togs bort som redundanta. Portföljens "helt avsålda fonder visas inte"-fix (också
  byggd oberoende i samma session) bevarades ovanpå den sammanslagna motorn.
- **Fondmatchning via ISIN vid import, bredare täckning (#8-uppföljning):** verifierat mot
  en riktig export att `FundNameMatcher` bara hittade 1 av 7 rader — Handelsbankens
  fondlista-katalog (TP-9) innehåller i praktiken bara Handelsbankens egna fonder/XACT, inte
  AMF/Amundi/Franklin Templeton/Nordea m.fl., och den enda träffen matchade dessutom fel
  andelsklass (A10 i stället för radens A1). Ny `FundPriceRepository.findFundByIsin` (TP-14)
  slår upp fonden exakt via ISIN i samma källkedja som kurshistoriken (Avanza) — testat mot
  samma exportfil gav 6 av 6 unika ISIN en exakt träff, med rätt andelsklass. Importets
  matchningsordning är nu: (1) redan bevakad fond med samma ISIN, (2) exakt ISIN-träff via
  `findFundByIsin`, (3) `FundNameMatcher` mot Handelsbankens katalog som sista utväg. Fonder
  som bara hittas via ISIN saknar Handelsbanken-FundId — deras `Fund.fundId` blir ISIN:et
  självt, och kurshistorik hämtas alltid via `refreshSince` (aldrig `refresh()`).
- **Vidgat sökfönster för inköpsdatum vid ISIN-matchning (#8-uppföljning):** simulerat mot
  samma exportfil att Avanzas historik ofta sträcker sig mycket längre tillbaka än
  Handelsbankens femårsfönster (en fond hade data ända sedan 1994) — men med lägre
  upplösning ju längre tillbaka (dagligt inom ~1 år, veckovis inom ~8 år, därefter
  månadsvis). `PurchaseDateEstimator` sökte därför bara fem år tillbaka även för
  ISIN-matchade fonder, trots att källan kunde ge mer. Nytt sökfönster på 30 år för fonder
  med känt ISIN (TP-13) hittade i simuleringen ett annat, äldre datum för 2 av 7 rader — där
  femårsfönstret antingen missade helt eller gav en osäker träff. Fonder utan ISIN
  (Handelsbankens FundId-källa) söker fortfarande bara fem år tillbaka, oförändrat.
- **Import av PDF-avräkningsnotor, flera samtidigt (#8-uppföljning, ÖV-10):** en riktig
  avräkningsnota (Handelsbankens PDF-orderbekräftelse) gav exakt datum/kurs/antal andelar
  för en transaktion som `PurchaseDateEstimator` bara kunnat gissa på tidigare (samma fond
  som i det vidgade sökfönstret ovan — verklig köpdag visade sig vara 2020-03-13, inte
  gissningen 2019-12-31/2022-08-31). Ny `AvrakningsnotaPdfParser` (TP-16) läser en eller
  flera valda PDF-filer samtidigt (SAF-flerval), matchar varje transaktion mot en fond via
  samma prioritetsordning som Excel-importet (nu utbruten till delad `ImportFundMatcher`,
  regel 4) och importerar exakta transaktioner utan uppskattning (IMP-6–IMP-8). PDF-
  textextraktion via PdfBox-Android (TP-16), samma "isolerad + odokumenterat format"-princip
  som övriga källor i appen (TP-10/TP-13/TP-14).
- **Töm databasen (SET-1):** en "farozon"-sektion i Inställningar låter användaren rensa
  all bevakad data i ett steg (fonder, transaktioner, cachade kurser) — användbart för att
  börja om från scratch under den här tidiga fasen, innan molnbackup (TP-7) finns att
  återställa från. `TransactionRepository.clearAll()` rensar alla tre tabeller atomiskt
  (`AppDatabase.withTransaction`) så en avbruten körning aldrig lämnar databasen i ett
  inkonsekvent tillstånd (t.ex. transaktioner kvar utan sin fond). Kräver bekräftelse i en
  dialog (samma mönster som transaktionsradering, TRX-2) eftersom åtgärden är permanent och
  irreversibel.
- **Hem — ny startskärm med dag/vecka/månadsresultat (#14):** ny toppnivåflik **Hem** (NAV-1,
  först i bottennavigeringen) blir appens startskärm och visar portföljens totala
  värde/vinst/procent (samma beräkning som Portfölj) plus förändring **idag, senaste veckan
  och senaste månaden** (HEM-1). Ny domänberäkning `PortfolioPerformanceCalc`
  (`domain/usecase/`) jämför nuvarande värde mot vad *dagens* antal andelar var värda vid
  periodens start (senaste kända NAV på eller före det datumet) — ett enkelt
  marknadsvärde-mått, inte en kassaflödesjusterad avkastning (TWR/MWR); köp/sälj inom
  perioden påverkar alltså inte beräkningen. Räcker inte ett innehavs kurshistorik tillbaka
  till periodens start markeras just den perioden som otillräcklig data (samma princip som
  POR-3/SLD-2/IMP-2); saknar något men inte alla innehav historik markeras portföljens total
  som delvis osäker i stället för att exkludera den helt eller låtsas att alla fonder är med
  (HEM-2). Samma beräkning utökar även Portföljens innehavsrader med dag/vecka/månad per fond
  (POR-5). Ny delad komponent `PeriodRow` (`ui/components/`, regel 4) återanvänds mellan Hem
  och Portföljs innehavsrader. Ingen ny persisterad data — förändringen härleds vid läsning
  ur befintlig kurshistorik/transaktioner, precis som `PortfolioCalc`.
- **Kodgranskning — daglig uppdatering, talformat och datasäkerhet:** en fullständig
  genomgång av projektet hittade att fonder matchade via ISIN (`findFundByIsin`, TP-14 —
  t.ex. AMF/Amundi/Nordea) aldrig fick sin dagliga kursuppdatering: `FundPriceUpdateWorker`
  och `PortfoljViewModel`s engångsuppdatering anropade alltid `refresh()`, som nycklas på
  Handelsbankens `FundId` och därför aldrig träffar sådana fonder. Båda använder nu samma
  gren som `FondDetaljViewModel`/`ImportHoldingsViewModel` redan hade (`refreshSince` när
  `Fund.isin != null`, med senaste kända köpdatum eller fem år tillbaka som sökfönster).
  `FundPriceRepository.refresh`/`refreshSince` returnerar nu om hämtningen lyckades, så
  `FundPriceUpdateWorker` kan be WorkManager köra om jobbet (`Result.retry()`) om samtliga
  fonder misslyckades i stället för att tyst vänta ett helt dygn. Vidare: `SwedishNumberFormat`
  (delad talparsning, regel 4) används nu konsekvent i transaktionsformuläret och båda
  importflödena — tidigare accepterade bara ett av inmatningsfälten svenskt decimalkomma,
  vilket kunde göra formuläret tyst ogiltigt på ett svenskt tangentbord (`KeyboardType.Decimal`
  ger ofta bara komma). Samma funktion hade också av misstag två identiska ersättningar av
  vanligt mellanslag i stället för att även hantera hårt mellanslag (U+00A0, vanligt i
  talformatering från webbsidor) — rättat. Androids **Auto Backup** aktiverat
  (`allowBackup="true"`) som interimistiskt skydd mot dataförlust tills Drive-backupen
  (TP-7) är byggd, se NFR-1. `HoldingsImportParser`s XML-tolkning härdad mot XXE. Inga
  synliga beteendeändringar i UI:t utöver att fler fonder nu faktiskt får uppdaterade
  kurser och fler giltiga inmatningar accepteras — inga nya krav-ID:n.
- **Analys — nyckeltal och säljsignaler (#16):** ny sektion **Analys** (avsnitt 8) i
  Fonddetalj för kvarvarande innehav — periodavkastning (YTD/3 mån/1 år/3 år/sedan köp),
  CAGR, GAV vs aktuell NAV och portföljandel (ANA-1), samt tre säljindikatorer (avstånd från
  52-veckorshögsta, NAV vs 200-dagars glidande medelvärde, 3-månadersmomentum mot övriga
  portföljen) summerade till en grön/gul/röd status med neutral triggertext, aldrig
  rådgivning (ANA-2/ANA-3). Otillräcklig kurshistorik markeras per nyckeltal/indikator i
  stället för att gissas (ANA-4). Ny domänberäkning `FundAnalysisCalc` (`domain/usecase/`) —
  ren, testbar, återanvänder `Holding.netInvested` (redan FIFO-korrekt, TP-15) för GAV i
  stället för en egen anskaffningsberäkning. Hem visar ett nytt summeringskort (HEM-4) med
  antal fonder per status och en klickbar lista över gul-/rödflaggade fonder som öppnar
  Fonddetalj. Delad `PeriodRow` (`ui/components/`, regel 4) utökad med ett procent-utan-kr-läge
  (CAGR/portföljandel har inget kr-belopp) i stället för en ny komponent; ny delad
  `AnalysisStatus.kt` (statusfärg/-titel/-triggertexter, `StatusDot`, `AnalysisStatusBanner`)
  återanvänds mellan Fonddetalj och Hem. `StatusColors` (`ui/theme/`) återanvänder befintlig
  grön/röd (`ReturnColors`) och den befintliga mässings-/guldaccenten för gul — ingen ny
  hårdkodad färg, fast palett bevarad (UI-1). Ingen ny persisterad data — allt härleds ur
  befintlig kurshistorik/transaktioner vid läsning, precis som `PortfolioCalc`/
  `PortfolioPerformanceCalc`.
- **Inaktuell kurs gav falskt 0 för dag/vecka, plus första köp/inköpsvärde (#18):**
  rotorsak till att Portfölj/Hem kunde visa "+0,0 % · 0,00 kr" för idag/veckan trots en
  verklig kursrörelse: `PortfolioPerformanceCalc.holdingChange` letade efter periodens
  startkurs i samma kurshistorik som `currentValue` redan var beräknat ur — hade ingen ny
  kurs hunnit hämtas ännu låg både "nu"-priset och periodens startpris på exakt samma
  (inaktuella) rad, vilket alltid gav en förändring på noll i stället för ett ärligt
  "vet inte". Ny distinktion i `PortfolioPerformanceCalc` (`PeriodResult`/
  `PortfolioPeriodResult`, sealed types): `StalePrice` (senast kända kurs äldre än
  periodens start — visas som "Kurs ej uppdaterad") skild från `InsufficientHistory`
  (kursen är färsk men historiken når inte periodens start — samma "Otillräcklig data"
  som tidigare), se POR-5/HEM-2. `PortfoljViewModel`s engångsuppdatering triggar nu om även
  för fonder med en **inaktuell** (inte bara helt saknad) cachad kurs, så en gårdagens kurs
  faktiskt hämtas om vid öppning i stället för att aldrig uppdateras förrän nästa dagliga
  bakgrundsjobb (TP-5) hunnit köras. Vidare visar varje innehavsrad i Portfölj, och
  fondrubriken i Fonddetalj, nu **första köp-datum och kvarvarande FIFO-inköpsvärde**
  (POR-6) — `Holding` har fått fältet `firstPurchaseEpochDay`, härlett ur befintliga
  transaktioner (`PortfolioCalc.computeHoldings`), ingen ny persisterad data. Delad
  `PeriodRow` (regel 4) utökad med `unavailableReason`-parametern i stället för en ny
  komponent.
- **Import: långsam/redundant kurshämtning, PDF-flödet hämtade ingen kurs alls, "Import
  klar" utan stängknapp (#19):** Excel-importflödet (`ImportHoldingsViewModel`) hämtade
  tidigare om hela kurshistoriken (5 år, 30 för ISIN-matchade fonder) för **varje** rad vid
  import, även för fonder som redan hade en aktuell cachad kurs — nu hoppas hämtningen över
  när fonden redan har en färsk kurs (samma "senaste NAV < 1 dag gammal"-princip som #18,
  IMP-4). PDF-avräkningsnota-flödet (`ImportOrdersViewModel`) hämtade tidigare **ingen** kurs
  alls vid import — en nyimporterad fond fick därför ingen cachad kurs förrän något annat
  (Fonddetaljs eget init-block, eller nästa dagliga `FundPriceUpdateWorker`-körning) råkade
  hämta den, vilket gjorde att Portfölj/Hem inte visade dag/vecka/månad förrän fonden
  öppnats separat — flödet triggar nu samma kursuppdatering som Excel-flödet (IMP-8). Båda
  flödena använder nu `refreshFund`/`isPriceStale`, två delade hjälpfunktioner på
  `FundPriceRepository` (regel 4) som samlar den ISIN- vs Handelsbanken-FundId-gren som
  tidigare fanns separat i flera ViewModels — `PortfoljViewModel` omfaktorerad till samma
  hjälpfunktioner. En misslyckad kursuppdatering markerar nu raden ("Kurs kunde inte
  hämtas") i stället för att tystas ner, samma princip som IMP-2/POR-5/SLD-2. "Import
  klar" är nu en stängbar dialog (ny delad `ImportCompleteDialog`, regel 4, med antal
  importerade poster + en **Stäng**-knapp) i stället för en fullskärms tom-tillståndsvy vars
  enda väg ut var systemets bakåtknapp (IMP-9) — `AppNavigation.kt` trådar in
  `onDone = { navController.popBackStack() }` till båda importskärmarna, samma mönster som
  `TransactionFormScreen(onSaved = ...)`. Ingen ny/ändrad persisterad data.
- **Sålda: totalt realiserat resultat + hopfällbara kort (#21):** Sålda-skärmen visade
  tidigare bara enskilda sälj-rader utan någon summering, och varje kort visade alltid all
  detalj oavsett hur många säljtransaktioner som fanns. `SoldFundsUiState` har fått
  `totalRealizedGain`/`totalRealizedGainFraction` (summerat över `RealizedSale.realizedGain`/
  `costBasis`, samma "null om nämnaren är 0"-princip som `RealizedSale.realizedGainFraction`
  själv), visat i ett nytt summeringskort överst (SLD-3). Varje sälj-kort är nu hopfällbart
  (SLD-4) — stängt som standard visas bara fondnamn och realiserat resultat, expanderat
  visas andelar/datum/belopp/avgift/anskaffningsvärde och en ev. osäkerhetsvarning (SLD-2).
  Byggt som lokal state i `SoldFundsScreen` snarare än en delad `ui/components/`-variant —
  den befintliga `ExpandableInfoRow` (issue #22) löser ett annat problem (en alltid synlig
  rad med en utfällbar klartextförklaring), inte det här (en rad vars egna detaljer ska
  döljas/visas). `SoldFundsScreen` fick samma Content-uppdelning
  (`SoldFundsScreen`/`SoldFundsContent`) som Portfölj/Hem/Fonddetalj för testbarhet utan
  Hilt. Ingen ny/ändrad persisterad data.
- **Analys: vinstsignal + signal på varje portföljkort (#26):** de tre befintliga
  säljsignalerna (S1–S3, ANA-2) och den sammanslagna statusen (ANA-3) visades tidigare bara
  i Fonddetalj och som ett summeringskort på Hem — inte direkt på varje innehavsrad i
  Portfölj, appens primära lista för att bläddra innehav. `PortfoljViewModel` beräknar nu
  samma `FundAnalysisCalc.Analysis` per innehav som `HemViewModel` redan gjorde för sin
  summering (`PortfoljUiState.analysis`, samma princip som `performance`-kartan, POR-5) —
  ingen ny nätverksuppdatering, bara den lokala kurscachen. `HoldingRow` visar den
  återanvända `StatusDot` (regel 4) bredvid fondnamnet när tillräcklig data finns (POR-8).
  Ny fjärde signal **S4 "vinstsignal"** (`FundAnalysisCalc.ProfitTakeSignal`, ANA-8) flaggar
  när ett innehavs orealiserade vinst mot GAV (redan beräknad som `KeyFigures.gavFraction`)
  är minst +50 % — till skillnad från S1–S3 är det ingen risksignal utan en möjlighet, och
  deltar därför medvetet **inte** i `combineStatus`/`Analysis.status`. Den fasta paletten
  (UI-1) har redan både den gula (`StatusColors.gul`) och gröna (`StatusColors.gron` /
  `ReturnColors.gain`) nivåfärgen upptagna av risksignalerna, så vinstsignalen visas i
  stället med en ny delad `ProfitTakeBadge` (`ui/components/`, regel 4) — ikon + kort
  textetikett i stället för en färgad prick som skulle kunnat förväxlas med en befintlig
  risknivå. Neutralt språk, aldrig ett köp-/säljråd (samma princip som ANA-3). Inget nytt
  persisterat fält (härlett ur befintlig `Holding`/kurshistorik, precis som övriga
  `FundAnalysisCalc`).
