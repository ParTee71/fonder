# Google-uppsättning för Fonder

Engångsuppsättningen i Firebase, Google Cloud och GitHub som krävs för
**Google-inloggning** (TP-6), **Drive-backup** (TP-7 steg 2) och **signerade
releasebyggen**. Samma uppsättning som Dagboken kör.

Ingen av stegen går att göra från kod — de måste klickas igenom i konsolerna.
Ordningen spelar roll: signeringsnyckeln måste finnas *innan* Firebase-appen skapas,
eftersom nyckelns SHA-1 ska registreras där.

| Vad | Var | Krävs för |
|---|---|---|
| Release-keystore (`fonder.jks`) + GitHub Secrets | lokalt + GitHub | signerad release-APK |
| Android-app + `google-services.json` | Firebase Console | Google-inloggning |
| OAuth-medgivandeskärm + Drive API + `drive.appdata` | Google Cloud Console | Drive-backup |

Redan klart i repot: `app/build.gradle.kts` läser signeringsuppgifter från
`local.properties`/miljövariabler, `.github/workflows/release.yml` avkodar keystoren ur
GitHub Secrets, och `gradle/libs.versions.toml` deklarerar alla beroenden. Det som
saknas är kontona och nycklarna nedan.

## Fonder ligger i Dagbokens projekt

Fonder läggs till som en **andra Android-app i det befintliga projektet
`dagboken-711d2`**, inte i ett eget. Det gör steg 4 till en ren verifiering i stället för en
uppsättning — medgivandeskärmen, Drive API och `drive.appdata`-scopet finns redan där och
gäller projektet, inte appen.
Ett Firebase-projekt är samtidigt ett Google Cloud-projekt; en app till i det är en
OAuth-klient till, inget mer.

Två saker följer med på köpet, och båda måste hanteras:

**1. Medgivandeskärmen är gemensam.** Google visar OAuth-medgivandeskärmens *appnamn* när
man loggar in — inte Android-appens namn. Loggar man in i Fonder står det alltså
"Dagboken" i kontoväljaren så länge det är projektets appnamn. Det går att byta till något
neutralt (steg 4), men då ändras texten för båda apparna. Kosmetiskt, men det är ingen bugg
när det dyker upp.

**2. Drive-mappen är gemensam.** `appDataFolder` är per *projekt*, inte per Android-app —
Fonders och Dagbokens säkerhetskopior hamnar i **samma dolda mapp**. Det gör ingen skada
så länge båda apparna alltid namnrymdar sina filer och alltid filtrerar på sitt eget
prefix. Dagboken gör redan det (`name contains 'dagboken-backup-'`, även i sin
gallringsrutin, så den kan inte råka radera Fonders filer). Fonder måste göra samma sak
åt sitt håll — se steg 5, där det är ett krav och inte en rekommendation.

Vill du hellre ha full isolering skapar du i stället ett eget projekt `fonder` i Firebase
Console (Google Analytics behövs inte) och sätter upp steg 4 från grunden i det: skapa
medgivandeskärmen (användartyp **Extern**, appnamn `Fonder`, publiceringsstatus
**Testing**, din adress som testanvändare), aktivera **Google Drive API** och lägg till
scopet `drive.appdata`. Du måste också aktivera Google som inloggningsmetod under
**Authentication → Sign-in method**, som i det gemensamma projektet redan är påslagen.
Allt annat i guiden är detsamma.

---

## Steg 1 — Release-nyckel

Skapa nyckeln **en gång**. Tappar du bort den går appen inte längre att uppdatera för
någon som redan installerat den — Android vägrar installera en APK signerad med en
annan nyckel över en befintlig. Säkerhetskopiera `fonder.jks` och lösenorden utanför
repot (lösenordshanterare, inte molndisken där APK:n ligger).

```bash
keytool -genkeypair -v \
  -keystore app/fonder.jks \
  -alias fonder \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=Fonder, O=partee71, C=SE"
```

