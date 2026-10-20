# Claude-uppsättningen i Fonder

Översikt över agenter, modellval, skills och hooks i `.claude/`. Reglerna står i
[CLAUDE.md](../CLAUDE.md); den här filen läses vid behov, så att CLAUDE.md – som laddas vid
varje uppgift – hålls kort.

## Agenter (`.claude/agents/`)

| Agent | När |
|---|---|
| `byggare` | Skärmar, komponenter, use cases, nätverkskällor, workers och CI-filer enligt en klar plan (Opus · medium). |
| `arkitekt` | Room-entiteter, migreringar, backup-kedjan, DataStore och arkitekturval (Opus · high). |

Från pluginen (generella, läser Fonders delar ur repot):

| Agent | När |
|---|---|
| `android:granskare` | Före varje PR: granskar diffen mot de fem reglerna och mockup-regeln, rapporterar i en tabell (sessionens modell · medium). Fonders kontrollpunkter: `android-projekt` → Granskning. |
| `android:ci-doktor` | Vid rött CI: läser loggar och testrapporter, föreslår fix (Sonnet · medium). Fonders felklasser: `ci-budget` → Felklasser. |
| `android:testskrivare` | Skriver/utökar tester och fixturer – när tre eller fler testnivåer berörs (Sonnet · medium). Fonders regler: `testing-strategy` → För testskrivaren. |

Modell och effort står i varje agents frontmatter (`model`, `effort`) och gäller när
agenten startas utan annat besked.

**Kortrapport:** agenternas svar hamnar i huvudsessionens kontext och kostar där. Varje agent
svarar bara med mallen i sin fil – inga referat av det den läst, ingen upprepning av
uppdraget, högst ~15 rader om inte fynden kräver mer.

## Modellval

Modellen väljs efter uppgiftens komplexitet – för sessionen och för varje agent. **Opus är
standard.** Frontmatterns modell är agentens standard; huvudsessionen anger `model` när
agenten startas, efter uppgiften. Sessionen byter själv via `/model`, eller låter en billigare
agent ta rutindelarna.

| Uppgift | Modell |
|---|---|
| Uppslag och läsning – loggar, data, sökningar | Haiku |
| Små avgränsade ändringar – en fil, en komponent, ett test | Sonnet |
| Flera lager, datamodell, migrering, backup och granskning | Opus |
| Väldigt komplexa issues – anges då i issuets **Agent · modell · effort** | Fable |

Skills `android:refine-issue` och `android:implement-issue` sätter och följer **Agent · modell · effort** per
issue utifrån den här tabellen (Fonders agenter: `android-projekt` → Issues).

## Skills (`.claude/skills/`)

De generella Android/Kotlin-skillsen kommer från pluginen (se nedan). De projektspecifika är
**anpassade till Fonder**, inte kopierade rakt av — Dagbokens backup går mot Drive och
dess migreringstester mot `MigrationTestHelper`, medan Fonders kontrakt är
`BackupPayload` mot en lokal fil och migreringstesterna bygger det gamla schemat för
hand. Läs skillen, inte Dagbokens motsvarighet.

| Skill | När |
|---|---|
| `android-projekt` | Fonders delar av pluginens `android:`-skills (se nedan) – ladda alltid tillsammans med dem; mockupens data, länk och skärmdumpar; avsnitten Arbetsflöde, Issues och Release till `android:implement-issue`, `android:refine-issue` och `android:release`. |
| `android-data-layer` | Repository/Room/DAO/offline-först. |
| `data-safety-backup` | Regel 1 — persisterad data, backup-kontraktet, rundturen. |
| `testing-strategy` | Regel 2 i Fonder — nivåer, fakes, kommandon och den flakiness som redan bitit. Det generella: `android:testing-strategy`. |
| `requirements-kravlista` | Regel 3 — ID-serier, hur en kravrad skrivs här, historiken. |
| `shared-ui-components` | Regel 4 — katalogen i `ui/components/` och `ui/diagram/`. |
| `room-migrations` | Schemaändringar, defaultvärden och migreringstesterna. |
| `ci-budget` | Regel 5 i Fonder — vad som körs när i CI (enda tabellen), felklasser och kända problem. Det generella: `android:ci-budget`. |
| `ui-style` | Designspråket — nordisk blå, koppar, M3 Expressive, vinst/förlust, Space Grotesk, tabulära siffror, former. |
| `diagram` | `FundLineChart` i `ui/diagram/` och diagrammatematiken i `domain/usecase/` (Vico 3.3.1). |
| `data-privacy-security` | Ekonomisk persondata, loggning, nätverkskällor, Drive `appDataFolder`, hemligheter, minify. |
| `fondmatematik` | Definitionerna bakom varje siffra (TWR, XIRR, årstakt, risk, genomsnittsmetoden, ISK/depå, SRI, avgifter, facit) och golden-värden ur `tools/fondmatte`. |

