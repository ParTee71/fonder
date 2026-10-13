# Fonder – Android

App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut värde
och visa utveckling i diagram — med molnbackup via Google Drive.

> Version: 0.69.0 (följer `versionName`/[KRAVLISTA.md](KRAVLISTA.md))

**Kravspecifikation:** [KRAVLISTA.md](KRAVLISTA.md) · **Utvecklingsregler:** [CLAUDE.md](CLAUDE.md)

> **Bidrar du (eller en AI-assistent) med kod?** Läs [CLAUDE.md](CLAUDE.md) först — den
> samlar projektets fyra icke-förhandlingsbara regler: datasäkerhet (backup/restore),
> tester på alla nivåer, aktuell kravlista och återbruk av delade komponenter.

---

## Status

Projektet är i **tidig fas**. Grunden (arkitektur, tema, Room/DataStore, navigering,
repository-kontrakt, CI) finns; slutfunktionerna byggs som egna issues:

- [x] Kurskälla från Handelsbanken utan inloggning (spike #2 → implementerad i #3)
- [x] Fondtransaktioner (köp/sälj) (#4)
- [x] Värdeberäkning, nuvarande värde (#6)
- [x] Historisk värdeutveckling i tabell och diagram (#7)
- [x] Import av befintliga innehav (Handelsbanken-Excel) (#8)
- [x] Kurshistorik sedan första köpet, utan datumtak (#7-uppföljning, källan utnyttjad fullt ut i #37)
- [x] Import av exakta transaktioner från PDF-avräkningsnotor, flera samtidigt (#8-uppföljning)
- [x] Töm databasen från Inställningar, med bekräftelse (SET-1)
- [x] Realiserat resultat (FIFO) och avgifter vid försäljning, på säljraderna i Händelser (#10, sedan 0.62.0 ingen egen vy)
- [x] Hem — startskärm med portföljens dag/vecka/månadsresultat (#14)
- [x] Analys — nyckeltal och säljsignal-status per innehav, summeringskort på Hem (#16)
- [x] Fondmetadata (avgift, kategori, risk) — sökbart datalager, grund för en framtida
      fondrobot (#57)
- [x] Billigare alternativ till ett innehav — appens första rådgivande funktion, i Fonddetalj
      (ANA-9, #59)
- [x] Portföljens totala fondavgift per år, kort på Hem (HEM-5, #60)
- [x] Samlad besparingspotential, persisterad jämförelse med inkrementell bakgrundsifyllnad
      (HEM-6, #61)
- [x] Exponeringskarta i Portfölj — andel per fondtyp, region, index/aktivt (POR-9, #66)
- [x] Riskprofil — enkät, målrisknivå och jämförelse mot innehavens faktiska risk (SET-3/HEM-7, #68)
- [x] Fondkortet som beslutsstöd — byte överst, foldouts, risknivå överallt och
      jämförelsediagram mot föreslagen fond (ANA-10/ANA-11/UI-10, #85)
- [x] Bytesplanen räknas om på begäran — knapp på riskkortet plus automatiskt vid ändrad
      riskprofil/kontotyp (HEM-8/SET-3/SET-4, #88)
- [x] "Genomförd"-kvittering även på fondkortets bytesförslag, via delad komponent (ANA-10, #90)
- [x] Avgiftsbytena spelas in i facit och kan kvitteras, redovisade skilt från bytesplanens
      byten (ANA-9/ANA-10/SET-5, #91) — varje visat alternativ, oberoende av jämförelsens
      TTL (#93)
- [x] Riskprofil som målfördelning över flera risknivåer, i stället för en enda nivå (SET-3/HEM-7/POR-9, #71)
- [x] Bytesplan i ISK: rangordnade fondbyten mot målfördelningen, med facit-inspelning (SET-4/HEM-8, #70)
- [x] Säkerhetskopiering till fil — export/återställning av all användardata via SAF (SET-6, #82)
- [x] Google-inloggning — Firebase Auth + Credential Manager, kontokort i Inställningar
      (TP-6, #106)
- [x] Google Drive-backup — hela backup-kontraktet till `appDataFolder` på begäran
      (TP-7 steg 2 / SET-7, #110)
- [x] Påminnelse på Hem när data ändrats sedan senaste säkerhetskopian — ingen automatisk kopia
      (HEM-12)
- [x] Fliken Beslut — pågående byten, bytesplanen och vägen till Facit på ett ställe, med antal
      på flikens kopparmärke och i Översiktens "Att besluta" (NAV-9, HEM-13)
- [x] Följ och Avstå på bytesförslagen — ett avstått fondpar döljs i 30 dagar och kan ångras
      (HEM-14)
- [x] Billigare alternativ som egna förslag på Beslut, med Följ och Avstå, i flikens antal och i
      "Att besluta"; avstådda förslag döljs även i Fonddetalj (ANA-9, ANA-10, HEM-13, HEM-14, NAV-9)
- [x] Byten: bevakningen kopplad till sälj- och köptransaktionen, bytesväljaren i formulär och
      PDF-import, likvid på väg i bytesplanen och omräkning efter varje transaktion
      (BYT-1–BYT-3, #143/#144)

---

## Design

Visuell identitet **Nordisk blå** (primär `#1F4E79` ljust, `#9CC4F0` mörkt) med
kopparaccent (`#B4552F` ljust, `#FFB59A` mörkt) i **Material 3 Expressive**, **fast palett** (ingen dynamisk färg), ljust + mörkt
tema. Rubriker i **Space Grotesk**; belopp med **tabulära siffror**. Avkastning visas med
semantisk färg **och** tecken/pil (aldrig färg ensam).

---

## Kom igång

### Förutsättningar

- Android Studio (senaste), JDK 17, Android SDK API 37 (compileSdk), minSdk 30

### Bygg och kör

```bash
./gradlew :app:assembleDebug
```

Öppna i Android Studio och kör på en enhet/emulator (API 30+).

### Google Services

Google-inloggning (TP-6) och Drive-backup (TP-7 steg 2) kräver en `google-services.json`
från Firebase Console i `app/`. Filen är **incheckad** — `google-services`-pluginet läser
den vid varje bygge, så utan den i repot går CI inte att köra. Både inloggningen (#106) och
molnbackupen (#110) är byggda och inkopplade.

Signeringsnycklar (`*.jks`, `*.keystore`) får **aldrig** checkas in — CI har en grind som
fäller bygget om någon gör det. Undantaget är `app/debug.keystore`, som är avsiktligt
incheckad med Androids kända standardlösenord.

Uppsättningen — Firebase-projekt, SHA-1-fingeravtryck, OAuth-medgivandeskärm och Drive
API — är dokumenterad steg för steg i **[docs/GOOGLE-SETUP.md](docs/GOOGLE-SETUP.md)**.

---

## Releasebygge och signering

Releasebygget signeras med `app/fonder.jks` (git-ignorerad). Lösenord läses ur
`local.properties` eller miljövariabler — saknas de byggs `release` osignerat i stället
för att fela.

**local.properties (lokalt, git-ignorerad):**
```properties
signing.storePassword=<lösenord>
signing.keyAlias=fonder
signing.keyPassword=<lösenord>
```

**GitHub Secrets (CI):** `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. `.github/workflows/release.yml` avkodar
keystoren, bygger och publicerar en signerad APK — hela släppet går därmed att köra från
telefonen. Se [docs/GOOGLE-SETUP.md](docs/GOOGLE-SETUP.md) för hur nyckeln skapas och
läggs upp.

---

## Versionshistorik

Tabellen skrivs vid varje release (se `.claude/skills/release`) och sammanfattar det
**användarsynliga** som kommit sedan föregående version. Den är avsiktligt kort — motiven
bakom besluten står i [KRAVLISTA.md](KRAVLISTA.md), som uppdateras löpande när varje
ändring landar. Tabellen börjar vid 0.51.0; för allt före det är KRAVLISTAs `Historik`
den fullständiga källan.

| Version | Innehåll |
|---|---|
| 0.69.0 | **Byten C – ett byte, en rad** (BYT-4, NAV-9, HEM-13, HEM-14, ANA-9, #145): Beslut visar listan Byten i stället för avsnitten Pågående byte, Förslag och Billigare alternativ, som kunde visa samma byte tre gånger. Varje byte står på en rad med ett tillståndschip – Föreslaget, Avgift, Sålt · dag N av 14 – och Registrera säljet/Avstå eller Öppna bevakningen. Köpta, avbrutna och utgångna byten ligger i 14 dagar i den hopfällda sektionen "Avslutade och avstådda" tillsammans med de avstådda förslagen. Översiktens "Att besluta" säger "N föreslagna · M sålda, väntar på köp · K billigare alternativ"; samma antal som flikmärket. |
| 0.68.0 | **Byten B – väljaren i formulär och import** (BYT-2, BYT-3, HEM-8, ANA-12, SET-5, SLD-5, TRX-2, #144): när ett sälj eller köp registreras — i formuläret eller i PDF-importen — svarar du på om det hör till ett byte: *Inget byte*, *Fristående bevakning* eller *för planrad N* vid sälj, *avslutar bevakning X* vid köp. Sälj av en fond planen vill sälja och köp av en bevakad kandidat är förvalda; ett köp av något annat medan ett byte pågår frågar alltid, utan gissning. Köpet stänger bytet på köpdagen med köptransaktionen som koppling; ett sälj som hör till en bevakning du startade en annan dag kopplas bara efter att du svarat ja, och då mäts alternativen om från säljdagen. Bytesplanen räknar nu med likviden som väntar på köp och räknas om efter varje transaktion, så den föreslår inte nästa sälj för ett gap du redan finansierat. Följ på Beslut och Fonddetalj öppnar "Registrera säljet" förifyllt (fond och typ låsta, dagens datum, senaste kurs, andelar ur förslagets belopp), "Köpte den här" köpformuläret med bytet förvalt; "Genomförd"-kryssrutan finns bara kvar i Facit. Väljaren är en vanlig rullgardin ("Byte" / "Hör till byte") med en förklaring under, och Spara är släckt tills en fråga utan förval besvarats. I PDF-importen står säljen före köpen och varje rad har samma väljare. |
| 0.67.0 | **Transaktionerna driver bytet** (BYT-1–BYT-2, HEM-8, HEM-14, ANA-12, SLD-5, SET-5, TRX-2, NFR-1, #143): ett pågående byte som startas från Händelser är kopplat till säljtransaktionen, så samma sälj inte kan få två bevakningar; "Bevaka alternativ" ur ett förslag på Beslut eller Fonddetalj startar fortfarande utan transaktion och dedupas på fond och dag. Kopplingen till köptransaktionen finns i datamodellen men sätts först i #144. "Bevaka alternativ" på Händelser kopplar en bevakning du redan startat ur ett förslag samma dag i stället för att starta en till. Bytesplanen räknas om efter Avstå/Ångra och lämnar avstådda byten utanför så listan alltid är 1..n; ett avstått byte går att ångra även efter att planen räknats om utan det, och ett byte som är klart tar planen med sig direkt i stället för att ligga kvar en vecka. Tar du bort en transaktion ligger bevakningen kvar utan koppling. Kopplingarna följer med i säkerhetskopian; en äldre kopia läses som okopplade bevakningar. Ingen synlig ändring i formulär eller import än – väljarna, omräkningen efter transaktioner och likviden på väg (BYT-3) kommer i #144, Beslut-listan i #145. |
| 0.66.1 | **Navigation 3** (TP-2): navigeringen bygger nu på Jetpack Navigation 3 med en egen bakåtstack per flik som överlever rotation och processdöd – samma flikar, bakåt och plusmeny som förut, ingen synlig ändring. Instrumenttest med Hilt bevisar Innehav → Fonddetalj och Beslut → Pågående byte på enhet (#139). |
| 0.66.0 | **200-dagarssnitt i kursdiagrammet** (ANA-14, ANA-2): Fonddetaljs kursdiagram visar 200-dagarssnittet som en prickad linje, med en förklaring under diagrammet som visar senaste kurs och senaste snitt i kronor. Snittet är detsamma som säljsignalen S2 jämför mot och räknas ur hela historiken, så också 1 mån har en snittlinje. Räcker historiken inte visas ingen linje. |
| 0.65.0 | **Resultatet på Beslut** (NAV-9, SET-5): raden "Hur gick det?" visar nu vad de förslag du följt har gett i kronor mot att ha behållit innehavet, färgat som vinst eller förlust, bredvid antalet ("Facit · 7 följda förslag  +4 210,00 kr"). Beloppet är samma som Facits rader "Genomförda" tillsammans. Utan mätbart resultat visas bara antalet. |
| 0.64.0 | **Billigare alternativ på Beslut** (ANA-9, ANA-10, NAV-9, HEM-13, HEM-14): de billigare alternativen till dina fonder visas nu också på Beslut, under bytesplanen, ett kort per alternativ med fondparet, avgifterna och besparingen per år samt Följ och Avstå. Ett följt alternativ visar "Genomförd" och "Bevaka alternativ", ett avstått hamnar under "Avstådda (N)". Öppna alternativ räknas i flikens märke och i "Att besluta" ("2 bytesförslag · 1 billigare alternativ · 1 pågående byte"). Ett avstått förslag visas inte heller i Fonddetalj, som i stället säger hur många som avståtts. |
| 0.63.0 | **Följ och Avstå på förslagen** (HEM-14, HEM-8, NAV-9, HEM-13): varje bytesförslag på Beslut har knapparna Följ och Avstå. Följ markerar förslaget som genomfört, som "Genomförd" förut, och erbjuder sedan "Bevaka alternativ". Avstå döljer förslaget och tar det ur antalet på flikens märke och i "Att besluta" – även när planen räknas om nästa dag – i 30 dagar. Längst ned på Beslut finns raden "Avstådda (N)" där ett avstående kan ångras. Avståendena följer med i säkerhetskopian. Jämförelsekurvan i diagrammen (index, alternativ) är nu streckad och kopparfärgad så att den syns tydligt mot den egna kurvan. |
| 0.62.0 | **Händelser som en lista** (TRX-1, TRX-3, TRX-4, SLD-1–SLD-5, NAV-5): Sålda fonder är ingen egen skärm längre. Händelser visar alla köp och sälj i en lista med filtret Alla · Köp · Sälj och ett kort med årets realiserade resultat; ett sälj visar sin vinst eller förlust direkt i raden (osäkert markerat i text), och ett tryck fäller ut andelar, belopp, avgift, anskaffningsvärde och "Bevaka alternativ". Totalen sedan start står i säljfiltret. |
| 0.61.0 | **Fliken Beslut** (NAV-9, HEM-13): det som väntar på dig samlas på en egen flik – pågående byten med det alternativ som gått bäst sedan säljdagen, bytesplanens förslag mot din riskprofil med "Genomförd" och "Bevaka alternativ" som förut, knappen "Räkna om bytesplanen" och raden "Hur gick det?" till Facit, som nu öppnas som en egen skärm med bakåtpil. Raden överst visar vilken riskprofil och kontotyp förslagen räknas mot, med en genväg till riskprofilen. Fliken bär ett kopparmärke med antalet öppna förslag och pågående byten, och Översikten visar samma antal i raden "Att besluta" under värdet. Översikten visar därefter jämförelsen mot index och påminnelsen om säkerhetskopia; bytesplanen och kortet för pågående byte har flyttat till Beslut. |
| 0.60.0 | **Ny navigering: fyra flikar och en plusmeny** (NAV-1, NAV-7, NAV-8, UI-14): flikarna heter nu Översikt, Innehav, Beslut och Händelser och ligger i en flytande flikrad längs nederkanten, där den valda fliken visar sitt namn. Plusknappen bredvid fäller ut Ny transaktion, Importera avräkningsnota, Importera innehav och Bevaka ny fond – samma knapp på alla flikar i stället för en egen på Portfölj och Transaktioner. Inställningar öppnas med kugghjulet på Översikt och Sålda fonder med länken på Händelser. Beslut visar tills vidare Facit. Bakåt från en flik går till Översikt. |
| 0.59.0 | **Samma knappar, kort, fält och dialoger överallt** (UI-13): alla skärmar använder appens delade komponenter i stället för egna – knappar som byter form när de trycks ned, kort med svag kontur, konturerade fält med 12 dp hörn och dialoger där en radering bekräftas med en knapp i felfärgen. Köp/Sälj väljs i samma knappgrupp som övriga val, kryssraderna i importen är klickbara på hela raden, och importens väntan visas i en gemensam dialog med den formskiftande laddningsindikatorn. |
| 0.58.0 | **Nytt utseende: Nordisk blå och koppar i Material 3 Expressive** (UI-1, UI-12): ny fast palett i blått med kopparaccent i ljust och mörkt läge, mjukare former (kort 18 dp, dialoger 28 dp, fält 12 dp), valrader som sammanhängande knappgrupper, en formskiftande laddningsindikator i stället för snurran, tjockare exponeringsstaplar och en tonad yta under kurskurvan. Påminnelseraden om säkerhetskopian är nu neutralt tonad. Vinst- och förlustfärgerna är oförändrade. |
| 0.57.0 | **Säkerhetskopia med påminnelse** (HEM-12, SET-7): den automatiska dygnsvisa molnbackupen är borttagen — du säkerhetskopierar till Google Drive eller fil när du vill, och Hem visar överst en rad när något ändrats sedan senaste kopian ("3 nya transaktioner · senast …"), med en knapp som leder rakt till säkerhetskopieringen. Inställningar visar när och vart den senaste kopian togs. Det gamla bakgrundsjobbet avregistreras vid uppgradering. |
| 0.56.0 | **Dra ned för att uppdatera** (UI-11, #119): Hem, Portfölj, Fonddetalj, Pågående byte, Facit och Fondsök uppdateras nu med en dragning — där datan faktiskt visas. Knappen "Uppdatera nu" i Inställningar är borttagen; vägen dit var lämna skärmen, skrolla till kortet, trycka och gå tillbaka, medan gesten alla andra appar har inte gjorde något. Varje skärm hämtar det just den visar: Hem och Portfölj forcerar en kursuppdatering, Fonddetalj fondens historik, Pågående byte kandidaternas kurvor, Facit bytesplansskanningen och Fondsök fondkatalogen. "Senast uppdaterad" står kvar i Inställningar. Snurran är samma signal som korten använder, så den slutar när datan faktiskt landat |
| 0.55.0 | **Väntesnurra på korten** (NAV-6, #118): varje kort och varje diagram visar nu en liten snurra i rubriken medan just dess data hämtas eller räknas om — totalen och innehavsraderna när kurserna uppdateras, exponeringskartan och avgiftskortet när fondmetadatan slås upp, riskkortet när bytesplanen skannas, avkastningskurvan när referensfonden hämtas, backup-kortet när molnkopian körs. Tidigare fanns bara en indikator högst upp i appen, som sa att *något* pågick men inte vad; ett kort med nollor gick därför inte att skilja från ett kort där appen faktiskt inte vet. Snurran är bunden till just de jobb som skriver kortets data, så en molnbackup inte får fondkorten att snurra |
| 0.54.0 | **Tidsviktad avkastningskurva** (HEM-9/HEM-10, #116): diagrammet på Hem mäter nu hur dina fonder faktiskt utvecklats, i stället för avkastningen på insatt kapital räknad om per dag. Insättningar, uttag och byten flyttar inte längre kurvan, så periodsiffrorna går att jämföra med varandra — tidigare kunde "5 år" visa någon enstaka procent samtidigt som 1 år och 3 år såg utmärkta ut, bara för att en stor påfyllning låg strax efter femårsfönstrets startdag. Kurvan slutar därför inte längre på samma procent som totalen överst: den siffran är vad du tjänat på insatt kapital, kurvan är hur fonderna gick, och texten under diagrammet säger det. Indexjämförelsen mäts med samma mått som förut |
| 0.53.0 | **Pågående byte** (ANA-12/ANA-13, #114): mellan att du sålt och att likviden är framme kan du nu bevaka ett par alternativa fonder att byta till. Bevakningen startas från ett kvitterat bytesförslag på Hem eller fondkortet, eller fristående från en säljrad i Sålda fonder. Appen fyller listan med köpkandidater på rätt risknivå — samma urval som bytesplanen använder — och du lägger till egna ur Fondsök, upp till fem. Skärmen visar säljfonden och alla alternativ i samma diagram, och per rad vad fonden gjort **sedan säljdagen** i kronor och procent. "Köpte den här" stänger bevakningen; appen genomför fortfarande aldrig ett byte. Hem visar ett kort för en pågående bevakning med den kandidat som gått bäst |
| 0.52.0 | **Molnbackup till Google Drive** (TP-7 steg 2 / SET-7, #110): säkerhetskopieringskortet i Inställningar skriver nu hela innehållet till en dold mapp i din Drive — automatiskt en gång per dygn, och på begäran med en knapp. Kortet visar när den senaste kopian togs, och säger till när Drive behöver din tillåtelse. Det är den första återställningsvägen appen både kan sköta åt dig och själv verifiera; filexporten finns kvar och fungerar utan konto och utan nätverk. Återställning från Drive ersätter all data och ligger bakom samma bekräftelse som filåterställningen. De fem senaste kopiorna behålls |
| 0.51.0 | **Google-inloggning** i Inställningar via Firebase Auth och Credential Manager (TP-6, #106): kortet visar inloggad adress och "Logga ut", eller "Inte inloggad" och en inloggningsknapp. Ett avbrutet kontoval visas aldrig som ett fel. Inloggningen flyttar ingen data i sig — den är förutsättningen för molnbackup till Drive (TP-7 steg 2), vilket kortets text säger rakt ut. Auto Backup begränsas samtidigt till databasen och inställningarna; tidigare kopierades appens hela datakatalog, vilket nu även hade omfattat den sparade inloggningen (NFR-1) |

---

## Arkitektur

```
Compose → ViewModel (StateFlow<UiState>) → Repository → Room / DataStore
```

Paket under `app/src/main/kotlin/se/partee71/fonder/`:

```
data/
├── auth/         AuthRepository + FirebaseAuthRepository (Google-inloggning via Credential Manager, TP-6/#106)
├── datastore/    PreferencesRepository (tema, riskprofil #68 m.m.)
├── network/      HandelsbankenFondlistaClient + HandelsbankenHtmlParser (kurskälla, #2/#3) ·
│                 AvanzaClient + AvanzaJsonParser + AvanzaPriceSource (ISIN-baserad historik, #7-uppföljning) ·
│                 AvanzaFundListParser + AvanzaFundListRequestBuilder (sökbar fondmetadata, #57) ·
│                 RiksbankFxClient + RiksbankJsonParser (dagsnoterade växelkurser, #43)
├── imports/      HoldingsImportParser (Excel-innehav, #8) · AvrakningsnotaPdfParser + PdfTextExtractor
│                 (PDF-avräkningsnotor, flera filer samtidigt, #8-uppföljning)
├── repository/   TransactionRepository (Room) · FundPriceRepository (Handelsbanken + ISIN-källkedja) ·
│                 FundMetadataRepository (fondmetadata + Handelsbanken-köpbarhet + persisterad
│                 billigare-alternativ-jämförelse + kända risknivåer, #57/#61/#68) ·
│                 BackupRepository/LocalBackupRepository (fil, SET-6) · DriveBackupRepository (moln, SET-7) ·
│                 SwitchWatchRepository (pågående byte mellan sälj och köp, ANA-12/#114) ·
│                 SuggestionDismissalRepository (avstådda bytesförslag per fondpar, HEM-14)
└── room/         AppDatabase (v16) · entities (inkl. FxRateEntity, FundMetadataEntity, SwitchWatchEntity,
                  SuggestionDismissalEntity) · daos (inkl. FxRateDao, FundMetadataDao, SwitchWatchDao, SuggestionDismissalDao)
di/               Hilt-moduler (AppModule, StorageModule – Room-filen och DataStore, NetworkModule, RepositoryModule)
domain/
├── model/        Fund (fundId, valfritt isin/fondlistaFundId) · FundCompany · FundCatalog · Transaction (inkl. fee) · FundPrice ·
│                 IsinPricePoint · ImportedHoldingRow · ImportedOrderTransaction · Holding · RiskProfile/RiskProfileAnswers (#68)
└── usecase/      PortfolioCalc · PortfolioPerformanceCalc (dag/vecka/månad, #14) ·
                  FundAnalysisCalc (nyckeltal + säljsignaler per innehav, #16) ·
                  RealizedGainCalculator (delad FIFO-motor, realiserat + kvarvarande resultat, #10) ·
                  MoneyFormat · SwedishNumberFormat · FundCompanyMatcher (kärnnamn för bolagsledtråd) · FundNameMatcher ·
                  PurchaseDateEstimator · ImportFundMatcher (delad matchningsordning, regel 4) ·
                  CurrencyConverter (fondlistas kurser → kronor, #43) · TransactionFormValidator ·
                  ChartPeriodFilter (kursdiagrammets periodväljare, #51) ·
                  PurchaseMarkerFilter (köpmarkörer i diagrammet, #55) ·
                  MovingAverageCalc (200-dagarssnittet för S2 och diagrammets snittlinje, ANA-14) ·
                  FundScreenFilter + FundMetadataFreshness (fondmetadata-frågor, #57) ·
                  FeeComparisonCalc (billigare alternativ, ANA-9, #59) ·
                  PortfolioFeeCalc (portföljens totala fondavgift + samlade besparingspotential, HEM-5/HEM-6, #60/#61) ·
                  PortfolioExposureCalc (exponeringskarta: fondtyp/region/index-aktivt, POR-9, #66) ·
                  RiskProfileCalc (målrisknivå ur enkätsvar, SET-3, #68) ·
                  PortfolioRiskCalc (innehavens värdeviktade risknivå, HEM-7, #68) ·
                  SwitchWatchCalc (utveckling sedan säljdagen för bevakade alternativ, ANA-12, #114) ·
                  RecordTransaction (den enda skrivvägen för transaktioner, och bytets livscykel: `Entry` med
                  `SwitchDecision`, bevakning ur sälj/planrad, avslut med köptransaktionen, koppling från
                  annan dag efter fråga, och en begärd omräkning av planen per skrivning, BYT-2/BYT-3, #143/#144) ·
                  SwitchTransitions + SwitchChoicesSource (bytesväljarens alternativ och förval, `SwitchDecision`,
                  och vilken okopplad bevakning från en annan dag ett sälj kan kopplas till, BYT-2, #144) ·
                  SwitchQuestions (väljarens frågor i formuläret och PDF-importen: Följ, "Köpte den här",
                  fråga om annan dag, köp som avslutar en säljrads bevakning i samma import, BYT-2, #144) ·
                  PendingDecisionsSource (bytesplan + billigare alternativ + pågående byten och deras antal,
                  en källa för Beslut, Översikten och flikmärket, NAV-9/HEM-13) ·
                  CheaperAlternatives (inspelade avgiftsbyten som förslag på Beslut, ANA-9) ·
                  SuggestionDismissals (vilka förslag ett avstående döljer och hur länge, HEM-14) ·
                  FacitSummary (facit ur cachen, en källa för Facit och Beslut-flikens resultat, SET-5/NAV-9)
ui/
├── hem/          HemScreen + ViewModel (Översikt: värde, "Att besluta" HEM-13, avkastning mot index,
│                 dag/vecka/månadsresultat, analys-summeringskort #16, fondavgiftskort HEM-5/HEM-6,
│                 riskkort mot riskprofilens målfördelning HEM-7)
├── beslut/       BeslutScreen + ViewModel (fliken Beslut: pågående byten, bytesplanen och billigare alternativ med Följ/Avstå, vägen till Facit, NAV-9/HEM-14)
├── facit/        FacitScreen + ViewModel (utfallet av inspelade förslag, skärm i stacken under Beslut, SET-5)
├── portfolj/     PortfoljScreen + ViewModel (exponeringskarta: fondtyp/region/index-aktivt, POR-9, #66)
├── transaktioner/TransaktionerScreen + ViewModel (fliken Händelser: filter Alla/Köp/Sälj, årets realiserade
│                 resultat, sälj med realiserat resultat per rad, #10) · TransactionFormScreen + ViewModel (registrera köp/sälj, avgift)
├── fond/         FondDetaljScreen + ViewModel (bytesbeslut överst: bytesplan + billigare alternativ med
│                 jämförelsediagram, ANA-10/ANA-11/#85 · kurshistorik i diagram sedan första köpet, #7, med 200-dagarssnitt, ANA-14 ·
│                 hopfälld Analys-sektion med nyckeltal/säljsignaler, #16 · billigare alternativ, ANA-9/#59)
├── fondsok/      FundSearchScreen + ViewModel (sök hela plattformens katalog, filtrera per fondbolag via källan, lägg till fond)
├── imports/      ImportHoldingsScreen + ViewModel (Excel-innehav, #8) · ImportOrdersScreen + ViewModel
│                 (PDF-avräkningsnotor, #8-uppföljning)
├── settings/     SettingsScreen + ViewModel (tema, kursuppdatering, riskprofil-ingång SET-3/#68 …)
├── riskprofil/   RiskProfilScreen + ViewModel (enkät + målrisknivå, SET-3, #68)
├── bytesfonster/ SwitchWatchScreen + ViewModel (pågående byte: jämförelse av bevakade alternativ mellan
│                 sälj och köp, ANA-12/ANA-13, #114)
├── navigation/   AppNavigation (Navigation 3: NavDisplay, en dekorerad stack per flik) · AppBackStack (stack per
│                 flik, sparas över rotation och processdöd) · AppKeys (@Serializable NavKey per skärm) · Screen ·
│                 Transitions · DecisionBadgeViewModel (Beslut-flikens märke, NAV-9)
├── components/   Delade komponenter (EmptyState, SelectField, DateField, PeriodRow, AnalysisStatusBanner/StatusDot,
│                 ExposureBar — proportionell radlista, POR-9/#66 · ChoiceChipRow — val-chiprad, #68 ·
│                 ExpandableSection/ExpandableInfoRow — utfällning, #22/#85 · RiskBadge — risknivå 1–7, UI-10/#85 …)
├── diagram/      Delade diagram (FundLineChart — en eller flera indexerade serier, ANA-11/#85)
└── theme/        Nordisk blå-tema (M3 Expressive), Space Grotesk-typografi (inkl. StatusColors, #16)
worker/           FundPriceUpdateWorker (daglig kursuppdatering + inkrementell jämförelseifyllnad, HEM-6/#61 ·
                  kandidatkurser för pågående byten, HEM-11/#114)
```

**Domän → worker, ett medvetet undantag:** `RecordTransaction` (domän) beror på
`FundPriceRefreshScheduler` (worker) för att begära en omräkning av bytesplanen efter varje
transaktion (BYT-3, #144). Omräkningen är en del av vad en transaktion *betyder* — hade varje
ViewModel själv behövt minnas den hade en glömd begäran lämnat planen föreslående ett sälj för ett
gap som redan är fyllt. Beroendet går mot gränssnittet, aldrig mot WorkManager, och testerna
använder `FakeFundPriceRefreshScheduler`.

Repository är single source of truth. `FundPriceRepository` hämtar och cachar riktiga
kurser från `handelsbanken.fondlista.se` (se issue #2/#3 för källbeslut och risknotis —
odokumenterad, inofficiell HTML-källa). Källan har **inget datumtak**: hela historiken
sedan första köpet kommer i ett anrop, men eftersom svaret kan bli flera megabyte hämtas
det bara som backfill — när cachen inte redan når tillbaka så långt räcker ett kort färskt
fönster (KRAVLISTA TP-18, issue #37). Räcker inte fondlista provas en prioritetsordnad
lista av `IsinPriceHistorySource` som reserv — i dag bara Avanzas odokumenterade fond-API
(`AvanzaPriceSource`, samma riskprofil, se KRAVLISTA TP-14); den täcker fonder som saknas i
katalogen och därför identifieras med sitt ISIN. `BackupRepository` och `AuthRepository` är
fortfarande kontrakt med stubbar tills respektive feature byggs.

**Fondbolagsfilter:** källans `company`-parameter filtrerar fondlistan exakt — utan den
levereras hela plattformens katalog (~1500 fonder), med den bara det valda bolagets.
Fondsök använder därför källans eget filter (`fetchFundsForCompany`) i stället för den
tidigare gissningen i appen. `FundCompanyMatcher.matches` är borttagen; `coreBrandName`
finns kvar som ledtråd åt `FundNameMatcher` vid importmatchning.

**ISIN från källan:** fondens egen sida (`/shb/sv/funds/<fundid>`) bär ISIN i
faktabladslänken, så `lookupIsin` kan koppla `FundId` ↔ ISIN maskinellt — används både när
en fond läggs till via fondsök och för att verifiera importens namnmatchning.

**Valuta:** all cachad NAV är i kronor (`FundPrice.VALUE_CURRENCY`). Fondlista noterar fonder i
fondens egen valuta — sådana kurser räknas om till kronor med en dagsnoterad växelkurs från
Riksbankens öppna API (`RiksbankFxClient`, `CurrencyConverter`, KRAVLISTA TP-19/TP-20) i stället
för att kastas. Saknas växelkursen för en dag utelämnas dagen, aldrig ett gissat värde; går
hämtningen inte alls faller fonden tillbaka på Avanza, som redan ger kronor direkt.

**Fonder som bara är kända via ISIN:** importmatchade fonder (`findFundByIsin`) har ISIN:et
som `fundId` och saknar plattformskod. De får ett uppslaget `fondlistaFundId` (namnkandidat
i katalogen verifierad mot ISIN) så även de når fondlistas färskare kurser — identiteten och
transaktionernas koppling följer fortfarande `fundId`. Avanzas svar filtreras dessutom på
handelsdag: källan levererar ibland helgdaterade kurser, som både är påhittade och gör
staleness-gaten (`isPriceStale`) falskt negativ.

---

## Tester

- **Enhet (JVM):** `domain/` (PortfolioCalc, RealizedGainCalculator — FIFO inkl.
  delförsäljning över flera lotter och avgift, FundAnalysisCalc — periodavkastning, CAGR,
  GAV, portföljandel och säljsignalerna S1–S3 kring sina trösklar, MoneyFormat), `data/network/`
  (HTML-/JSON-parsning mot verkliga sid-/API-fixturer, inkl. Avanzas fond-API),
  `data/imports/` (Excel- och PDF-parsning mot verkliga fixturer — köp- **och**
  sälj-avräkningsnota, PDF-textextraktionen fejkad via `PdfTextExtractor` så testerna
  slipper PDF-biblioteket), `data/repository/` (cache/fallback-logik med fejkade
  HTTP-källor) och ViewModels (Turbine).
- **Instrument:** Room DAO-rundtur (`androidTest`), inklusive `FundPriceDao`, migreringstester
  (`Migration12Test`, `Migration23Test`, `Migration34Test`) samt `RoomTransactionRepositoryTest`
  (`clearAll` töms atomiskt över alla tre tabeller, SET-1).

> Formell `MigrationTestHelper`-baserad migreringstest saknas — migreringstesterna bygger
> i stället det gamla schemat med rå SQL och öppnar via den riktiga `AppDatabase`, vilket ändå
> fångar en felaktig migrering via Rooms schemaverifiering. Schema-JSON:erna checkas in från
> version 15 (`app/schemas/`); sedan `Migration1516Test` läses det gamla schemats satser ur
> den frysta JSON:en i stället för att skrivas av (skill `room-migrations`).

Kör i **GitHub Actions** (`.github/workflows/android.yml`) vid PR/push mot `master` – enhets- och instrumenttester bara när koden de testar ändrats; lint och release-bygget (R8) veckovis i `quality.yml` och vid release (NFR-2, NFR-4, skill `ci-budget`).
