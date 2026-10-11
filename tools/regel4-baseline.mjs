#!/usr/bin/env node
// Genererar baslinjen i app/src/test/resources/ui-allowlist.txt: varje befintligt brott mot
// ui-forbidden.txt i app/src/main, en rad per fil + matchad symbol. Samma tolkning som hooken
// och UiConsistencyTest (funktionerna importeras från hooken – ingen egen kopia).
//
//   node tools/regel4-baseline.mjs           # skriv om baslinjen
//   node tools/regel4-baseline.mjs --check   # exit 1 om filen inte är aktuell (t.ex. ett rättat brott står kvar)
//
// Bara rader med baslinjens motivering skrivs om; handskrivna undantag (med användarens ok)
// står kvar överst. Utdata är sorterad, så samma källkod ger alltid samma fil.

import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PATTERNS_FILE, ALLOWLIST_FILE, parsePatterns, findViolations } from '../.claude/hooks/regel4-check.mjs';

export const BASELINE_REASON = 'baslinje 2026-10 – rättas i skärm-PR:erna';

const HEADER = `# Undantag från regel 4 (skill shared-ui-components).
# Format: <sökväg relativt repots rot>:<symbol> – <motivering>
# Varje ny handskriven rad kräver användarens uttryckliga ok i PR:en.
#
# Raderna med motiveringen "${BASELINE_REASON}" är baslinjen: brotten som fanns när
# kontrollen infördes. De genereras av \`node tools/regel4-baseline.mjs\` och ska bara bli färre –
# kör skriptet igen när en skärm rättats. Lägg aldrig till en baslinjerad för hand.`;

function kotlinFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) return kotlinFiles(p);
    return e.name.endsWith('.kt') ? [p] : [];
  });
}

/** Baslinjeraderna för repot i [root], sorterade och utan dubbletter. */
export function baselineLines(root) {
  const scopes = parsePatterns(readFileSync(path.join(root, PATTERNS_FILE), 'utf8'));
  const entries = new Set();
  for (const abs of kotlinFiles(path.join(root, 'app/src/main'))) {
    const rel = path.relative(root, abs).split(path.sep).join('/');
    for (const hit of findViolations(rel, readFileSync(abs, 'utf8'), scopes, [])) {
      entries.add(`${rel}:${hit.match.trim()} – ${BASELINE_REASON}`);
    }
  }
  return [...entries].sort();
}

/** Hela allowlist-filen: huvudet, handskrivna rader (oförändrade) och den nya baslinjen. */
export function renderAllowlist(root) {
  const allowPath = path.join(root, ALLOWLIST_FILE);
  const current = existsSync(allowPath) ? readFileSync(allowPath, 'utf8') : '';
  const manual = current
    .split(/\r?\n/)
    .filter((l) => l.trim() && !l.startsWith('#') && !l.endsWith(BASELINE_REASON));
  const parts = [HEADER];
  if (manual.length) parts.push('', '# Handskrivna undantag', ...manual);
  parts.push('', '# Baslinje', ...baselineLines(root));
  return parts.join('\n') + '\n';
}

function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const allowPath = path.join(root, ALLOWLIST_FILE);
  const next = renderAllowlist(root);
  if (process.argv.includes('--check')) {
    const current = existsSync(allowPath) ? readFileSync(allowPath, 'utf8') : '';
    if (current !== next) {
      process.stderr.write(`${ALLOWLIST_FILE} är inte aktuell – kör node tools/regel4-baseline.mjs\n`);
      return 1;
    }
    return 0;
  }
  writeFileSync(allowPath, next);
  const count = next.split('\n').filter((l) => l.endsWith(BASELINE_REASON)).length;
  process.stdout.write(`${ALLOWLIST_FILE}: ${count} baslinjerader\n`);
  return 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main());
}
