// Test för regel4-check-hooken och baslinjeskriptet. Körs med: node --test '.claude/hooks/test/*.test.mjs'
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { scopesFor, parsePatterns, parseSections, parseAllowlist, findViolations, findRepoRoot } from '../regel4-check.mjs';
import { baselineLines, renderAllowlist, BASELINE_REASON } from '../../../tools/regel4-baseline.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../../..');
const patternsRel = 'app/src/test/resources/ui-forbidden.txt';
const allowRel = 'app/src/test/resources/ui-allowlist.txt';
const examplesRel = 'app/src/test/resources/ui-forbidden-examples.txt';
const pkg = 'app/src/main/kotlin/se/partee71/fonder';
const hookFiles = ['regel4-check.mjs', 'session-start.sh', 'maven-central-mirror.init.gradle.kts'];

const readPatterns = () => readFileSync(path.join(repoRoot, patternsRel), 'utf8');

/** Kortkomponenten som mönsterfilen anvisar i stället för `Card(` – hooken ska tipsa om den. */
const cardReplacement = () => parsePatterns(readPatterns()).feature.find((r) => r.regex.test('Card(')).replacement;

/**
 * Tillfälligt repo (i en egen föräldrakatalog, med Fonders katalognamn) med de riktiga mönster- och
 * undantagsfilerna och en kopia av hooken – hooken kontrollerar bara repot den själv ligger i.
 */
function makeProject(allowlist) {
  const parent = mkdtempSync(path.join(tmpdir(), 'regel4-'));
  const dir = path.join(parent, 'fonder');
  mkdirSync(path.join(dir, 'app/src/test/resources'), { recursive: true });
  mkdirSync(path.join(dir, '.claude/hooks'), { recursive: true });
  copyFileSync(path.join(repoRoot, patternsRel), path.join(dir, patternsRel));
  if (allowlist !== undefined) writeFileSync(path.join(dir, allowRel), allowlist);
  else copyFileSync(path.join(repoRoot, allowRel), path.join(dir, allowRel));
  for (const f of hookFiles) copyFileSync(path.join(repoRoot, '.claude/hooks', f), path.join(dir, '.claude/hooks', f));
  return { dir, parent };
}

function writeKt(dir, rel, content) {
  const abs = path.join(dir, rel);
  mkdirSync(path.dirname(abs), { recursive: true });
  writeFileSync(abs, content);
  return abs;
}

function runHook(projectDir, filePath, { tool = 'Write', hookRoot = projectDir } = {}) {
  return spawnSync('node', [path.join(hookRoot, '.claude/hooks/regel4-check.mjs')], {
    input: JSON.stringify({ tool_name: tool, tool_input: { file_path: filePath }, cwd: projectDir }),
    env: { ...process.env, CLAUDE_PROJECT_DIR: projectDir },
    encoding: 'utf8',
  });
}

const featureCard = `package x\n\n@Composable\nfun FundRow() {\n    Card(modifier = Modifier) { Text("Fond") }\n}\n`;
const featureFileRel = `${pkg}/ui/hem/FundRow.kt`;

test('stoppar Card( i feature-kod med exit 2 och tipsar om den delade kortkomponenten', () => {
  const { dir } = makeProject();
  const r = runHook(dir, writeKt(dir, featureFileRel, featureCard));
  assert.equal(r.status, 2);
  assert.match(r.stderr, /FundRow\.kt:5/);
  assert.ok(cardReplacement(), 'ui-forbidden.txt har en ersättning för Card(');
  assert.ok(r.stderr.includes(cardReplacement()), r.stderr);
});

test('fungerar när sessionens projektrot är en föräldrakatalog med flera repon', () => {
  const { dir, parent } = makeProject();
  const r = runHook(parent, writeKt(dir, featureFileRel, featureCard), { hookRoot: dir });
  assert.equal(r.status, 2, 'hooken måste hitta repot från filen, inte från projektroten');
  assert.match(r.stderr, new RegExp(`${pkg}/ui/hem/FundRow\\.kt:5`));
});

