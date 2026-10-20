---
name: data-privacy-security
description: 'Integritet och säkerhet i Fonder — innehav, belopp, transaktioner och avräkningsnotor är ekonomisk persondata som aldrig loggas eller skickas till nätverkskällorna; inga nycklar i repot. Ladda alltid vid loggning, backup, Drive, import, nätverkskällor, inloggning, hemligheter, signering eller release/minify. Trigger-ord: integritet, säkerhet, GDPR, persondata, PII, logga, hemlighet, keystore, jks, google-services, token, Drive, appDataFolder, Avanza, Handelsbanken, Riksbanken, R8, ProGuard, allowBackup.'
---

# Datasäkerhet & integritet

Fonder lagrar **ekonomisk persondata**: vilka fonder användaren äger, antal andelar, belopp,
transaktioner, importerade avräkningsnotor och exportfiler, riskprofil och val. Det är inte
GDPR art. 9-data som i Dagboken, men det är privat och ska behandlas som hemligt som standard.
Datan ligger i Room (`fonder.db`) och DataStore lokalt, och i användarens Drive (backup).

## Regler

### 1. Logga aldrig innehav, belopp eller transaktioner
Inga `Log.*`/`println` med belopp, antal andelar, transaktioner, filinnehåll från en import,
e-post, konto-ID eller backupens JSON. Logga som mest undantagsklass och antal. Ingen analytics
eller crash-rapportering som skickar innehåll.

> **Avvikelse i dag:** `FundPriceRepository` loggar `fundId`, ISIN och fondnamn (`Log.w` vid
> misslyckad hämtning) och `FundMetadataRepository` fondbolag. Inga belopp, men listan över
> loggade fonder avslöjar innehavet. Release strippar inte loggar (`proguard-rules.pro` saknar
> `-assumenosideeffects` för `android.util.Log`). Ska åtgärdas: ta bort identifierarna ur
> meddelandena eller strippa `Log` i release.

### 2. Nätverkskällorna får aldrig skicka användardata
`data/network` hämtar kurser och metadata från Avanzas fond-API, Handelsbankens fondlista och
Riksbankens SWEA-API (växelkurser) – utan inloggning. Anropen får bara innehålla det källan
behöver för att svara: ISIN, källans fond-id, fondnamn vid sökning, datumintervall, valuta.
**Aldrig** belopp, andelar, transaktioner, användarens e-post, Google-token eller annan
identifierare. Ingen ny källa med inloggning eller användarkonto utan uttryckligt beslut.

Samma gäller utvecklingsverktygen: MCP-servern `avanza` (`.mcp.json`) och `tools/fondmatte`
får bara fondkatalogens data, enstaka ISIN och påhittade fixturer – aldrig användarens innehav,
belopp eller transaktioner, och MCP-serverns BankID-läge används inte (skill `fondmatematik`).

### 3. Backup och Drive
- Lokal backup (`BackupRepository`) skrivs bara på uttrycklig användarhandling till en fil
  användaren väljer (`fonder-backup-<datum>.json`, dokumentväljaren).
- Molnbackupen (`DriveBackupRepository`) använder minsta scope **`DRIVE_APPDATA`** – aldrig bredare
  Drive-scope.
- **`appDataFolder` delas med Dagboken**: Fonder ligger i Dagbokens Google Cloud-projekt
  (`dagboken-711d2`, se `docs/GOOGLE-SETUP.md`), och mappen är per projekt, inte per app. Filerna
  skiljs **bara** med prefixet `fonder-backup-` (`DriveBackupFiles.PREFIX`/`isOurs`). Varje läsning,
  listning och radering (gallringen i `DriveBackupFiles.staleFileIds`) ska filtrera på prefixet och
  vara enhetstestad – Fonder får aldrig röra Dagbokens filer.
- Androids Auto Backup är **på** (`allowBackup="true"`) men begränsad med include-listor i
  `res/xml/backup_rules.xml` och `data_extraction_rules.xml` till `fonder.db` och `datastore` – så
  Firebase Auth-sessionen följer aldrig med. Ny fil som ska med läggs till i **båda** listorna;
  skill `data-safety-backup` gäller för backup-kontraktet.

### 4. Hemligheter checkas aldrig in
- Git-ignorerade: `*.jks`, `*.keystore`, `local.properties`. Undantag: `app/debug.keystore`
  (Androids kända standardlösenord, avsiktligt incheckad för stabila debug-snapshots).
- Release-nyckeln **har** checkats in en gång (commit 9403cf5) – därför fäller grinden
  "Ingen signeringsnyckel i repot" i `android.yml` varje `*.jks`/`*.keystore`/`*.p12`/`*.pem`
  utom debug-nyckeln. Ta aldrig bort eller försvaga den.
- Service account-JSON, OAuth-klienthemligheter och tokens checkas aldrig in och klistras aldrig
  in i chatten. Be aldrig användaren om en nyckel; skriv aldrig ut ett värde.
- **`app/google-services.json` är incheckad** och är klientkonfiguration (projektnummer,
  OAuth-klient-ID:n), ingen hemlighet – skyddet är SHA-1-kopplingen till signeringsnyckeln.
  Lägg aldrig nya hemligheter bredvid den.
- Signering i CI: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
  `SIGNING_KEY_PASSWORD` som GitHub-secrets; `release.yml` raderar keystoren efter bygget.
  Lokalt läses lösenorden från `local.properties` eller miljövariabler, aldrig hårdkodat.

### 5. Release minifieras
`isMinifyEnabled = true` + `isShrinkResources = true` på release. Bibliotek som reflekterar
(Room, kotlinx.serialization, Google API-klienten) har keep-regler i `app/proguard-rules.pro`;
ett nytt sådant beroende får sina regler i samma PR. R8 verifieras veckovis och på begäran
(`quality.yml`) och vid release – kör `quality.yml` på PR-grenen när beroendet läggs till
(skill `ci-budget`, NFR-2).

### 6. Autentisering och behörigheter
- Inloggning via `AuthRepository` (Credential Manager + Firebase, skill `android:firebase-auth` och `android-projekt` → "Inloggning") – aldrig
  direkt från UI. Avbruten inloggning är inte ett fel.
- Appen begär i dag bara `INTERNET`. Nya behörigheter bara vid tydligt behov och först när de behövs.

## Checklista

- [ ] Inga loggar med belopp, andelar, transaktioner, importinnehåll eller kontouppgifter.
- [ ] Nätverksanrop innehåller bara fondidentifierare, datum och valuta.
- [ ] Drive: bara `DRIVE_APPDATA`, allt filtrerat på `fonder-backup-` och testat.
- [ ] Ny persisterad fil: med i båda Auto Backup-listorna och i backup-kontraktet – eller medvetet utanför.
- [ ] Inga hemligheter incheckade, utskrivna eller efterfrågade.
- [ ] Nytt bibliotek har keep-regler så R8 inte går sönder.
- [ ] Ny exporterad komponent eller ny Intent-hantering granskad med Googles skill `android:android-intent-security`
  (projektets delar: `android-projekt` → "Intent-säkerhet").
