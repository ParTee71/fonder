# Fonder – Android

App för att hålla koll på fonder: ladda kurser, registrera transaktioner, räkna ut värde
och visa utveckling i diagram — med molnbackup via Google Drive.

> Version: 0.81.1 (följer `versionName`/[KRAVLISTA.md](KRAVLISTA.md))

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
- [x] Realiserat resultat (genomsnittsmetoden, som K4) och avgifter vid försäljning, på säljraderna i Händelser (#10, sedan 0.62.0 ingen egen vy)
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

- Android Studio (senaste), JDK 21, Android SDK API 37 (compileSdk), minSdk 30

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
telefonen. Releasen görs bara från `master` när CI (`ci-ok`) är grönt på commiten, och kör lint
och instrumenttesterna om de inte redan körts gröna för samma kod (skill `ci-budget`). Se [docs/GOOGLE-SETUP.md](docs/GOOGLE-SETUP.md) för hur nyckeln skapas och
läggs upp.

---

## Versionshistorik

Raden skrivs i funktions-PR:en som höjer versionen (CLAUDE.md → PR-flöde) och sammanfattar det
**användarsynliga** som kommit sedan föregående version. Den är avsiktligt kort — motiven
bakom besluten står i [KRAVLISTA.md](KRAVLISTA.md), som uppdateras löpande när varje
ändring landar. Tabellen börjar vid 0.51.0; för allt före det är KRAVLISTAs `Historik`
den fullständiga källan.

| Version | Innehåll |
|---|---|
| 0.81.1 | Analysens periodrader mäter inte längre från en månader gammal startkurs: saknas kurs inom 5 dagar före periodens start visas otillräcklig data, en äldre slutkurs anges som "per ÅÅÅÅ-MM-DD" och CAGR räknas mellan de två kursdagarna (#198). |
| 0.81.0 | Facit jämför sälj- och köpfonden på samma dag: varje förslag sparar dagen båda kurserna gäller och mäts till den senaste dag båda fonderna har en kurs ("mätt till ÅÅÅÅ-MM-DD · N dagar", annars "Ej utvärderad ännu"). Summeringen visar träffandel ("3 av 5 bättre") med kronsumman i stället för ett snitt över förslag med olika ålder, en ny del "Per horisont" (under 1 månad, 1–6 månader med median, över 6 månader i årstakt) och median per plats i planen. Databasversion 20 (#197). |
| 0.80.1 | Volatilitet och Sharpe-kvot i Analys räknas nu i ett fast fönster: de senaste 3 åren av fondens kurshistorik, eller det senaste året om treårsfönstret är för kort, för glest (minst 60 % av handelsdagarna måste finnas) eller har hål över en månad – oavsett hur länge du ägt fonden – och fönstret står i etiketten ("Volatilitet (3 år)"). Luckor på mer än 5 dagar i kurshistoriken räknas inte längre som en enda dag, och Sharpe-kvotens avkastning är fondens totala kursutveckling i fönstret i årstakt, så att en rörelse över en lucka inte tappas. En fond med kortare kurshistorik än ett år hos källan visar "otillräcklig data" för volatilitet och Sharpe. En fond vars kurs inte rört sig visar Sharpe som "Ej definierad (ingen kursrörelse)". Analysens kurshistorik hämtas tre år och 45 dagar bakåt (tidigare 10 dagar), så att treårsfönstret ryms även när senaste kursen är några veckor gammal. Sharpe-förklaringen säger att riskfri ränta är satt till 0 % (#195). |
| 0.80.0 | Säljsignalerna S1/S2, 200-dagarssnittet i Fonddetaljs diagram och riskmåtten räknas på fondens kurs upp till tre år bakåt, även för en fond köpt nyligen eller bara bevakad – tidigare gav en fond köpt för mindre än ~9,5 månader sedan "otillräcklig data" för S2 och ingen snittlinje. Periodraderna i Analys visar därmed fondens kursutveckling även för perioder som börjar före köpet, medan kronorna, "sedan köp", GAV och diagrammens "Allt" fortfarande utgår från köpet. Kurshistoriken hämtas en gång tre år bakåt per fond (ca 300 kB från fondlista), därefter bara de senaste 60 dagarna; en fond yngre än tre år hämtas helt högst en gång per appstart (#214). |
| 0.79.4 | 200-dagarssnittet (S2 i Analys och snittlinjen i Fonddetaljs diagram) räknas nu över 290 kalenderdagar (≈ 200 handelsdagar) i stället för 200 kalenderdagar (~137 handelsdagar), så att det stämmer med texterna i appen. Snittet kräver därmed ca 290 dagars historik: en fond som ägts kortare tid än ca 9,5 månader visar S2 och snittlinjen först då (otillräcklig data dessförinnan). |
| 0.79.3 | Kronbeloppen i periodraderna (dag/vecka/månad på Hem och Portfölj, i år/3 mån/1 år/3 år i Analys) är nu värdeförändringen på de andelar som faktiskt ägts, dag för dag – tidigare räknades kursrörelsen på dagens antal andelar, så andelar köpta mitt i perioden fick "avkastning" från före köpet. "Sedan köp" i kronor är positionens vinst mot anskaffningsvärdet, samma tal som GAV-raden (#193). |
| 0.79.2 | "Sedan köp", CAGR och säljsignalerna räknas från positionens startdatum på alla tre skärmarna – Hem och Portfölj räknade tidigare från fondens första transaktion, så en fond som sålts av helt och köpts igen kunde visa andra siffror och annan signalfärg än i Fonddetalj (#194). |
| 0.79.1 | Anskaffningsvärde och realiserat resultat räknas med **genomsnittsmetoden** (Skatteverkets regel för fondandelar, K4) i stället för FIFO, och köpavgiften ingår i anskaffningsvärdet (#192). Påverkar säljradernas resultat i Händelser, "Realiserat resultat i år", innehavens inköpsvärde, GAV och "Varav orealiserat". Inget nytt sparas. |
| 0.79.0 | Totalkortet på Hem och Portfölj visar **totalresultatet** (värde + uttag − insättningar, avgifter inräknade, även helt sålda fonder) som huvudtal, **årsavkastning (pengaviktad, XIRR)** efter ett år och orealiserat resultat på kvarvarande andelar som underordnad rad, med en utfällbar förklaring (#191). Tidigare dolde kortet realiserade förluster och avgifter. |
| 0.78.3 | Kopierad kod sammanslagen till delade delar – analysen av innehaven, importradernas huvud och testernas fakes och fixturer – och kopieringskontrollen (`cpdCheck`) har ingen baslinje längre (#175). Appen ser ut och fungerar som förut. |
| 0.78.2 | Skärmarna skickar händelser till sina ViewModels (sealed events och `onEvent()`), och alla compose-rules-regler är påslagna i kodstilen (#176). Appen ser ut och fungerar som förut. |
| 0.78.1 | Domänen i en egen ren Kotlin-modul, `:core` (#171): modellerna och de rena beräkningarna med sina tester. Appen fungerar som förut. |
| 0.78.0 | Fonddetalj på android-ui:s detaljram (#174): fondens namn står i den lilla toppraden, laddningen visas mitt på skärmen och ett läsfel har "Försök igen"; en fond som inte finns längre sägs ut. Avsnitten står 12 dp isär, "Ska du byta?" är en avsnittsrubrik i 20 sp och grupprubrikerna i versaler. |
| 0.77.0 | Knappar, kort, rader och avsnittsrubriker från android-ui 0.8.0 (#174): Fonders egna AppButton, AppCard, ItemRow och StateChip är borta. Besluts rader har rubriken i 16 sp och chipet med ursprunget ovanför, profilraden står på två rader, avsnittsrubrikerna är 20 sp med antalet i en pill och Facit-raden har en pil; Inställningars kortrubriker är 16 sp. Översiktens kugghjul är 48 dp. |
| 0.76.0 | Fälten och dialogerna från android-ui 0.7.0 (#174): textfälten har kortets yta och ljusare kontur, datum visas som "tis 20 okt 2026" och väljs i Material 3-datumväljaren, dialogerna har 18 dp hörn och ljusare yta, och strömbrytaren *Bara indexfonder* har bibliotekets rad. Riskprofilens andelsfält har etiketten "Andel" (UI-13). |
| 0.75.0 | Listorna på android-ui 0.7.0 (#174): Händelser och Fondsök står på listramen – Händelser med stor rubrik som fälls ihop, filtret och resultatkortet kvar, Fondsök med fälten fasta och träffarna i ett kort. Läsfel på Händelser, Innehav, Beslut, Facit, Fondsök, Fonddetalj och Översikt har "Försök igen". Tomma Översikten, Innehav och Händelser har knappar till fondsöken, transaktionsformuläret, importerna och säkerhetskopian. Bakåtpilen följer temat. |
| 0.74.0 | Transaktionsformuläret har Spara uppe i toppraden, aktiv först när något ändrats (ett förifyllt sälj från Följ direkt); bakåt med osparade ändringar frågar "Släng ändringar?", fel visas under fältet, ett sparfel som snackbar och ett läsfel med "Försök igen". Vid sälj står innehavet med "Sälj alla andelar", och fler andelar än innehavet går inte att spara. Utan bevakade fonder leder "Bevaka en fond" till fondsök. Påminnelsen om säkerhetskopia på Hem blinkar inte längre bort när en ändring kommer medan den räknas om (NAV-4, TRX-5, TRX-6, UI-15, NFR-6, HEM-12, #174). |
| 0.73.0 | Riskprofilen har Spara uppe i toppraden, aktiv först när något ändrats och summan är 100 %; bakåt med osparade ändringar frågar "Släng ändringar?", ett sparfel visas som snackbar och ett läsfel har "Försök igen" (SET-3, UI-15, NFR-6, #174). |
| 0.72.6 | Intern: komponentbiblioteket android-ui 0.6.0; skalet stöder skärmar byggda på bibliotekets ramar (ingen extra rubrik, bakgrundsindikatorn i ramens topprad, tangentbordet tas inte med två gånger), och debug-bygget har ett komponentgalleri under Inställningar. Ingen synlig ändring i release-bygget (#174). |
| 0.72.5 | Ett misslyckat sparande i transaktionsformuläret sägs under Spara i stället för att appen kraschar, formuläret står kvar med inmatningen, och Spara är släckt medan transaktionen sparas (NFR-6, #185). |
| 0.72.4 | Intern: Hem, Fonddetalj och Pågående byte har sitt lästa innehåll som android-ui:s `DetailUiState`, riskprofilen går via `EditorState`/`EditorLoader`, och inloggningens och säkerhetskopians fel tas ur samma felmappning med oförändrade texter (NFR-6, #173). En detaljskärm eller riskprofil som inte går att läsa visar ett fel i stället för att appen kraschar, ett misslyckat sparande av riskprofilen sägs under Spara, och Spara är släckt medan profilen läses eller sparas. Ingen synlig ändring i övrigt. |
| 0.72.3 | Intern: listskärmarnas tillstånd (Händelser, Beslut, Facit, Fondsök, Portfölj) och felen går via komponentbiblioteket android-ui (`ListUiState`, `UiError`), och datalagrets fel mappas på ett ställe till en svensk text per fel (NFR-6, #173). En lista som inte går att läsa ur databasen visar ett fel i stället för att appen kraschar. Ingen synlig ändring i övrigt. |
| 0.72.2 | Intern: temat går via komponentbiblioteket android-ui (`AppTheme`) med Fonders palett, former och Space Grotesk-typskala; avstånden i skärmarna tas ur `Spacing`, och regel 4-kontrollen fäller nya literala avstånds-dp. Ingen synlig ändring. |
| 0.72.1 | Intern: bara `di/` väljer coroutine-dispatcher (`DispatchersModule`), övrig kod får den injicerad och importens filläsning flyttad från skärmen till ViewModel; regel 4-kontrollen fäller nya `Dispatchers.` utanför `di/`; listor, kartor och mängder i skärmtillstånden är oföränderliga och tillstånden märkta `@Immutable`, och listorna har `contentType`; navigeringen efter sparat i riskprofilen och transaktionsformuläret kvitteras till ViewModel, så att den sker exakt en gång även vid rotation, och regel 4-kontrollen fäller `Channel(` och `.receiveAsFlow(` i `ui/`. Appen riktar sig mot Android 16 (targetSdk 36). Effekter anropar alltid den senaste callbacken. Ingen synlig ändring. |
| 0.72.0 | **Handelsbankens fondlista fungerar igen** (TP-18, NAV-3, TP-13, ANA-13, #155): Handelsbankens fondlista släpper bara in webbläsare. Appen löser nu kontrollen själv i en osynlig webbläsare och använder nyckeln den får i flera dygn. Fondsök, importens fondmatchning och kollen av vilka fonder som går att köpa hos Handelsbanken fungerar då igen. |
| 0.71.1 | **Köpbara alternativ igen** (TP-21, ANA-13, #153): Pågående byte sa att ingen av de bästa fonderna gick att köpa hos Handelsbanken på nästan varje risknivå. Det var Handelsbankens fondlista som svarade med en robotkontroll, och appen läste det som "går inte att köpa" och kom ihåg det i 30 dygn. Nu räknas ett sådant svar som att köpbarheten inte gick att kontrollera, och de felaktigt sparade neja glöms vid uppdateringen så fonderna prövas igen. |
| 0.71.0 | **Pågående byte – välj nivå och bara indexfonder** (ANA-12, ANA-13, SLD-5, BYT-2, BYT-3, #150): på Pågående byte väljer du vilken risknivå likviden ska köpa på. När du registrerar ett fristående sälj är nivån förvald efter din riskprofil – den nivå som ligger längst under målet, räknat som om likviden inte låg någonstans än – och utan riskprofil är den säljfondens egen nivå som förut; ett byte ur bytesplanen behåller planens nivå. Väljer du en annan nivå byts appens förslag ut mot förslag på den nya nivån, medan de fonder du själv lagt till ligger kvar, och bytesplanen räknas om eftersom likviden räknas på den valda nivån. Med "Bara indexfonder" föreslås bara indexfonder; finns ingen köpbar indexfond på nivån säger skärmen det. Nivån och filtret följer med i säkerhetskopian. |
| 0.70.0 | **Pågående byte – varför alternativen saknas** (ANA-13, SLD-5, #149): en bevakning som startades utan känd risknivå får nivån i efterhand när skärmen öppnas — säljfondens risknivå slås upp och sparas, och sedan hämtas förslagen. Är listan ändå tom säger skärmen varför: risknivån är okänd, ingen av de bästa fonderna på nivån går att köpa hos Handelsbanken, eller det gick inte att kontrollera just nu — i det sista fallet med knappen Försök igen. |
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

`Compose → ViewModel (StateFlow<UiState>) → Repository → Room / DataStore`. Paketträd, dataflöde,
kurskällor, datamodell och byggkonfiguration står i [ARKITEKTUR.md](ARKITEKTUR.md).

---

## Tester

- **Enhet (JVM):** `domain/` i modulen `:core` (`./gradlew :core:test`; PortfolioCalc, RealizedGainCalculator — genomsnittsmetoden inkl.
  delförsäljning, köp- och säljavgift, FundAnalysisCalc — periodavkastning, CAGR,
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