test('kontrollerar bara sitt eget repo – ett grannrepo med egen mönsterfil lämnas åt sin egen hook', () => {
  const own = makeProject();
  const other = makeProject();
  const r = runHook(other.dir, writeKt(other.dir, featureFileRel, featureCard), { hookRoot: own.dir });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stderr, '');
});

test('släpper igenom samma rad i ui/components', () => {
  const { dir } = makeProject();
  const r = runHook(dir, writeKt(dir, `${pkg}/ui/components/AppCard.kt`, featureCard));
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stderr, '');
});

/** Delade exempel – samma fil körs av UiConsistencyTest, så hook och bygge tolkar lika. */
const readExamples = () => parseSections(readFileSync(path.join(repoRoot, examplesRel), 'utf8'));

// Feature-filen som exempelfilens [allowlist]-rader gäller (samma som UiConsistencyTest) –
// läses ur exempelfilen, så att hook och bygge alltid testar mot samma sökväg.
const featureFile = parseAllowlist(readExamples().allowlist[0].split('\t')[0])[0].file;
const componentFile = `${pkg}/ui/components/X.kt`;
const diagramFile = `${pkg}/ui/diagram/X.kt`;

test('delade exempel: tillåtna rader släpps i feature-kod', () => {
  const scopes = parsePatterns(readPatterns());
  const ok = readExamples().ok;
  assert.ok(ok.length >= 20);
  for (const line of ok) assert.deepEqual(findViolations(featureFile, line, scopes, []), [], line);
});

test('delade exempel: hårdkodat utseende, råa M3-komponenter med delad motsvarighet och Vico stoppas', () => {
  const scopes = parsePatterns(readPatterns());
  const bad = readExamples().stopp;
  assert.ok(bad.length >= 20);
  for (const line of bad) assert.ok(findViolations(featureFile, line, scopes, []).length > 0, line);
});

test('delade exempel: delade komponenter får använda M3 och hårdkodat utseende, men bara de får linda in PullToRefreshBox och ExposedDropdownMenuBox', () => {
  const scopes = parsePatterns(readPatterns());
  const ex = readExamples();
  for (const line of ex['komponent-ok']) assert.deepEqual(findViolations(componentFile, line, scopes, []), [], line);
  for (const line of ex['komponent-stopp']) assert.ok(findViolations(diagramFile, line, scopes, []).length > 0, line);
});

test('delade exempel: diagrammen i ui/diagram får använda Vico och rita själva, inget annat får använda Vico', () => {
  const scopes = parsePatterns(readPatterns());
  const ex = readExamples();
  assert.ok(ex['diagram-ok'].length >= 4);
  for (const line of ex['diagram-ok']) assert.deepEqual(findViolations(diagramFile, line, scopes, []), [], line);
  for (const line of ex['diagram-stopp']) assert.ok(findViolations(componentFile, line, scopes, []).length > 0, line);
});

test('Vico stoppas av hooken utanför ui/diagram men släpps i ui/diagram', () => {
  const { dir } = makeProject();
  const src = 'package x\n\n@Composable\nfun Chart() {\n    CartesianChartHost(chart, model)\n}\n';
  const r = runHook(dir, writeKt(dir, `${pkg}/ui/fond/FondDetaljScreen.kt`, src));
  assert.equal(r.status, 2);
  assert.match(r.stderr, /kod utanför ui\/diagram/);
  assert.equal(runHook(dir, writeKt(dir, `${pkg}/ui/diagram/FundLineChart.kt`, src)).status, 0);
});

test('PullToRefreshBox stoppas utanför ui/components – även i data- och di-kod', () => {
  const { dir } = makeProject();
  const src = 'fun x() {\n    PullToRefreshBox(isRefreshing = false, onRefresh = {}) { }\n}\n';
  const r = runHook(dir, writeKt(dir, `${pkg}/di/AppModule.kt`, src), { tool: 'Edit' });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /kod utanför ui\/components/);
  assert.equal(runHook(dir, writeKt(dir, `${pkg}/ui/components/PullToRefreshContainer.kt`, src), { tool: 'Edit' }).status, 0);
});

