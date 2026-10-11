---
name: mockup
description: 'Processregeln "mockup före ny GUI" i Fonder — ny skärm, ny delad komponent eller tydligt ändrat utseende/beteende visas som en mockup (Design-canvas) för användaren och godkänns innan någon kod skrivs, för att slippa byggen bara för att se hur det blev. Ladda denna ALLTID innan du bygger eller ändrar något som syns. Trigger-ord: ny skärm, ny vy, ny flik, ny komponent, visa, mockup, skiss, design, prototyp, canvas, hur ska det se ut, layout, ändra utseendet, gör om skärmen, nytt kort, ny knapp, diagram, flöde, GUI, UI.'
---

# Mockup före ny GUI

Utvecklingen sker mest från telefonen, och ett bygge (debug-snapshot eller release) för att
"se hur det blev" är dyrt. Därför visas nytt utseende och beteende först som en mockup, och
koden skrivs först efter användarens ok.

## När

| Ändring | Mockup? |
|---|---|
| Ny skärm | Ja |
| Ny delad komponent i `ui/components/` eller `ui/diagram/` | Ja |
| Tydligt ändrat utseende eller beteende (syns i en tumnagel) | Ja |
| Padding, ordval, ikonbyte, färgjustering inom temat | Nej – beskrivningen i PR:en räcker (skärmdumpsdiffen när Roborazzi finns) |
| Ren logik utan synlig ändring | Nej |

## Mallen

Det finns **ingen gemensam canvas än**. Den första mockupen som byggs blir mallen: ett
komponentark med katalogen i `ui/components/` och `ui/diagram/` (skill `shared-ui-components`) i
ljust och mörkt. Länken skrivs då in här och i CLAUDE.md:s processregel. Mallen är en
approximation – koden i `ui/theme` och de byggda komponenterna är facit.

## Så bygger du en mockup

1. Skapa en Design-canvas (Artifact-typen "Design") – en per issue, eller nya artboards i
   issuets befintliga canvas.
2. Telefonyta 390 × 844 eller högre; ljust tema först, mörkt när det spelar roll (vinst/förlust,
   diagramkurvor och mässing ändrar ton mellan temana).
3. Bygg av **befintliga komponenter** (`CardTitleRow`, `PeriodRow`, `RiskBadge`, `EmptyState`,
   `FundLineChart` …) i Fonders designspråk (skill `ui-style`): grön petrol, sparsam mässing,
   Space Grotesk, belopp med tabulära siffror, vinst/förlust med tecken och färg.
4. Riktiga svenska texter och **påhittad men realistisk portföljdata**: fem–sju fonder med
   trovärdiga namn och fondbolag, risknivå 1–7, innehav på några tusen till några hundra tusen
   kronor, avkastning med både plus och minus, en kurshistorik med köpmarkörer, ett bytesförslag.
   Belopp i svenskt format ("12 345,67 kr", "+4,2 %", "−1,8 %"). **Aldrig användarens riktiga
   innehav, belopp eller transaktioner** – inte heller avrundade eller "lite ändrade".
5. **Finns elementet redan i appen** → återanvänd det på samma sätt som tidigare. Det är ingen
   designfråga. Bara för något **helt nytt** med en verklig öppen fråga → två artboards sida vid
   sida med en kort beskrivning av skillnaden.
6. Allt som saknas i katalogen märks tydligt **"NY KOMPONENT"** – det byggs då som delad del
   (regel 4), aldrig lokalt i featuren.
7. Visa de tillstånd som betyder något: tomt, laddar (väntesnurran i rubrikraden, NAV-6), fel,
   okänd data ("Risk okänd", ANA-4), lång text som kapas (UI-6).

## Visa och vänta

- Ge länken och en eller två meningar om vad som visas och vilka val som är öppna.
- **Skriv ingen kod innan användaren godkänt** eller bett om ändringar. Ändringar görs i samma canvas.
- Länken läggs i issuet under rubriken **Design** (skill `refine-issue`).

## I PR:en

- Mockup-länken och, när Roborazzi finns *(kommer i verkstads-PR:en)*, skärmdumpar ljust + mörkt
  av det byggda bredvid. Till dess: beskriv resultatet och länka debug-snapshoten.
- Avvikelser från mockupen förklaras i PR-beskrivningen.

## Mockup är inte kod

Mockupens HTML/CSS kopieras aldrig till appen. Översätt till temat (`ui/theme`) och delade
komponenter. Färgvärden i mockupen är en approximation av temat, inte källan.
