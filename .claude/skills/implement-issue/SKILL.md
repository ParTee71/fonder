---
name: implement-issue
description: Implement a GitHub issue, bug fix, feature, or chore in Fonder end to end — research, plan, change code following the project's patterns, add tests at every level, update requirements and the backup contract, then commit, push, and open a PR. Use when the user wants to "implement issue #N", "fix this bug", "build this feature", "do this ticket", "pick up #N", or hands off from refine-issue. Handles bugs, features, and chores alike.
---

# Implement an Issue (bug · feature · chore)

This is the "do the work" counterpart to `refine-issue`. It drives a change from a ticket
or description all the way to a pushed branch with a PR, honouring the four non-negotiable
rules in [CLAUDE.md](../../../CLAUDE.md). Don't skip steps to "save time" — the rules are the
definition of done, not optional polish.

> **Tests run in GitHub Actions, not in the session.** From the phone/web there is no
> Android SDK and Google Maven may be blocked — do not try to run `./gradlew`. Reason about
> correctness, write the tests, push, and let CI run them (see CLAUDE.md). Note:
> `android.yml` (compile + unit tests) runs on both push and PR against `master`, but
> `instrumented.yml` (emulator tests) runs **only on pull requests** — a plain push does not
> trigger it, so open a PR to get instrumented coverage. In Android Studio you can run
> everything locally.

## Step 1 — Understand the work

- If given an issue number, fetch it: `mcp__github__issue_read (repo: partee71/fonder, issue_number: N)`.
  Read its acceptance criteria and Definition of Done — those are your contract.
- If it's a loose description and the scope is unclear or risky, consider running
  `refine-issue` first (or at least restate scope and confirm) before writing code.
- Classify: **bug**, **feature**, or **chore** — it changes the workflow below slightly.

## Step 2 — Reproduce (bugs) / pin the target (features)

- **Bug:** establish the exact reproduction and the expected vs actual behaviour. Find the
  faulty code path before changing anything. You will encode the repro as a failing test.
- **Feature/chore:** identify the precise insertion point and the user-visible outcome that
  marks "done".

## Step 3 — Research existing patterns (before writing code)

Mandatory — the project values consistency over cleverness.

- Open a sibling ViewModel / screen / repository and copy its shape (state exposure, events,
  error mapping) — e.g. `PortfoljViewModel`, `TransaktionerViewModel`, `FundSearchViewModel`.
  See `android-dev`'s existing-pattern audit.
- UI? Find the shared component to reuse/extend (`EmptyState`, `SelectField`, `DateField` in
  `ui/components/`, `FundLineChart` in `ui/diagram/`) — don't fork a new variant
  (`shared-ui-components`).
- Data? Note Room entities (`FundEntity`, `TransactionEntity`, `FundPriceEntity`), DAOs, and
  whether a migration is needed (bump `AppDatabase` version, write a `MigrationXYTest`
  following the existing `Migration12Test`/`Migration23Test` pattern).
- External fund-data source involved? `HandelsbankenFondlistaClient`/`HandelsbankenHtmlParser`
  and `AvanzaClient`/`AvanzaJsonParser`/`AvanzaPriceSource` are isolated, undocumented-source
  integrations (KRAVLISTA TP-10, TP-14) — keep new source logic similarly isolated in
  `data/network/` and don't leak parsing details into repositories/ViewModels.
- Map the touched code to `KRAVLISTA.md` requirement IDs (`ÖV`, `TP`, `UI`, `NAV`, `POR`,
  `TRX`, `IMP`, `NFR`) and to existing tests.

## Step 4 — Plan the change

Write a short internal plan (and share it if the change is non-trivial): files to touch, the
order, the tests to add, the migration + backup-contract edits, and the requirement rows to
update. For anything architecturally significant or ambiguous, confirm with the user via
`AskUserQuestion` before coding.

## Step 5 — Branch

Work on the designated development branch (see CLAUDE.md / task instructions); create it if
missing. Never commit straight to `master`.

## Step 6 — Implement (tests alongside, not after)

Follow the architecture: `Compose → ViewModel(StateFlow<UiState>) → Repository → Room/DataStore`,
Hilt DI, errors mapped in the repository layer.

- **Bug:** first add a **regression test that fails** (reproduces the bug), then make the
  minimal change that turns it green. Don't expand scope while you're in there.