test('Dispatchers stoppas av hooken utanför di/ men släpps i di/', () => {
  const { dir } = makeProject();
  const src = 'package x\n\nsuspend fun x() = withContext(Dispatchers.IO) { }\n';
  const r = runHook(dir, writeKt(dir, `${pkg}/data/network/AvanzaClient.kt`, src));
  assert.equal(r.status, 2);
  assert.match(r.stderr, /kod utanför di\//);
  assert.equal(runHook(dir, writeKt(dir, `${pkg}/di/DispatchersModule.kt`, src)).status, 0);
});

test('delade exempel: ett undantag gäller bara sin fil och sin symbol', () => {
  const scopes = parsePatterns(readPatterns());
  for (const example of readExamples().allowlist) {
    const [allowLine, code, expected] = example.split('\t');
    const hits = findViolations(featureFile, code, scopes, parseAllowlist(allowLine));
    assert.equal(hits.length === 0 ? 'ok' : 'stopp', expected, example);
  }
});

test('delade exempel: allowlist-rad utan symbol eller motivering avvisas', () => {
  for (const line of readExamples()['allowlist-fel']) {
    assert.throws(() => parseAllowlist(line), /ui-allowlist\.txt rad 1/, line);
  }
});

test('allowlist-rad släpper igenom just den filen och symbolen via hooken', () => {
  const { dir } = makeProject(`# test\n${featureFileRel}:Card( – test\n`);
  assert.equal(runHook(dir, writeKt(dir, featureFileRel, featureCard)).status, 0);
});

test('trasig allowlist stoppar hooken med radnummer', () => {
  const { dir } = makeProject(`# test\n${featureFileRel} – saknar symbol\n`);
  const r = runHook(dir, writeKt(dir, featureFileRel, featureCard));
  assert.equal(r.status, 2);
  assert.match(r.stderr, /ui-allowlist\.txt rad 2/);
});

test('delade exempel: scope per sökväg', () => {
  const cases = readExamples().scope;
  assert.ok(cases.length >= 10);
  for (const line of cases) {
    const [file, expected = ''] = line.split('\t');
    assert.deepEqual(scopesFor(file), expected ? expected.split(',') : [], file);
  }
});

test('filer utanför repo, utan mönsterfil eller av annan typ ignoreras', () => {
  const { dir, parent } = makeProject();
  assert.equal(runHook(dir, writeKt(dir, 'docs/Card.kt', featureCard)).status, 0);
  const outside = writeKt(parent, `annat-repo/${pkg}/ui/hem/X.kt`, featureCard);
  assert.equal(findRepoRoot(outside), null);
  assert.equal(runHook(parent, outside, { hookRoot: dir }).status, 0);
});

test('tom eller trasig indata ger exit 0', () => {
  const hook = path.join(repoRoot, '.claude/hooks/regel4-check.mjs');
  assert.equal(spawnSync('node', [hook], { input: 'inte json', encoding: 'utf8' }).status, 0);
});

test('mönsterfilen har alla scope, giltiga regexar och en ersättning per rad', () => {
  const scopes = parsePatterns(readPatterns());
  assert.deepEqual(Object.keys(scopes).sort(), ['component-only', 'dispatchers', 'events', 'feature', 'vico']);
  assert.ok(scopes.feature.length >= 15);
  assert.ok(scopes['component-only'].length >= 2);
  assert.ok(scopes.vico.length >= 3);
  assert.ok(scopes.dispatchers.length >= 1);
  assert.ok(scopes.events.length >= 2);
  for (const rule of Object.values(scopes).flat()) assert.ok(rule.replacement, rule.source);
});

test('baslinjen i ui-allowlist.txt är aktuell och täcker alla brott i app/src/main', () => {
  const current = readFileSync(path.join(repoRoot, allowRel), 'utf8');
  assert.equal(current, renderAllowlist(repoRoot), 'kör node tools/regel4-baseline.mjs');
  for (const line of baselineLines(repoRoot)) assert.ok(line.endsWith(BASELINE_REASON), line);
  assert.doesNotThrow(() => parseAllowlist(current));
});

test('baslinjeskriptet behåller handskrivna undantag och tar bort rättade brott', () => {
  const manual = `${featureFileRel}:Card( – användarens ok i #999`;
  const stale = `${pkg}/ui/hem/Rattad.kt:Card( – ${BASELINE_REASON}`;
  const { dir } = makeProject(`# gammalt huvud\n${manual}\n${stale}\n`);
  writeKt(dir, `${pkg}/ui/portfolj/Ny.kt`, 'fun x() {\n    AlertDialog(onDismissRequest = {})\n}\n');
  const out = renderAllowlist(dir);
  assert.ok(out.includes(manual), out);
  assert.ok(!out.includes(stale), 'en rättad fil ska falla ur baslinjen');
  assert.ok(out.includes(`${pkg}/ui/portfolj/Ny.kt:AlertDialog( – ${BASELINE_REASON}`), out);
  assert.deepEqual(renderAllowlist(dir), out, 'samma källkod ger samma fil');
});

test('hook-kommandona i settings.json hittar skripten både i repot och från en föräldrakatalog', () => {
  const settings = JSON.parse(readFileSync(path.join(repoRoot, '.claude/settings.json'), 'utf8'));
  const postCmd = settings.hooks.PostToolUse[0].hooks[0].command;
  const startCmd = settings.hooks.SessionStart[0].hooks[0].command;

  const { dir, parent } = makeProject();
  const file = writeKt(dir, featureFileRel, featureCard);
  const input = JSON.stringify({ tool_name: 'Write', tool_input: { file_path: file } });

  for (const projectDir of [dir, parent]) {
    const post = spawnSync('bash', ['-c', postCmd], {
      input, env: { ...process.env, CLAUDE_PROJECT_DIR: projectDir }, encoding: 'utf8',
    });
    assert.equal(post.status, 2, `PostToolUse med projektrot ${projectDir}: ${post.stderr}`);

    const home = mkdtempSync(path.join(tmpdir(), 'hem-'));
    const start = spawnSync('bash', ['-c', startCmd], {
      env: { ...process.env, CLAUDE_PROJECT_DIR: projectDir, CLAUDE_CODE_REMOTE: 'true', HOME: home },
      encoding: 'utf8',
    });
    assert.equal(start.status, 0, start.stderr);
    assert.match(start.stdout, /Android SDK: /);
    assert.ok(existsSync(path.join(home, '.gradle/init.d/maven-central-mirror.init.gradle.kts')), 'spegeln för Maven Central');
  }

  const none = spawnSync('bash', ['-c', postCmd], {
    input, env: { ...process.env, CLAUDE_PROJECT_DIR: mkdtempSync(path.join(tmpdir(), 'tom-')) }, encoding: 'utf8',
  });
  assert.equal(none.status, 0, 'utan hook i projektet ska kommandot vara tyst');
});

/** Tillfälligt git-repo med hooken där origin/master pekar på första committen. */
function makeGitProject(name = 'fonder', parent) {
  const made = makeProject();
  let dir = made.dir;
  if (parent) {
    dir = path.join(parent, name);
    mkdirSync(path.join(dir, 'app/src/test/resources'), { recursive: true });
    mkdirSync(path.join(dir, '.claude/hooks'), { recursive: true });
    for (const rel of [patternsRel, allowRel]) copyFileSync(path.join(made.dir, rel), path.join(dir, rel));
    for (const f of hookFiles) copyFileSync(path.join(repoRoot, '.claude/hooks', f), path.join(dir, '.claude/hooks', f));
  }
  const git = (...args) => {
    const r = spawnSync('git', args, { cwd: dir, encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
  };
  git('init', '-q');
  git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '--allow-empty', '-m', 'bas');
  git('update-ref', 'refs/remotes/origin/master', 'HEAD');
  return { dir, parent: parent ?? made.parent, git };
}

function runStop(projectDir, { input = {}, hookRoot = projectDir } = {}) {
  return spawnSync('node', [path.join(hookRoot, '.claude/hooks/regel4-check.mjs'), '--stop'], {
    input: JSON.stringify({ hook_event_name: 'Stop', ...input }),
    env: { ...process.env, CLAUDE_PROJECT_DIR: projectDir },
    encoding: 'utf8',
  });
}

test('Stop blockerar när en ändrad eller ny fil har förbjudna mönster', () => {
  const { dir } = makeGitProject();
  writeKt(dir, featureFileRel, featureCard);
  const r = runStop(dir);
  assert.equal(r.status, 0);
  const out = JSON.parse(r.stdout);
  assert.equal(out.decision, 'block');
  assert.match(out.reason, /FundRow\.kt:5/);
  assert.ok(out.reason.includes(cardReplacement()));
});

test('Stop ser även filer som redan committats på branchen', () => {
  const { dir, git } = makeGitProject();
  writeKt(dir, featureFileRel, featureCard);
  git('add', '.');
  git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '-m', 'rad');
  assert.equal(JSON.parse(runStop(dir).stdout).decision, 'block');
});

test('Stop är tyst för oförändrade filer, rena ändringar och när den redan blockerat en gång', () => {
  const { dir, git } = makeGitProject();
  writeKt(dir, featureFileRel, featureCard);
  git('add', '.');
  git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '-m', 'på master');
  git('update-ref', 'refs/remotes/origin/master', 'HEAD');
  writeKt(dir, `${pkg}/ui/hem/FundList.kt`, 'package x\n\nfun ok() = Unit\n');
  assert.equal(runStop(dir).stdout, '', 'gamla träffar på master hör inte till den här ändringen');

  writeKt(dir, `${pkg}/ui/hem/FundRow2.kt`, featureCard);
  assert.equal(runStop(dir, { input: { stop_hook_active: true } }).stdout, '', 'blockerar högst en gång i följd');
});

