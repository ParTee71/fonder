# Claude-uppsättningen i Fonder

Översikt över agenter, modellval, skills och hooks i `.claude/`. Reglerna står i
[CLAUDE.md](../CLAUDE.md); den här filen läses vid behov, så att CLAUDE.md – som laddas vid
varje uppgift – hålls kort.

## Agenter (`.claude/agents/`)

| Agent | När |
|---|---|
| `byggare` | Skärmar, komponenter, use cases, nätverkskällor, workers och CI-filer enligt en klar plan (Opus · medium). |
| `arkitekt` | Room-entiteter, migreringar, backup-kedjan, DataStore och arkitekturval (Opus · high). |
| `granskare` | Före varje PR: granskar diffen mot de fem reglerna och mockup-regeln, rapporterar i en tabell (sessionens modell · medium). |
| `ci-doktor` | Vid rött CI: läser loggar och testrapporter, föreslår fix (Sonnet · medium). |
| `testskrivare` | Skriver/utökar tester och fixturer – när tre eller fler testnivåer berörs (Sonnet · medium). |

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

Skills `refine-issue` och `implement-issue` sätter och följer **Agent · modell · effort** per
issue utifrån den här tabellen.

## Skills (`.claude/skills/`)

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
| `ui-style` | Designspråket — nordisk blå, koppar, M3 Expressive, vinst/förlust, Space Grotesk, tabulära siffror, former. |
| `accessibility-compose` | TalkBack, belopp och procent som läses begripligt, tryckytor, dynamisk text. |
| `diagram` | `FundLineChart` i `ui/diagram/` och diagrammatematiken i `domain/usecase/` (Vico 3.3.1). |
| `data-privacy-security` | Ekonomisk persondata, loggning, nätverkskällor, Drive `appDataFolder`, hemligheter, minify. |

## Hooks (`.claude/hooks/`) och inställningar (`.claude/settings.json`)

- **`regel4-check.mjs`** kontrollerar regel 4 direkt efter varje filändring (PostToolUse), med
  samma mönster som bygget (`app/src/test/resources/ui-forbidden.txt`). Den kör också som
  **Stop-hook** innan Claude avslutar en tur: alla filer som ändrats mot `origin/master` går
  igenom, och en träff gör att Claude ombeds rätta först (högst en gång i följd). Har sessionen
  flera repon under en gemensam katalog kontrollerar Stop-hooken varje repo med det repots
  egen hook; ett repo utan hook lämnas därhän.
- **`session-start.sh`** säger i molnsessioner om Android SDK finns och skriver `sdk.dir` i
  `local.properties` när den saknas där.
- **`install-android-sdk.sh`** installerar Android SDK i `/opt/android-sdk` (≈450 MB) och
  pekar ut den i `local.properties`. Körs vid behov, inte automatiskt.
- **`settings.json`** sätter `LC_ALL=C.UTF-8` (testnamn med å/ä/ö), tillåter de läsande
  GitHub- och sessionsverktygen utan fråga och hindrar läsverktyget från att öppna
  signeringsnycklar (`*.jks`, `keystore.properties`), service accounts och `.env`.
  `app/google-services.json` och `app/debug.keystore` är incheckade och ingen hemlighet.
