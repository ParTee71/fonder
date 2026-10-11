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

Visuell identitet: **grön petrol** (petrol #167C6E, mässingsaccent #C9A227), rubriker i
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
→ Skill: **shared-ui-components**.

### 5. CI-budget
En PR ska kosta **~8 Actions-minuter**, plus instrumenttesterna på emulatorn bara när PR:en
rör koden de testar. Release-bygge (R8) och lint hör inte till PR-flödet; de körs på begäran,
veckovis och vid release. Nya beroenden eller plugins får inte fördubbla byggtiden – ange
effekten i PR:en. Att slippa köra ett test är aldrig ett skäl att inte skriva det.

**Läget i dag:** Fonder ligger på **~19–20 minuter per PR-push** (`android.yml` med
release-bygge + `instrumented.yml` på varje PR). Målet nås i CI-etappen; till dess får ingen
ändring göra det dyrare.
→ Skill: **ci-budget**.

---

## Processregel: mockup före ny GUI

Utvecklingen sker mest från telefonen, och ett bygge för att "se hur det blev" är dyrt.

- Ny skärm, ny delad komponent eller tydligt ändrat utseende/beteende → visa först en
  **mockup** för användaren (Design-canvas-artifact) och vänta på ok innan koden skrivs.
- Mockupen byggs av befintliga komponenter i `ui/components/` och `ui/diagram/`, i Fonders
  designspråk (grön petrol, mässing, Space Grotesk, tabulära siffror), med riktiga svenska
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
./gradlew :app:testDebugUnitTest             # enhetstester (JUnit/MockK/Turbine)
./gradlew :app:compileDebugAndroidTestKotlin # kompilera instrumenttester
./gradlew :app:connectedDebugAndroidTest     # instrumenttester (kräver emulator/enhet)
```

**Gradle går att köra i sessionen när Android SDK finns** (session-start-hooken säger om den
finns); kör då det du ändrat innan varje push. Annars får CI bekräfta – säg det i PR:en.

I GitHub Actions kör `.github/workflows/android.yml` kompilering, enhetstester och ett
release-bygge (R8) vid push och PR mot `master`. Instrumenttester
(`.github/workflows/instrumented.yml`, emulator) körs **endast på pull requests** mot
`master` — ren push triggar dem inte. `release.yml` bygger signerad release (tagg eller
manuellt), `release-debug.yml` en debug-snapshot. Från telefonen: **pusha branchen och öppna
en PR mot `master`** så kör Actions båda.

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
- **Prenumerera automatiskt** på PR-aktivitet (`subscribe_pr_activity`) så fort en PR
  skapas, utan att fråga. Bevaka tills den är mergad eller stängd: fixa CI-fel, hantera
  granskningskommentarer, schemalägg check-in. Vid rött CI: låt agenten `ci-doktor`
  diagnostisera. Avsluta prenumerationen när PR:en är klar.
- **Delegera på rätt nivå:** huvudsessionen orkestrerar och granskar; agenten `byggare` tar
  skärmar, komponenter, use cases, nätverkskällor och CI-filer, agenten `arkitekt`
  Room-schema, migreringar, backup-kedjan och arkitekturval. Går ett steg fel två gånger i
  rad flyttas det upp en nivå.
- **Varje issue anger Agent · modell · effort** (skill `refine-issue`), och agenten körs med
  just den modellen och effort (skill `implement-issue`).

## Arkitektur i korthet

`Compose → ViewModel (StateFlow<UiState>) → Repository → Room/DataStore`.
Paket under `app/src/main/kotlin/se/partee71/fonder/`:
`data/` (auth, datastore, imports, network, repository, room) · `di/` · `domain/` (model, usecase) ·
`ui/<feature>/` + `ui/components/` + `ui/diagram/` + `ui/theme/` · `worker/`.
Detaljer i [README.md](README.md).

---

## Agenter (`.claude/agents/`)

| Agent | När |
|---|---|
| `byggare` | Skärmar, komponenter, use cases, nätverkskällor, workers och CI-filer enligt en klar plan (Opus · medium). |
| `arkitekt` | Room-entiteter, migreringar, backup-kedjan, DataStore och arkitekturval (Fable/Opus · high). |
| `granskare` | Före varje PR: granskar diffen mot de fem reglerna och mockup-regeln, rapporterar i en tabell (Sonnet · medium). |
| `ci-doktor` | Vid rött CI: läser loggar och testrapporter, föreslår fix (Sonnet · medium). |
| `testskrivare` | Skriver/utökar tester och fixturer – när tre eller fler testnivåer berörs (Sonnet · medium). |

**Kortrapport:** agenternas svar hamnar i huvudsessionens kontext och kostar där. Varje agent svarar bara med mallen i sin fil – inga referat av det den läst, ingen upprepning av uppdraget, högst ~15 rader om inte fynden kräver mer. Effort står som kommentar i frontmatter och styr inte körningen; huvudsessionen anger den när agenten startas.

## Skills i detta repo (`.claude/skills/`)

Generella Android/Kotlin-skills är kopierade från Dagboken. De projektspecifika är
**anpassade till Fonder**, inte kopierade rakt av — Dagbokens backup går mot Drive och
dess migreringstester mot `MigrationTestHelper`, medan Fonders kontrakt är
`BackupPayload` mot en lokal fil och migreringstesterna bygger det gamla schemat för
hand. Läs skillen, inte Dagbokens motsvarighet.

| Skill | När |
|---|---|
| `android-dev` | Baslinje för all Android/Kotlin-utveckling (ladda alltid). |
| `android-data-layer` | Repository/Room/DAO/offline-först. |
| `compose-expert` · `kotlin-coroutines` · `kotlin-flows` | Compose-, coroutine- och flow-detaljer. |
| `android-gradle-logic` | Gradle/version catalogs/build-konfiguration. |
| `data-safety-backup` | Regel 1 — persisterad data, backup-kontraktet, rundturen. |
| `testing-strategy` | Regel 2 — nivåer, fakes, och den flakiness som redan bitit. |
| `requirements-kravlista` | Regel 3 — ID-serier, hur en kravrad skrivs här, historiken. |
| `shared-ui-components` | Regel 4 — katalogen i `ui/components/` och `ui/diagram/`. |
| `room-migrations` | Schemaändringar, defaultvärden och migreringstesterna. |
| `firebase-auth` | Google-inloggning (TP-6) — Credential Manager, `AuthRepository`. |
| `refine-issue` | Förfina en idé/bugg till ett planerat GitHub-issue (med DoD enligt reglerna). |
| `implement-issue` | Genomför ett issue/bugg/feature hela vägen till PR enligt reglerna. |
| `release` | Endast vid uttrycklig release. |
| `ci-budget` | Regel 5 — vad som körs när i CI (enda tabellen), kända problem och målet efter CI-etappen. |
| `mockup` | Ny skärm, komponent eller ändrat utseende: mockup före kod. |
| `ui-style` | Designspråket — petrol, mässing, vinst/förlust, Space Grotesk, tabulära siffror, former. |
| `accessibility-compose` | TalkBack, belopp och procent som läses begripligt, tryckytor, dynamisk text. |
| `diagram` | `FundLineChart` i `ui/diagram/` och diagrammatematiken i `domain/usecase/` (Vico 2.0.3). |
| `data-privacy-security` | Ekonomisk persondata, loggning, nätverkskällor, Drive `appDataFolder`, hemligheter, minify. |

> Reglerna gäller både i Claude Android-appen och i Claude inuti Android Studio —
> båda läser denna fil och `.claude/`. Skill-filerna är vanlig Markdown och kan läsas
> direkt.
