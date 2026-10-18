---
name: implement-issue
description: Implement a GitHub issue, bug fix, feature, or chore in Fonder end to end — check that the previous PR is merged, research, reuse shared parts, change code following the project's patterns, add tests at every level, update requirements and the backup chain, self-review with the granskare agent and /code-review, then commit, push, open a PR, watch it and link the APK. Use when the user wants to "implement issue #N", "fix this bug", "build this feature", "do this ticket", "pick up #N", or hands off from refine-issue. Svenska trigger-ord: genomför, implementera, kör #N, kör nästa, driv på, fixa buggen, bygg funktionen, ta issue.
---

# Genomföra ett issue (bugg · funktion · underhåll)

Motsvarigheten till `refine-issue`: driver en ändring från issue till PR enligt de fem
reglerna i [CLAUDE.md](../../../CLAUDE.md). Hoppa inte över steg – reglerna är definitionen
av klart.

> **Tester körs lokalt före push när Android SDK finns, CI bekräftar** (skill
> `testing-strategy`, "Innan du anser dig klar"). Saknas SDK får CI testa – säg det i PR:en.
> `android.yml` kör kompilering + enhetstester på push och i PR som rör koden, och
> emulatorjobbet `instrumented-tests` **bara i PR** som rör koden det testar (skill `ci-budget`).

## Steg 0 – Föregående PR och branch (alltid först)

En PR i taget mot `master`, och sessionen har normalt en enda arbetsbranch.

1. Hämta den senaste PR:en från arbetsbranchen med `mcp__github__list_pull_requests`
   (owner `ParTee71`, repo `fonder`, head `<branch>`, state `all`) och
   `mcp__github__pull_request_read`. **Lita aldrig på ett antagande eller på att någon sagt
   "mergad" – fråga GitHub.**
2. **Mergad** → `git fetch origin master && git checkout -B <branch> origin/master`. Avsluta
   bevakningen av den gamla PR:en (`unsubscribe_pr_activity`) och ta bort dess check-in.
3. **Öppen** → säg direkt att den inte är mergad och att nytt arbete annars hamnar i samma PR.
   Vänta på besked, eller förbered arbetet i en lokal commit utan push. **Återställ aldrig
   branchen från `master` innan mergen bekräftats** – det tappar PR:ens commits lokalt.
4. Ingen PR alls → skapa branchen från `origin/master`. Committa aldrig direkt på `master`.

Skälet är konkret: parallella PR:ar mot samma bas krockar nästan alltid på
`KRAVLISTA.md`/`README.md`/`versionName`.

## Steg 1 – Förstå arbetet

- Hämta issuet **och dess kommentarer**: `mcp__github__issue_read` (owner `ParTee71`, repo
  `fonder`, metod `get` och `get_comments`). Acceptanskriterier, Design, Återbruk och
  **Agent · modell · effort** är kontraktet; kommentarer med rubriken "Tillägg" gäller före
  issuetexten.
- Oklart eller riskabelt omfång → kör `refine-issue` först eller bekräfta omfånget.
- Klassa: bugg, funktion eller underhåll.

## Steg 2 – Mockup (ny GUI)

Kräver issuet ny eller tydligt ändrad GUI (tumregel i CLAUDE.md, skill `mockup`) och det
finns ingen godkänd mockup under **Design** → gör mockupen, visa den och **vänta på ok**
innan kod.

## Steg 3 – Återskapa (bugg) / sikta (funktion)

- **Bugg:** exakt återskapande, förväntat mot faktiskt, hitta felande kodväg. Reproduktionen
  blir ett test som faller.
- **Funktion/underhåll:** exakt var ändringen görs och vilket synligt utfall som betyder klart.

## Steg 4 – Hitta befintliga mönster och delade delar

- Öppna en syskon-ViewModel/skärm/repository och följ dess form (`PortfoljViewModel`,
  `TransaktionerViewModel`, `FundSearchViewModel`) – skill `android-dev`.
- Slå upp komponenter, kända dubbletter och byggstenar i skill `shared-ui-components`. Rör du
  en känd dubblett (`TotalCard`, `ComparisonChart`, importens laddningsdialoger) – slå ihop den
  först. Saknas något delat → planera det som **egen commit före** featuren.
- Data → skills `data-safety-backup` och `room-migrations`.
- Extern källa → `data/network/` (Avanza, Handelsbanken, Riksbanken) är odokumenterade källor
  (TP-10, TP-14); håll parsning isolerad där och läck den inte in i repositories/ViewModels.
- Beräkning → `domain/usecase/`, aldrig i ViewModel eller composable.
- Mappa till KRAVLISTA-ID:n (skill `requirements-kravlista`) och befintliga tester.

## Steg 5 – Planera och delegera

Kort plan: filer, ordning, tester, migrering och backup-kedjan, krav. Arkitektoniskt
betydande eller tvetydigt → fråga användaren innan kod (`AskUserQuestion` där det finns,
annars ett beslutskort eller en kort fråga i tråden).

**Delegera på rätt nivå:** skärmar, komponenter, use cases, nätverkskällor, workers och
CI-filer till agenten `byggare`; Room-schema, migreringar, backup-kedjan, DataStore och
arkitekturval till agenten `arkitekt`; tester på tre eller fler nivåer till `testskrivare`.
Huvudsessionen orkestrerar och granskar. Går ett steg fel två gånger i rad flyttas det upp en nivå.

