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
| Firebase-projekt + Android-app + `google-services.json` | Firebase Console | Google-inloggning |
| OAuth-medgivandeskärm + Drive API + `drive.appdata` | Google Cloud Console | Drive-backup |

Redan klart i repot: `app/build.gradle.kts` läser signeringsuppgifter från
`local.properties`/miljövariabler, `.github/workflows/release.yml` avkodar keystoren ur
GitHub Secrets, och `gradle/libs.versions.toml` deklarerar alla beroenden. Det som
saknas är kontona och nycklarna nedan.

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

## Steg 3 — Firebase-projekt

1. [Firebase Console](https://console.firebase.google.com/) → **Lägg till projekt** →
   namn `fonder`. Google Analytics behövs inte.
2. **Lägg till app → Android**
   - Paketnamn: `se.partee71.fonder` (måste stämma exakt med `applicationId`)
   - Smeknamn: `Fonder`
   - SHA-1: klistra in **debug**-fingeravtrycket från steg 2
3. Ladda ner `google-services.json` → lägg i **`app/google-services.json`**.
4. **Projektinställningar → Dina appar → Lägg till fingeravtryck** → klistra in
   **release**-SHA-1. Ladda ner `google-services.json` **igen** — den första saknar
   releasenyckelns klient.
5. **Authentication → Kom igång → Sign-in method → Google → Aktivera.**
   Ange en supportmejladress. Spara.

Ett eget projekt (i stället för att lägga Fonder som andra app i `dagboken-711d2`) håller
användare, kvoter och medgivandeskärm skilda mellan apparna. Det kostar bara att
medgivandeskärmen i steg 4 måste sättas upp en gång till.

### Filen ska checkas in

`app/google-services.json` är **inte** git-ignorerad (till skillnad från keystoren).
`google-services`-pluginet läser den vid varje bygge och genererar bland annat
`R.string.default_web_client_id` — utan filen i repot går CI-bygget inte att köra alls.
Innehållet är projektnummer och OAuth-klient-ID:n, som ändå ligger läsbara i den
publicerade APK:n; Google dokumenterar uttryckligen att den får checkas in. Det som
faktiskt skyddar kontot är SHA-1-kopplingen och Firebase-reglerna, inte filens hemlighet.

Kontrollera att den nedladdade filen innehåller båda klienttyperna:

```bash
python3 - <<'PY'
import json
d = json.load(open("app/google-services.json"))
for c in d["client"]:
    print(c["client_info"]["android_client_info"]["package_name"])
    for o in c["oauth_client"]:
        print("  client_type", o["client_type"], o.get("android_info", {}).get("certificate_hash"))
PY
```

- `client_type: 1` — Android-klient, en per registrerat SHA-1 (ska bli **två**: debug + release)
- `client_type: 3` — webbklient, den som blir `default_web_client_id` och som
  Credential Manager begär ID-token för

Saknas `client_type: 3` har Firebase inte skapat någon webbklient — den dyker upp när
Google-inloggning aktiverats i steg 3.5. Ladda ner filen på nytt efteråt.

---

## Steg 4 — Google Cloud: medgivandeskärm och Drive API

Firebase-projektet **är** ett Google Cloud-projekt. Öppna
[Google Cloud Console](https://console.cloud.google.com/) och välj projektet `fonder`.

1. **APIs & Services → OAuth consent screen**
   - Användartyp **Extern**
   - Appnamn `Fonder`, supportmejl och utvecklarmejl = din adress
   - Publiceringsstatus: låt den stå kvar på **Testing**
   - **Test users → Lägg till** din egen Google-adress
2. **APIs & Services → Bibliotek → Google Drive API → Aktivera**
3. **OAuth consent screen → Data access (Scopes) → Lägg till**
   `https://www.googleapis.com/auth/drive.appdata`

`drive.appdata` ger appen bara dess egen dolda mapp i användarens Drive — inte
användarens filer, och inte läsrättigheter till något appen inte själv skrivit. Så länge
publiceringsstatusen är **Testing** och du står som testanvändare krävs ingen
Google-verifiering. Publicerar du appen bredare senare måste medgivandeskärmen granskas
av Google först.

Scopet begärs vid körning via `Identity.getAuthorizationClient(...)` — separat från
inloggningen, första gången backupen används. Inloggning i steg 3 räcker alltså inte;
utan det här steget svarar Drive med 403.

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
| APK:n går inte att installera över en tidigare | annan signeringsnyckel | samma `fonder.jks` som förra släppet, alltid |