Femton generella skills och tre agenter kommer från pluginen `android@claude-android` och heter `android:<namn>`
i sessionen: `android-dev` (baslinje, ladda alltid), `compose-expert`, `kotlin-coroutines`,
`kotlin-flows`, `navigation-3`, `accessibility-compose`, `android-gradle-logic`, `firebase-auth`,
`mockup` (mockup före ny GUI), `refine-issue` (förfina en idé eller bugg till ett planerat
issue), `implement-issue` (genomföra ett issue hela vägen till PR, med merge-kontroll först),
`release` (endast vid uttrycklig release; versionshöjningen i funktions-PR:en), `testing-strategy` och
`ci-budget` (det generella i regel 2 och 5) samt Googles `android-intent-security` (manifest, `PendingIntent`,
Intents). Pluginens `firebase-security-rules-auditor` gäller inte – Fonder har ingen Firestore.
Pluginen är generell; Fonders delar och nuvarande avvikelser står i `android-projekt`, och där
de krockar gäller projektets regler.

## Hooks (`.claude/hooks/`) och inställningar (`.claude/settings.json`)

- **`regel4-check.mjs`** kontrollerar regel 4 direkt efter varje filändring (PostToolUse), med
  samma mönster som bygget (`app/src/test/resources/ui-forbidden.txt`). Den kör också som
  **Stop-hook** innan Claude avslutar en tur: alla filer som ändrats mot `origin/master` går
  igenom, och en träff gör att Claude ombeds rätta först (högst en gång i följd). Har sessionen
  flera repon under en gemensam katalog kontrollerar Stop-hooken varje repo med det repots
  egen hook; ett repo utan hook lämnas därhän.
- **`session-start.sh`** säger i molnsessioner om Android SDK finns och skriver `sdk.dir` i
  `local.properties` när den saknas där. Den lägger också `maven-central-mirror.init.gradle.kts`
  i `~/.gradle/init.d/`, så att sessionens Gradle hämtar från Googles spegel av Maven Central i
  stället för att få 429 från `repo.maven.apache.org`. CI och Android Studio påverkas inte.
- **`install-android-sdk.sh`** installerar Android SDK i `/opt/android-sdk` (≈450 MB) och
  pekar ut den i `local.properties`. Körs vid behov, inte automatiskt.
- **`settings.json`** sätter `LC_ALL=C.UTF-8` (testnamn med å/ä/ö), tillåter de läsande
  GitHub- och sessionsverktygen utan fråga och hindrar läsverktyget från att öppna
  signeringsnycklar (`*.jks`, `keystore.properties`), service accounts och `.env`.
  `app/google-services.json` och `app/debug.keystore` är incheckade och ingen hemlighet.

## Verktyg för fondlogiken

- **`tools/fondmatte/`** (Python, bara i sessionen): `fondmatte.py golden` räknar förväntade värden
  till JUnit-testerna med pyxirr och empyrical-reloaded, `fi_innehav.py` läser Finansinspektionens
  fondinnehav för överlapp och genomlysning. Installation och kommandon i
  [tools/fondmatte/README.md](../tools/fondmatte/README.md); när de används står i skill `fondmatematik`.
- **MCP-servern `avanza`** (`.mcp.json`, påslagen via `enabledMcpjsonServers` i `settings.json`):
  inofficiell, läsande åtkomst till Avanzas publika fond- och kursdata (avanza-mcp 2.1.0, startas med
  `uvx`). För att slå upp en fond eller kontrollera parsrarna i `data/network` – aldrig med
  användarens innehav, och aldrig i BankID-läget. Saknas `uv` lokalt: `pip install uv`, eller låt bli.

## Gemensam plugin

`.claude/settings.json` slår på pluginen `android@claude-android` från det privata repot
[ParTee71/claude-android](https://github.com/ParTee71/claude-android) (`extraKnownMarketplaces`
+ `enabledPlugins`), som bär de generella Android-skillsen ovan. Lokalt (Android Studio, CLI)
installeras den därifrån. Molnsessioner får den via miljöns setup-skript, som klonar repot till
`/home/user/claude-android`. Repospecifikt hör aldrig hemma i pluginen – det står i `android-projekt`.