test('Stop-kommandot i settings.json hittar skriptet både i repot och från en föräldrakatalog', () => {
  const settings = JSON.parse(readFileSync(path.join(repoRoot, '.claude/settings.json'), 'utf8'));
  const stopCmd = settings.hooks.Stop[0].hooks[0].command;
  const { dir, parent } = makeGitProject();
  writeKt(dir, featureFileRel, featureCard);
  for (const projectDir of [dir, parent]) {
    const r = spawnSync('bash', ['-c', stopCmd], {
      input: '{}', env: { ...process.env, CLAUDE_PROJECT_DIR: projectDir }, encoding: 'utf8',
    });
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /"decision":"block"/, `projektrot ${projectDir}`);
  }
});

test('Stop blockerar när undantagsfilen är trasig', () => {
  const { dir } = makeGitProject();
  writeFileSync(path.join(dir, allowRel), 'saknar-symbol\n');
  const out = JSON.parse(runStop(dir).stdout);
  assert.equal(out.decision, 'block');
  assert.match(out.reason, /Regel 4:.*rad 1/);
});

test('Stop kontrollerar varje repo med dess egen hook när projektroten har flera repon', () => {
  const { dir, parent } = makeGitProject();
  writeKt(dir, featureFileRel, featureCard);
  const other = makeGitProject('Annat', parent).dir;
  writeKt(other, `${pkg}/ui/hem/OtherRow.kt`, featureCard);
  const utanHook = path.join(parent, 'UtanHook');
  mkdirSync(path.join(utanHook, 'app/src/test/resources'), { recursive: true });
  copyFileSync(path.join(dir, patternsRel), path.join(utanHook, patternsRel));
  writeKt(utanHook, `${pkg}/ui/hem/Ignorerad.kt`, featureCard);

  const out = JSON.parse(runStop(parent, { hookRoot: dir }).stdout);
  assert.equal(out.decision, 'block');
  assert.match(out.reason, /fonder\/.*FundRow\.kt:5/);
  assert.match(out.reason, /OtherRow\.kt:5/);
  assert.doesNotMatch(out.reason, /Ignorerad/, 'ett repo utan egen hook har egna regler och lämnas därhän');
});