**Kör agenten som issuet anger.** Läs avsnittet **Agent · modell · effort** och starta varje
del med just den agenten, modellen och effort. Saknas avsnittet: komplettera issuet först
(skill `refine-issue`). Avvikelse bara uppåt, och motiveras i PR-beskrivningen. Hur modellen
väljs efter uppgiften står i [`.claude/README.md`](../../README.md) → Modellval.

## Steg 6 – Implementera (tester samtidigt, inte efter)

`Compose → ViewModel(StateFlow<UiState>) → Repository → Room/DataStore`, Hilt, fel mappas i
repository-lagret.

- **Bugg:** regressionstest som faller först, sedan minsta ändringen som gör det grönt.
  Utöka inte omfånget medan du är där.
- **Funktion:** delade delar först (egen commit), sedan featuren ovanpå. Injicera dispatchers.
- **Persisterad data:** hela kedjan i samma ändring – `@Entity`, `version`, `MIGRATION_N_N+1`
  i `MIGRATIONS`, `MigrationXYTest`; fältet i `BackupPayload` med default, i `export()`/`restore()`,
  fältvakten i `BackupSerializerTest` och `BackupRoundTripTest`. Drive-backupen är en transport
  och rörs inte av ett nytt fält.
- Svenska UI-strängar i `strings.xml`; återanvänd befintliga texter.
- Logga aldrig hela HTTP-svar, innehav, belopp eller tokens; `contentDescription` och 48 dp
  tryckyta för nya interaktiva element.

## Steg 7 – Tester på varje berörd nivå

Enligt skill `testing-strategy`: enhet i `app/src/test`, Room/migrering/rundtur/Compose i
`app/src/androidTest`; uppdatera – ta aldrig bort eller försvaga – befintliga tester; nya
komponenter får ett Compose-test (och skärmdump ljust + mörkt när Roborazzi finns).

## Steg 8 – Självgranskning

Kör agenten **`granskare`** på diffen. Varje ❌ åtgärdas; ett ⚠️ åtgärdas om det är litet,
annars nämns det i PR:en. Spåra varje acceptanskriterium till kod eller test och till
issuets **Verifiering** – kör de kommandona. Kan du inte det är du inte klar.

Rör ändringen `domain/usecase/`, datalagret, Room-schemat, backup-kedjan, en nätverkskälla
eller en delad komponent – eller är diffen större än ett par filer – kör dessutom
**`/code-review`** på diffen, *efter* `granskare` och innan push. `granskare` kontrollerar
reglerna; `/code-review` letar buggar. Verifiera varje fynd och åtgärda de verkliga. Rena
dokument- och strängändringar behöver den inte.

Ändras koden efter granskningen granskas rättningarna igen – `granskare` och `/code-review`
på bara det som ändrats sedan förra rundan, och bara mot ❌ och buggar – innan push. Rundorna
slutar när en runda inte hittar något ❌; ⚠️ i en senare runda nämns i PR:en i stället för att
starta en ny. En PR går aldrig ut med kod som ingen granskning sett.

## Steg 9 – Commit och push

- Konventionella meddelanden, gärna svenska: `feat(transaktioner): …`, `fix(portfolj): …`,
  `test(...)`, `docs(...)`, `chore(...)`. Små, sammanhängande commits. `Closes #N` i den sista.
  Avsluta med de commit-trailers som sessionen anger.
- Kör kontrollerna lokalt (skill `testing-strategy`) och pusha först när de är gröna.
- `git push -u origin <branch>` (försök igen med backoff vid nätverksfel).

## Steg 10 – Pull request och bevakning

- Mot `master`. Titel `<område>: <sammanfattning>`. Innehåll: vad som ändrats, hur det
  uppfyller de fem reglerna, krav-ID:n som ändrats eller uppfylls, testplan (och vad som inte
  kunde köras lokalt), byggtidseffekt vid nya beroenden, `Closes #N`, och för GUI:
  mockup-länk (+ skärmdumpar ljust/mörkt när Roborazzi finns).
- **Prenumerera direkt** (`subscribe_pr_activity`) utan att fråga, och schemalägg en check-in.
  Bevaka tills den är mergad eller stängd; hantera granskningskommentarer.
- Rött CI → agenten **`ci-doktor`** diagnostiserar; fixa grundorsaken och pusha. Kör aldrig
  bara om.
- **APK-länk:** har en körning producerat en installerbar APK (debug-snapshot via
  `release-debug.yml`, eller en artifact) – avsluta svaret med länken, commit och `versionName`
  (CLAUDE.md → Bygg & test).
- Har användaren bett om automatisk merge när den är grön: kontrollera att alla kontroller på
  senaste commit är gröna och att PR:en är konfliktfri, merga med `merge_pull_request`
  (`expectedHeadSha`), avsluta bevakningen och gå tillbaka till steg 0.

## Flera issues

Ett i taget, var och en med egen PR. Blanda aldrig orelaterade ändringar i samma branch.
Att förbereda nästa på en lokal branch går bra – det är PR-öppning till merge som sekvenseras.

## Anti-mönster

- Påbörja nästa issue utan att ha frågat GitHub om föregående PR är mergad.
- Kod för ny GUI utan godkänd mockup.
- Bugfix utan regressionstest.
- Persisterad ändring utan migrering + `MigrationXYTest`, eller utan plats i `BackupPayload`
  och rundturen (eller ett uttryckligt motiv till att fältet är cache).
- En lokal variant av en delad komponent, eller en tredje kopia av en känd dubblett.
- Ta bort eller `@Ignore`:a ett rött test.
- "Bygget är grönt" utan APK-länk när en APK byggts.