`keytool` frågar efter lösenord — använd **samma** för store och nyckel, det är vad
`release.yml` förutsätter. `-validity 10000` (≈27 år) är Googles rekommenderade
minimum för en app som ska gå att uppdatera över tid.

`*.jks` är git-ignorerad; filen ska aldrig checkas in.

### Lokalt bygge (Android Studio)

`local.properties` (git-ignorerad):

```properties
signing.storePassword=<lösenord>
signing.keyAlias=fonder
signing.keyPassword=<lösenord>
```

Utan de här raderna faller `release`-bygget tillbaka på osignerat — `hasSigningCredentials`
i `app/build.gradle.kts` är falskt och `signingConfig` sätts aldrig.

### CI (GitHub Actions)

`release.yml` bygger och signerar i molnet, så en release går att köra från telefonen.
Lägg upp fyra secrets under **Settings → Secrets and variables → Actions**:

```bash
base64 -w0 app/fonder.jks   # macOS: base64 -i app/fonder.jks | tr -d '\n'
```

| Secret | Värde |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | utdata från kommandot ovan |
| `SIGNING_STORE_PASSWORD` | keystore-lösenordet |
| `SIGNING_KEY_ALIAS` | `fonder` |
| `SIGNING_KEY_PASSWORD` | nyckellösenordet |

Workflowet konverterar keystoren till legacy-PKCS12 innan bygget (`keystore.pkcs12.legacy`)
— JDK 17 skriver PKCS12 med ett nyare krypto som AGP:s signerare inte läser. Det steget
finns redan och behöver inget av dig.

Verifiera genom att köra **Release Build** via workflow_dispatch utan att kryssa
`publish_release`: den bygger och laddar upp APK:n som artifact utan att skapa någon tagg.

---

## Steg 2 — SHA-1-fingeravtryck

Firebase kopplar en OAuth-klient till *paketnamn + signeringsnyckel*. Både debugnyckeln
och releasenyckeln måste registreras, annars fungerar inloggning bara i den ena.

**Debug** — nyckeln ligger incheckad i repot (`app/debug.keystore`, lösenord/alias är
Androids standardvärden), så fingeravtrycket är detsamma på alla maskiner och i CI:

```
SHA-1:   B6:9E:C9:B9:E9:C8:89:DD:AD:E9:9F:34:12:E8:53:69:20:B3:33:FB
SHA-256: C1:15:B8:16:51:2C:EC:B4:CE:14:89:D4:A2:D5:BD:B5:72:CE:94:2C:DF:73:8C:05:D3:62:24:B8:AE:0D:67:19
```

Kontrollera vid behov:

```bash
keytool -list -v -keystore app/debug.keystore -storepass android -alias androiddebugkey
```

**Release** — ur nyckeln från steg 1:

```bash
keytool -list -v -keystore app/fonder.jks -alias fonder
```

> Ett SHA-1 kan bara vara registrerat i **ett** Google Cloud-projekt åt gången. Fonders
> debugnyckel är en annan än Dagbokens, så de krockar inte — men försöker du registrera
> samma fingeravtryck i två projekt avvisas det andra.

---

## Steg 3 — Lägg till Fonder-appen i Firebase

Gör hela steget i en följd. Ladda **inte** ner `google-services.json` förrän efter
punkt 6 — filen speglar läget vid nedladdningen, och laddar du ner den innan
releasenyckeln är registrerad saknar den den klienten utan att något klagar.