- **Feature:** build behind the existing patterns; reuse shared components; keep ViewModels
  testable (inject dispatchers).
- **Persisted data changed?** Write the Room migration (`AppDatabase` version bump + a
  `MigrationXYTest`), and account for the new field in the backup contract
  (`BackupRepository`/`StubBackupRepository` in `data/repository/BackupRepository.kt`).
  Drive backup itself is still a stub pending its own feature build-out (NFR-1,
  *"backup planerad"*) — don't invent a JSON backup format for it; just make sure the stub's
  contract and any in-repo notes reflect the new field so the real implementation won't miss
  it later (`data-safety-backup`).
- Keep diffs focused. Match surrounding style. UI strings in Swedish via `strings.xml`.
- Don't log full HTTP responses or secrets from the fund-data sources; add
  `contentDescription`/adequate touch targets for new interactive UI.

## Step 7 — Tests at every touched level (rule 2)

- **Unit** (`app/src/test`): ViewModel/domain/use-case/parser/mapper logic (`PortfolioCalc`,
  `MoneyFormat`, `FundCompanyMatcher`, `FundNameMatcher`, `TransactionFormValidator`,
  `HandelsbankenHtmlParser`, `AvanzaJsonParser`, …), fakes over mocks for the data layer,
  Turbine for flows.
- **Instrumented** (`app/src/androidTest`): Compose UI behaviour; Room DAO round-trips;
  `MigrationXYTest` for schema changes.
- Update — never delete or weaken — existing tests the change affects. If you add a method to
  a DAO interface, update its fakes too (or the build breaks).

## Step 8 — Self-review against the four rules (slutkontroll)

Run the CLAUDE.md final checklist:
- [ ] **Data safety** — persisted changes have a Room migration + test, and are accounted for
  in the backup contract (or the issue explicitly notes backup is still pending). (rule 1)
- [ ] **Tests** — added/updated at every touched level. (rule 2)
- [ ] **Requirements** — `KRAVLISTA.md` (and README/version if scope changed) updated. (rule 3)
- [ ] **Reuse** — no duplicate component. (rule 4)
- [ ] **Architecture** — follows established patterns.

Trace each issue acceptance-criterion to the code/test that satisfies it. If you can't,
you're not done.

## Step 9 — Commit & push

- Conventional, **Swedish-OK** commit messages scoped to the change:
  `feat(transaktioner): …`, `fix(portfolj): …`, `test(...)`, `chore(...)`.
- Small, coherent commits over one giant blob. Include the commit trailers required by the
  task/CLAUDE.md instructions.
- Push the development branch: `git push -u origin <branch>` (retry with backoff on network errors).

## Step 10 — Pull request (only when the user wants one)

Do **not** open a PR unless asked. When you do:
- Target `master`. Title `<area>: <imperative summary>`.
- Body: what changed, how it maps to the four rules, the test plan, and `Closes #N` to link
  the issue. Mirror any PR template if the repo has one.
- Opening the PR triggers GitHub Actions (`android.yml` on push+PR, `instrumented.yml` on PR
  only) — that's where the tests actually run. Offer to watch the PR
  (`subscribe_pr_activity`) and drive CI green.

## Multiple issues

Implement them **one at a time**, each its own focused commit set (and PR if requested).
Don't batch unrelated changes into one branch — it muddies review and CI.

**Only one open PR against `master` at a time.** If a PR was requested, get it merged (or
closed) before opening the next one — don't have two PRs open against `master`
simultaneously. Parallel PRs based on the same stale `master` almost always collide on
`KRAVLISTA.md`/`README.md`/`versionName` (both bump the same next version number), causing
an avoidable merge conflict on whichever merges second. Implementing on separate branches
ahead of time is fine; just sequence the PR-open-through-merge step.

## Anti-patterns

- Fixing a bug without a regression test.
- Changing persisted data without a Room migration + test, or without accounting for it in
  the backup contract.
- A new component that duplicates a shared one (`ui/components/`, `ui/diagram/`).
- Deleting/`@Ignore`-ing a failing test to "go green".
- Trying to run `./gradlew` in a remote/phone session instead of trusting CI.
- Inventing a real Drive backup implementation as a side effect of an unrelated feature —
  that's its own issue; just keep the stub's contract accurate.
