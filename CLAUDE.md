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

## De fyra icke-förhandlingsbara reglerna

Vid **varje** kodändring ska du, innan du anser arbetet klart, gå igenom alla fyra:

### 1. Datasäkerhet — backup/restore får aldrig tappa data
All användardata (fonder, transaktioner, inställningar) måste överleva en
**backup → restore-rundtur** utan förlust. Lägger du till eller ändrar ett persisterat
fält/en entitet ska det in i hela backup-kedjan *och* täckas av rundturstest.
→ Skill: **data-safety-backup** (kopieras från Dagboken vid behov).

### 2. Tester på alla nivåer
Ingen beteendeändring utan att tester läggs till eller uppdateras på rätt nivå
(enhet / instrument / migrering). Befintliga tester som påverkas ska uppdateras,
aldrig tas bort för att "bli gröna".
→ Skill: **testing-strategy**.

### 3. Kraven hålls aktuella
Ändrar du synligt beteende ska [KRAVLISTA.md](KRAVLISTA.md) uppdateras i samma
ändring — nytt krav läggs till, borttaget beteende stryks med
`~~…~~ *(borttaget)*`. Versionen i README och `versionName` följer med.
→ Skill: **requirements-kravlista**.

### 4. Återanvänd generiska komponenter
Behöver du ett diagram, ett kort, en tom-tillståndsvy, en datum/tid-rad osv. — använd
den befintliga delade komponenten i `ui/components/` eller `ui/diagram/`. Bygg inte en ny
variant av något som redan finns; utöka den delade komponenten i stället.
→ Skill: **shared-ui-components**.

---

## Innan du skriver ny kod (snabb checklista)

1. **Sök efter befintligt mönster** — sibling-ViewModel, delad komponent eller
   repository-metod som visar hur projektet gör. `Grep`/läs grannfiler först.
2. **Följ arkitekturen**: MVVM, `StateFlow<UiState>`, Repository som single source of
   truth, Hilt-DI, fel mappas i repository-lagret.
3. **Svenska** i allt användarvänt (UI-strängar i `strings.xml`); kod/identifierare på
   engelska enligt befintlig stil.

## Innan du anser dig klar (slutkontroll)

- [ ] **Datasäkerhet:** nya/ändrade persisterade fält finns i backup-kedjan och har rundturstest. (regel 1)
- [ ] **Tester:** lagt till/uppdaterat på alla berörda nivåer. (regel 2)
- [ ] **Krav:** KRAVLISTA.md (och ev. README/version) speglar ändringen. (regel 3)
- [ ] **Återbruk:** ingen ny komponent som dubblerar en befintlig delad. (regel 4)

---

## Bygg & test (kommandon)

```bash
./gradlew :app:compileDebugKotlin            # kompilera
./gradlew :app:testDebugUnitTest             # enhetstester (JUnit/MockK/Turbine)
./gradlew :app:compileDebugAndroidTestKotlin # kompilera instrumenttester
./gradlew :app:connectedDebugAndroidTest     # instrumenttester (kräver emulator/enhet)
```

**Tester körs i GitHub Actions** — inte i sessionen. Vid push mot `master` kör
`.github/workflows/android.yml` kompilering + enhetstester. Instrumenttester
(`.github/workflows/instrumented.yml`, emulator) körs **endast på pull requests** mot
`master` — ren push triggar dem inte. Från telefonen: **pusha branchen och öppna en PR
mot `master`** så kör Actions båda.

> I fjärr-/telefonsessioner finns ingen Android SDK och Google Maven kan vara blockerad —
> försök därför **inte** köra `./gradlew` där. Lita på CI.

## Arkitektur i korthet

`Compose → ViewModel (StateFlow<UiState>) → Repository → Room/DataStore`.
Paket under `app/src/main/kotlin/se/partee71/fonder/`:
`data/` (auth, datastore, repository, room) · `di/` · `domain/` (model, usecase) ·
`ui/<feature>/` + `ui/components/` + `ui/diagram/` + `ui/theme/` · `worker/`.
Detaljer i [README.md](README.md).

## Skills i detta repo (`.claude/skills/`)

Generella Android/Kotlin-skills är kopierade från Dagboken: `android-dev`,
`android-data-layer`, `android-gradle-logic`, `compose-expert`, `kotlin-coroutines`,
`kotlin-flows`. Projektspecifika skills (backup, kravlista, tester, delade komponenter)
tas in från Dagboken och anpassas när respektive feature byggs.

| Skill | När |
|---|---|
| `refine-issue` | Förfina en idé/bugg till ett planerat GitHub-issue (med DoD enligt de fyra reglerna). |
| `implement-issue` | Genomför ett issue/bugg/feature hela vägen till PR enligt de fyra reglerna. |