1. [Firebase Console](https://console.firebase.google.com/) → välj projektet
   **`dagboken-711d2`** (visas som *Dagboken*). Skapa inget nytt projekt.
2. Kugghjulet uppe till vänster → **Projektinställningar** (*Project settings*).
3. Fliken **Allmänt** (*General*) → skrolla till **Dina appar** (*Your apps*) → knappen
   **Lägg till app** (*Add app*) → Android-ikonen.
4. Fyll i:
   | Fält | Värde |
   |---|---|
   | Android-paketnamn | `se.partee71.fonder` |
   | Smeknamn (valfritt) | `Fonder` |
   | SHA-1 för felsökningssigneringscertifikat | `B6:9E:C9:B9:E9:C8:89:DD:AD:E9:9F:34:12:E8:53:69:20:B3:33:FB` |

   Paketnamnet måste stämma **exakt** med `applicationId` i `app/build.gradle.kts` — det
   går inte att ändra efteråt, appen får tas bort och läggas till på nytt. Att Dagboken
   redan ligger i projektet spelar ingen roll; paketnamnen skiljer sig.
5. **Registrera app**. Guiden erbjuder nu nedladdning och visar Gradle-steg — **hoppa över
   båda** (`Nästa` → `Nästa` → `Fortsätt till konsolen`). Gradle-delen är redan förberedd i
   repot, och filen hämtas i punkt 7.
6. Tillbaka på **Projektinställningar → Allmänt → Dina appar**: leta upp kortet för
   `se.partee71.fonder` (inte Dagbokens) → **Lägg till fingeravtryck** (*Add fingerprint*)
   → klistra in **release**-SHA-1 från steg 2 → **Spara**.

   Kortet ska nu lista två fingeravtryck. Har du inte skapat releasenyckeln ännu går det
   att lägga till senare — men då måste `google-services.json` laddas ner på nytt, och
   inloggning fungerar inte i release-APK:n förrän dess.
7. På samma kort: **google-services.json** → ladda ner → lägg filen som
   **`app/google-services.json`** i repot och checka in den (se nedan).
8. Kontrollera att Google-inloggning är påslagen för projektet: vänstermenyn →
   **Build → Authentication → Sign-in method**. **Google** ska stå som *Aktiverad*.
   Det är den redan om Dagboken loggar in, och inställningen är projektgemensam —
   rör den inte. Är den mot förmodan av: aktivera, ange supportmejladress, spara, och
   ladda ner `google-services.json` igen (webbklienten skapas först då).

### Steg 4 blir en verifiering

Medgivandeskärmen, Drive API och `drive.appdata`-scopet gäller projektet och är redan
uppsatta för Dagboken, så steg 4 är bara att kontrollera att så är fallet — det tar en
minut och sparar en 403 som annars är svår att förstå.

### Filen ska checkas in

`app/google-services.json` är **inte** git-ignorerad (till skillnad från keystoren).
`google-services`-pluginet läser den vid varje bygge och genererar bland annat
`R.string.default_web_client_id` — utan filen i repot går CI-bygget inte att köra alls.
Innehållet är projektnummer och OAuth-klient-ID:n, som ändå ligger läsbara i den
publicerade APK:n; Google dokumenterar uttryckligen att den får checkas in. Det som
faktiskt skyddar kontot är SHA-1-kopplingen och Firebase-reglerna, inte filens hemlighet.

Eftersom projektet nu har två appar kan den nedladdade filen innehålla klientposter för
**både** `se.partee71.dagboken` och `se.partee71.fonder`. Det är ofarligt — pluginet
väljer posten vars `package_name` matchar `applicationId` och ignorerar resten. Dagbokens
egen `google-services.json` ska inte röras; den är fortfarande giltig.

Kontrollera vad du faktiskt fick:

```bash
python3 - <<'PY'
import json
d = json.load(open("app/google-services.json"))
print("projekt:", d["project_info"]["project_id"])
for c in d["client"]:
    pkg = c["client_info"]["android_client_info"]["package_name"]
    print(pkg)
    for o in c["oauth_client"]:
        print("   client_type", o["client_type"],
              o.get("android_info", {}).get("certificate_hash", ""))
PY
```

Kraven på posten för `se.partee71.fonder`:

- **två** `client_type: 1` — en Android-klient per registrerat SHA-1 (debug + release).
  `certificate_hash` är samma fingeravtryck som i steg 2, fast gemener utan kolon.
- **en** `client_type: 3` — projektets webbklient, den som blir
  `R.string.default_web_client_id` och som Credential Manager begär ID-token för. Den
  delas med Dagboken; samma sträng i båda filerna är väntat och rätt.

| Vad du ser | Vad det betyder |
|---|---|
| bara **en** `client_type: 1` | release-SHA-1 saknas — punkt 6, ladda sedan ner filen igen |
| ingen `client_type: 3` | Google-inloggning inte aktiverad i projektet — punkt 8 |
| `project_id` ≠ `dagboken-711d2` | du står i fel projekt |
| ingen post alls för `se.partee71.fonder` | appen registrerades med fel paketnamn |

---

## Steg 4 — Google Cloud: medgivandeskärm och Drive API

Firebase-projektet **är** ett Google Cloud-projekt, så allt här är redan uppsatt för
Dagboken och gäller Fonder automatiskt. Verifiera ändå — ett saknat scope visar sig annars
först som en 403 långt in i backup-flödet, utan att något säger varför.

Öppna [Google Cloud Console](https://console.cloud.google.com/) och välj projektet
**`dagboken-711d2`** i projektväljaren högst upp. Kontrollera projekt-ID:t, inte namnet:
flera projekt kan heta "Dagboken".

> Google har döpt om det här området. **APIs & Services → OAuth consent screen** heter i
> den nuvarande konsolen **Google Auth Platform**, med flikarna *Overview*, *Branding*,
> *Audience*, *Clients*, *Data access* och *Verification center*. Båda namnen används
> nedan.

**1. Medgivandeskärmen (Branding + Audience).** Ska redan finnas.

- *Audience* → **Publishing status: Testing**, och din egen Google-adress under
  **Test users**. Står den på *In production* utan att appen är verifierad av Google
  slutar `drive.appdata` fungera — sätt tillbaka den till Testing.
- *Branding* → **App name**. Det är den här strängen som visas i kontoväljaren när man
  loggar in i **båda** apparna. Vill du slippa "Dagboken" i Fonders inloggningsruta byter
  du den till något gemensamt, t.ex. `partee71`. Ändringen slår igenom på Dagboken också,
  och kan ta några minuter.

**2. Drive API.** *APIs & Services → Enabled APIs & services* → **Google Drive API** ska
stå i listan. Saknas den: *Library* → sök `Google Drive API` → **Enable**.

**3. Scopet `drive.appdata`.** *Google Auth Platform → Data access* (tidigare *OAuth
consent screen → Scopes*) → i listan över tillagda scopes ska
`https://www.googleapis.com/auth/drive.appdata` finnas. Saknas det: **Add or remove
scopes** → filtrera på `drive.appdata` → kryssa i → **Update** → **Save**.

**4. Klienterna.** *Google Auth Platform → Clients* ska nu innehålla två nya Android-
klienter för `se.partee71.fonder` (en per SHA-1), utöver Dagbokens och projektets enda
webbklient. De skapades av steg 3 — du ska inte lägga till dem för hand här.

`drive.appdata` ger appen bara dess egen dolda mapp i användarens Drive — inte
användarens filer, och inte läsrättigheter till något appen inte själv skrivit. Så länge
publiceringsstatusen är **Testing** och du står som testanvändare krävs ingen
Google-verifiering. Publicerar du appen bredare senare måste medgivandeskärmen granskas
av Google först.

Scopet begärs vid körning via `Identity.getAuthorizationClient(...)` — separat från
inloggningen, första gången backupen används. Inloggningen i steg 3 räcker alltså inte;
utan scopet svarar Drive med 403.

---

## Steg 5 — Vad implementationen sedan behöver

Beroendena är redan deklarerade i `gradle/libs.versions.toml` men **inte inkopplade** —
det görs i respektive issue, eftersom `google-services`-pluginet fäller bygget så länge
`app/google-services.json` saknas.

**Google-inloggning (TP-6):**

- `app/build.gradle.kts`: `alias(libs.plugins.google.services)` i `plugins { }`,
  och rot-`build.gradle.kts`: `alias(libs.plugins.google.services) apply false`
- Beroenden: `firebase.bom` (platform), `firebase.auth`, `credentials`,
  `credentials.play.services`, `googleid`
- `FirebaseAuthRepository` ersätter `StubAuthRepository` bakom befintligt
  `AuthRepository`-interface. Använd `GetSignInWithGoogleOption` (helsidesväljare), inte
  `GetGoogleIdOption` (bottenark, faller på "Failed to retrieve an ID token" utan cachad
  inloggning).
- Auto Backup i manifestet behöver då exkludera auth-tokens ur `backup_rules.xml` —
  kommentaren där utgår i dag från att inget känsligt finns lagrat.

**Drive-backup (TP-7 steg 2):**

- Beroenden: `google.auth.play.services`, `google.api.client.android`,
  `google.api.services.drive`
- `DriveBackupRepository` implementerar samma `BackupRepository`-interface som
  `LocalBackupRepository` — kontraktet är en **sträng**, så formatet (`BackupPayload`/
  `BackupSerializer`) och rundturstestet är oförändrade. Bara transporten byts.
- **`appDataFolder` delas med Dagboken** (samma Cloud-projekt). Det är ett datasäkerhetskrav
  (regel 1), inte en stilfråga:
  - filnamnen prefixas `fonder-backup-`
  - **varje** `files().list()` filtrerar på det prefixet —
    `name contains 'fonder-backup-' and trashed = false`, aldrig en obefiltrerad listning
  - gallringen ("behåll de N senaste") körs mot den filtrerade listan. Dagboken gör redan
    så åt sitt håll; en obefiltrerad `drop(n).forEach { delete }` här skulle radera
    Dagbokens säkerhetskopior utan väg tillbaka.
  - `trashed = false` behövs för att papperskorgade filer ligger kvar i mappen och annars
    matchar namnfrågan — en raderad backup skulle räknas som "senaste".
- `proguard-rules.pro` behöver keeps för Google API-klienten, som annars krymps bort i
  release:

  ```proguard
  -keep class com.google.api.** { *; }
  -keep class com.google.apis.** { *; }
  -dontwarn com.google.api.**
  -dontwarn com.google.apis.**
  -dontwarn org.apache.http.**
  ```

  `app/build.gradle.kts` har redan `packaging`-exkluderingarna
  (`META-INF/DEPENDENCIES`, `INDEX.LIST`, `*.SF/DSA/RSA`) som Google API-klienten kräver.

---

## Felsökning

| Symptom | Orsak | Åtgärd |
|---|---|---|
| `Missing google-services.json` vid bygge | filen saknas eller är ignorerad | lägg i `app/`, kontrollera att den är incheckad |
| `default_web_client_id` går inte att resolva | `client_type: 3` saknas i JSON-filen | aktivera Google under Authentication, ladda ner filen på nytt |
| Inloggning funkar i debug men inte i release | releasenyckelns SHA-1 saknas | lägg till fingeravtrycket, ladda ner ny JSON |
| `GetCredentialException: no credentials available` | ingen SHA-1-koppling, eller inget Google-konto på enheten | kontrollera `client_type: 1` för rätt fingeravtryck |
| Drive svarar 403 | Drive API inte aktiverat, eller scopet inte tillagt | steg 4.2 och 4.3 |
| Drive-auktoriseringen frågar om och om igen | appen står som Testing → token kort livslängd | förväntat; scopet begärs på nytt vid behov |
| Kontoväljaren säger "Dagboken" när man loggar in i Fonder | gemensam medgivandeskärm, appnamnet är projektets | förväntat; byt *Branding → App name* i steg 4.1 om det stör |
| Dagbokens backupfiler dyker upp i Fonders lista | gemensam `appDataFolder`, ofiltrerad listning | prefixfiltrera frågan — se steg 5 |
| SHA-1:t avvisas: "already in use by another project" | fingeravtrycket är registrerat i ett annat Cloud-projekt | ta bort det där först; ett SHA-1 kan bara ligga i ett projekt |
| APK:n går inte att installera över en tidigare | annan signeringsnyckel | samma `fonder.jks` som förra släppet, alltid |
