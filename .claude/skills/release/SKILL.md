---
name: release
description: 'Fonders release-flöde — föreslå versionshöjning, bekräfta, uppdatera versionName/versionCode i app/build.gradle.kts och README:s versionshistorik, landa release-commiten på master och trigga release.yml via workflow_dispatch så att GitHub Actions skapar taggen, bygger den signerade APK:n och publicerar en GitHub Release – och avsluta med APK-länken. CI-först (fungerar från telefonen). Använd BARA när användaren uttryckligen ber om en release. Trigger-ord: release, släpp, ny version, publicera, versionshöjning, bygg APK, signerad APK, debug-snapshot.'
---

# Release

Kör stegen i ordning och rapportera efter varje steg. Stanna och förklara om något fallerar.
**Kör bara när användaren uttryckligen ber om en release.**

## Hur en release går till

- **Bygg och signering:** i Actions (`release.yml`); keystoren avkodas ur secrets
  (`SIGNING_*`) och den signerade APK:n byggs i molnet. Ingen lokal SDK krävs.
- **Publicering:** `release.yml` publicerar en GitHub Release med automatisk changelog och
  APK:n. Två triggers: push av tagg `v*`, eller `workflow_dispatch` på `master` med
  `version_name` och `publish_release` – då **skapar workflowen taggen själv** på den
  dispatchade commiten.
- **Grindar:** `release.yml` kör i dag inga tester själv. **Starta releasen först när CI
  (`android.yml`) är grönt** för release-commiten på `master` – annars släpps oprövad kod.
  Snålt med Actions-minuter (regel 5): kör inte om det som redan körts grönt.
- **Ett fallerat jobb:** läs loggen (`get_job_logs`, eller agenten `ci-doktor`), fixa
  grundorsaken och kör om bara det som föll – aldrig hela releasen i blindo.
- **Testbygge utan publicering:** `workflow_dispatch` med bara `version_name` (utan
  `publish_release`) ger den signerade APK:n som artifact – ingen tagg, ingen Release.
- **Debug-snapshot:** `release-debug.yml` (bara `workflow_dispatch`) bygger en debug-APK och
  publicerar den som rullande pre-release under taggen `debug-snapshot`, som skrivs över vid
  varje körning. Ingen versionshöjning, ingen tagg `v*`.
- **Taggar pushas aldrig från en agentsession** – nätverkspolicyn blockerar `refs/tags/*`
  (`error: RPC failed; HTTP 403`). Använd dispatch-vägen. Branch-pushar påverkas inte.
- **Verktyg:** GitHub MCP (`mcp__github__*`); ingen `gh`.

## Steg 1 – Nuvarande version

Läs `versionCode` och `versionName` i `app/build.gradle.kts` (`versionNameOverride` sätts bara
av workflowens `-PversionNameOverride`).

## Steg 2 – Föreslå versionshöjning

Senaste tagg: `mcp__github__list_tags` / `mcp__github__get_latest_release` (owner `ParTee71`,
repo `fonder`). Commits sedan dess: `git log <tagg>..HEAD --oneline --no-merges`. PR:er
squash-mergas, så varje rad är ett ämne som `feat(...)`/`fix(...)` med `(#NN)`. Den
**allvarligaste** ändringen i hela intervallet styr (en `feat` → minor, även bland många `fix`).

| Innehåll | Höjning |
|---|---|
| `BREAKING`, `!:`, borttagen funktion, nytt backupformat som äldre appar inte kan läsa | **major** |
| `feat`, ny skärm, ny förmåga | **minor** |
| `fix`, `chore`, `refactor`, `test`, `docs`, beroenden | **patch** |

Har en funktions-PR redan höjt `versionName` sedan senaste tagg (CLAUDE.md regel 3), utgå från
den – höj inte två gånger. Ny `versionCode` = nuvarande + 1. Visa:
```
Nuvarande: v<gammal>  (versionCode <gammal>)
Förslag:   v<ny>      (versionCode <ny>)
Skäl:      <en mening>
Commits:   <punktlista>
Kör v<ny>, eller en annan version?
```
**Vänta på bekräftelse.**

## Steg 3 – Uppdatera version, README och krav

1. `app/build.gradle.kts`: `versionCode = <ny>` och `versionName = "<ny>"`. Verifiera ändringen.
2. README: versionsraden överst (`> Version: <ny> (följer versionName/KRAVLISTA.md)`).
3. README "Versionshistorik": **ny rad** för `<ny>` med de användarsynliga ändringarna sedan
   förra releasen, i samma stil som befintliga rader, med `(#NN)`-referenser. Funktions-PR:er
   rör inte tabellen – raden **skrivs här**, utifrån commitarna i steg 2.
4. Kontrollera att KRAVLISTA.md speglar det som släpps och att TP-kraven matchar SDK-nivåerna
   i `build.gradle.kts` (`compileSdk`, `minSdk`, `targetSdk`).
5. Har `BackupPayload.FORMAT_VERSION` höjts sedan förra releasen: nämn det tydligt – en äldre
   app avvisar då nya säkerhetskopior (skill `data-safety-backup`).

## Steg 4 – Release-commit på master

Via PR (grön och mergad, skill `implement-issue`), eller direkt till `master` om användaren
uttryckligen godkänt det.
```
git commit -am "Release v<ny>"
git push origin master
```
Skapa ingen tagg här.

## Steg 5 – Trigga release-bygget

När CI är grönt på release-commiten:
```
mcp__github__actions_run_trigger
  owner: ParTee71, repo: fonder
  workflow_id: release.yml, ref: master
  inputs: { version_name: "<ny>", publish_release: "true" }
```
`version_name` utan `v`; `publish_release` som strängen `"true"`. Workflowen vägrar om
taggen redan finns, så en upprepad dispatch skriver aldrig över en publicerad Release.

Följ körningen: `actions_list` → `actions_get` → vid fel `get_job_logs` (`failed_only`) eller
agenten `ci-doktor` → `get_release_by_tag` för `v<ny>`.

**Manuell reserv:** från användarens egen dator `git tag v<ny> && git push origin v<ny>` –
samma workflow. Erbjud bara om dispatch-vägen inte fungerar.

## Steg 6 – Sammanfattning med APK-länk

Avsluta **alltid** med länken till APK:n – appen installeras från telefonen, och "bygget är
grönt" utan länk tvingar fram ett extra steg. Ange commit och `versionName`.
```
Släppt:  Fonder v<ny>  (versionCode <ny>)
Commit:  <sha> "Release v<ny>" på master
Tagg:    v<ny> (skapad av release.yml)
CI:      <länk / status>
APK:     <Release-länk> (signerad fonder-<ny>-<tidsstämpel>.apk)
```
Samma regel för `release-debug.yml` (länk till `releases/tag/debug-snapshot`) och för ett
testbygge utan Release (länk till artifacten).

## Reserv – bygga lokalt i Android Studio

`./gradlew :app:assembleRelease` kräver `fonder.jks` i `app/` och signeringslösenord via
`local.properties` eller `SIGNING_*`-miljövariabler; APK:n hamnar i
`app/build/outputs/apk/release/`. Checka aldrig in keystoren. För en publicerad Release, kör
ändå steg 5.
