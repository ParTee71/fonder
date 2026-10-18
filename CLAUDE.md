# Fonder – projektets grundregler

> Fondbevakning (Android/Kotlin · Compose · MVVM · Hilt · Room · DataStore).
> Håller koll på fonder: kurser, transaktioner, värde och utveckling — med molnbackup.
> Kravspecifikation: [KRAVLISTA.md](KRAVLISTA.md) · Arkitektur: [README.md](README.md)

Den här filen laddas automatiskt vid **varje** uppgift. Den gäller alltid, för alla
ändringar, oavsett storlek. De detaljerade reglerna ligger som skills i
`.claude/skills/` — den här filen är kontraktet som binder ihop dem.

**Svarslängd:** håll chattsvar minimala. Inga sammanfattningar, ingen upprepning av vad
som gjordes, inga rubriker/listor om inte nödvändigt. Kod, commits och PR-beskrivningar
skrivs normalt.

Visuell identitet: **Nordisk blå** (#1F4E79) med **kopparaccent** (#B4552F ljust / #FFB59A mörkt), M3 Expressive, rubriker i
**Space Grotesk**, belopp med **tabulära siffror**. Fast palett — ingen dynamisk färg.

---

## De fem icke-förhandlingsbara reglerna

Vid **varje** kodändring ska du, innan du anser arbetet klart, gå igenom alla fem:

### 1. Datasäkerhet — backup/restore får aldrig tappa data
All användardata (fonder, transaktioner, inställningar) måste överleva en
**backup → restore-rundtur** utan förlust. Lägger du till eller ändrar ett persisterat
fält/en entitet ska det in i hela backup-kedjan *och* täckas av rundturstest.
→ Skill: **data-safety-backup** · **room-migrations**.

### 2. Tester på alla nivåer
Ingen beteendeändring utan att tester läggs till eller uppdateras på rätt nivå
(enhet / instrument / migrering). Befintliga tester som påverkas ska uppdateras,
aldrig tas bort för att "bli gröna". **Flaky tester är inte tillåtna** — kör aldrig
bara om ett test som ibland faller för att få grönt. Hitta grundorsaken och åtgärda
den så att testet blir deterministiskt. Ett test som ibland faller är en bugg som
ska fixas, inte ignoreras.
→ Skill: **testing-strategy**.

### 3. Kraven hålls aktuella
Ändrar du synligt beteende ska [KRAVLISTA.md](KRAVLISTA.md) uppdateras i samma
ändring — nytt krav läggs till, borttaget beteende stryks med
`~~…~~ *(borttaget)*`. Versionstabellen i README och `versionName` följer med.
→ Skill: **requirements-kravlista**.

### 4. Återanvänd generiska komponenter
Behöver du ett diagram, ett kort, en tom-tillståndsvy, en datum/tid-rad osv. — använd
den befintliga delade komponenten i `ui/components/` eller `ui/diagram/`. Bygg inte en ny
variant av något som redan finns; utöka den delade komponenten i stället.

**Kontrolleras automatiskt:** hooken `regel4-check` ger besked direkt efter varje filändring;
`UiConsistencyTest` (Konsist) och `cpdCheck` (copy-paste-detektor) fäller bygget. Förbjudna mönster
finns i en fil (`ui-forbidden.txt`), undantag bara via `ui-allowlist.txt` med motivering och
användarens ok i PR:en. Brotten och kopiorna från före kontrollerna står som baslinjer som bara får
krympa (`tools/regel4-baseline.mjs`, `config/cpd-baseline.txt`).
→ Skill: **shared-ui-components**.

### 5. CI-budget
En PR ska kosta **~8 Actions-minuter**, plus instrumenttesterna på emulatorn bara när PR:en
rör koden de testar. Release-bygge (R8) och lint hör inte till PR-flödet; de körs på begäran,
veckovis och vid release. Nya beroenden eller plugins får inte fördubbla byggtiden – ange
effekten i PR:en. Att slippa köra ett test är aldrig ett skäl att inte skriva det.

**Läget i dag:** efter CI-etappen uppskattat **~2 minuter** för en dokument-PR och **~6–7
minuter** för en kod-PR (`changes` + `build-and-test` + `ci-ok`), plus emulatorn (~10–15) bara
när `app/src/main/**`, `app/src/androidTest/**` eller byggfilerna ändrats. Tidigare ~19–20
minuter per PR-push. Ingen ändring får göra det dyrare.
→ Skill: **ci-budget**.

---

## Processregel: mockup före ny GUI

Utvecklingen sker mest från telefonen, och ett bygge för att "se hur det blev" är dyrt.

- Ny skärm, ny delad komponent eller tydligt ändrat utseende/beteende → visa först en
  **mockup** för användaren (Design-canvas-artifact) och vänta på ok innan koden skrivs.
- Mockupen byggs av befintliga komponenter i `ui/components/` och `ui/diagram/`, i Fonders
  designspråk (nordisk blå, koppar, Space Grotesk, tabulära siffror), med riktiga svenska
  texter och påhittad data – aldrig användarens riktiga innehav. Något som saknas i
  katalogen märks "NY KOMPONENT".
- Länken läggs i issuet under rubriken **Design**.
- Tumregel: syns förändringen i en tumnagel → mockup först. Padding, ordval eller
  ikonbyte → beskrivningen i PR:en räcker.

→ Skill: **mockup**.

---

## Innan du skriver ny kod (snabb checklista)

1. **Sök efter befintligt mönster.** Det finns nästan alltid en sibling-ViewModel,
   en delad komponent eller en repository-metod som visar hur projektet gör.
   `Grep`/läs grannfiler först (se skill `android-dev`).
2. **Följ arkitekturen** även om en genväg vore enklare: MVVM, `StateFlow<UiState>`,
   Repository som single source of truth, Hilt-DI, fel mappas i repository-lagret.
3. **Svenska** i allt användarvänt: UI-strängar (i `strings.xml`), commit-meddelanden
   får vara svenska, kod/identifierare på engelska enligt befintlig stil.

## Innan du anser dig klar (slutkontroll)

- [ ] **Datasäkerhet:** nya/ändrade persisterade fält finns i backup-kedjan och har rundturstest. (regel 1)
- [ ] **Tester:** lagt till/uppdaterat på alla berörda nivåer; grönt lokalt eller i CI. (regel 2)
- [ ] **Krav:** KRAVLISTA.md (och ev. README/version) speglar ändringen. (regel 3)
- [ ] **Återbruk:** ingen ny komponent som dubblerar en befintlig delad. (regel 4)
- [ ] **CI-budget:** inga nya dyra steg i PR-flödet; byggtidseffekt angiven vid nya beroenden. (regel 5)
- [ ] **Arkitektur:** följer projektets etablerade mönster (skill `android-dev`).
- [ ] **GUI:** mockup godkänd; skärmdumpar (ljust + mörkt) i PR:en när Roborazzi finns.
- [ ] **Granskning:** agenten `granskare` och `/code-review` har körts på diffen – och igen på rättningarna efter dem.

---

## Bygg & test (kommandon)

```bash
./gradlew :app:compileDebugKotlin            # kompilera
./gradlew :app:testDebugUnitTest             # enhetstester (JUnit/MockK/Turbine), Robolectric, UiConsistencyTest
./gradlew :app:verifyRoborazziDebug          # samma tester + skärmdumpsjämförelse mot app/src/test/screenshots/ (som CI)
./gradlew :app:recordRoborazziDebug          # spela in referenserna – bara vid avsiktlig visuell ändring, checka in bilderna
./gradlew :app:compileDebugAndroidTestKotlin # kompilera instrumenttester
./gradlew cpdCheck                           # copy-paste-detektor (regel 4)
./gradlew :app:connectedDebugAndroidTest     # instrumenttester (kräver emulator/enhet)
node --test .claude/hooks/test/*.test.mjs    # hooktest regel4-check + baslinjen (ingen SDK behövs)
node tools/regel4-baseline.mjs               # skriv om baslinjen i ui-allowlist.txt när en skärm rättats
```

**Gradle körs i sessionen med Android SDK i `/opt/android-sdk`** (session-start-hooken säger
om den finns); kör det du ändrat innan varje push. Saknas den: installera den – det är alltid ok
att installera Android SDK och liknande verktyg i sessionen vid behov
(`.claude/hooks/install-android-sdk.sh`, ≈450 MB, några minuter). Bara om installationen inte går
får CI bekräfta – säg det i PR:en.

I GitHub Actions kör `.github/workflows/android.yml` vid push och PR mot `master`: ett
`changes`-jobb avgör utifrån ändrade sökvägar om kompilering och enhetstester
(`build-and-test`) och instrumenttesterna på emulator (`instrumented-tests`, **bara i PR**
som rör koden de testar) körs, och `ci-ok` är den samlade statusen. Lint och release-bygget
(R8) körs veckovis och på begäran i `quality.yml` samt i `release.yml`. `release.yml` bygger
signerad release (tagg eller manuellt), `release-debug.yml` en debug-snapshot. Vad som körs
när står i skill `ci-budget`. Från telefonen: **pusha branchen och öppna en PR mot `master`**.

**Länka alltid APK:n efter ett bygge.** Har en körning producerat en installerbar APK ska
svaret alltid avslutas med **länken till den** — release-sidan (t.ex.
`https://github.com/ParTee71/fonder/releases/tag/debug-snapshot`) eller, för ett bygge som
bara laddar upp en artifact, artifact-länken. Gäller **alla** byggen: debug-snapshot
(`release-debug.yml`), signerad release (`release.yml`) och varje ombyggnad av samma commit.
Skälet är praktiskt: appen installeras från telefonen, och ett "bygget är grönt" utan länk
tvingar fram ett extra steg för att hitta filen. Räcker inte länken ensam — säg också vilken
commit och `versionName` APK:n är byggd på, så en installation går att knyta till rätt
ändring.

## PR-flöde

- **En PR i taget.** Öppna/driv aldrig flera PR:ar mot `master` samtidigt — parallella PR:ar
  mot samma bas (särskilt när båda rör `KRAVLISTA.md`/`README.md`/`versionName`) ger nästan
  alltid en mergekonflikt på den andra. Vid flera issues: ett i taget, sekventiellt.
- **Kontrollera mergen via GitHub** (`pull_request_read`) innan nästa issue påbörjas –
  inte via antagande. Först när den är mergad startas arbetsbranchen om från `origin/master`.
- **Förbered releasen i funktions-PR:en.** Varje PR som ändrar appens beteende höjer
  versionen i samma ändring, så att ingen separat release-PR behövs. Välj semver efter
  ändringen: **major** = brytande/borttagen funktion eller backupformat som äldre appar inte
  läser, **minor** = ny funktion, **patch** = fix, chore, refactor. Höj `versionCode` med 1,
  uppdatera `versionName` i `app/build.gradle.kts` och versionstabellen i README, och skriv versionen i PR-beskrivningen. Ren dokument-,
  test- eller CI-ändring utan effekt i appen höjer inte. Är versionen redan höjd sedan senaste
  tagg, höj bara om denna PR är allvarligare. Själva releasen (tagg, bygge, publicering) körs
  fortfarande bara på uttrycklig begäran (skill **release**) och kräver då ingen ny PR.
- **Prenumerera automatiskt** på PR-aktivitet (`subscribe_pr_activity`) så fort en PR
  skapas, utan att fråga. Bevaka tills den är mergad eller stängd: fixa CI-fel, hantera
  granskningskommentarer, schemalägg check-in. Vid rött CI: låt agenten `ci-doktor`
  diagnostisera. Avsluta prenumerationen när PR:en är klar.
- **Delegera på rätt nivå:** huvudsessionen orkestrerar och granskar; agenten `byggare` tar
  skärmar, komponenter, use cases, nätverkskällor och CI-filer, agenten `arkitekt`
  Room-schema, migreringar, backup-kedjan och arkitekturval. Går ett steg fel två gånger i
  rad flyttas det upp en nivå.
- **Modell efter uppgift:** sessionen och varje agent väljer modell och effort efter uppgiftens
  komplexitet – Opus är standard; byt via `/model` eller låt en billigare agent ta rutindelar,
  och starta agenter med `model` efter uppgiften ([.claude/README.md](.claude/README.md) → Modellval).
- **Varje issue anger Agent · modell · effort** (skill `refine-issue`), och agenten körs med
  just den modellen och effort (skill `implement-issue`).

## Arkitektur i korthet

`Compose → ViewModel (StateFlow<UiState>) → Repository → Room/DataStore`.
Paket under `app/src/main/kotlin/se/partee71/fonder/`:
`data/` (auth, datastore, imports, network, repository, room) · `di/` · `domain/` (model, usecase) ·
`ui/<feature>/` + `ui/components/` + `ui/diagram/` + `ui/theme/` · `worker/`.
Detaljer i [README.md](README.md).

---

## Agenter, modellval och skills

Agenterna (`byggare`, `arkitekt`, `granskare`, `ci-doktor`, `testskrivare`), modellvalet och
alla skills, med när de används, står i [.claude/README.md](.claude/README.md). Agenterna
svarar med kortrapporten i sin fil. Vid komprimering av en lång session: behåll ändrade filer,
issue- och PR-nummer, körda testkommandon med resultat och öppna ❌ från granskningen.

> Reglerna gäller både i Claude Android-appen och i Claude inuti Android Studio —
> båda läser denna fil och `.claude/`. Skill-filerna är vanlig Markdown och kan läsas
> direkt.
