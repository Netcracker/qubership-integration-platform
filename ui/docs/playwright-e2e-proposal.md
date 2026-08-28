# Proposal: browser-level tests for the UI workspace

Status: draft for team discussion. Nothing here is implemented.

## The gap this closes

The `ui` workspace has 221 Jest suites under `ui/tests/`, around 61% line coverage on a
local July 2026 run. They run in `jsdom` through `ts-jest`, and they are good at hooks,
utilities, and component logic. They cannot fail on a defect that only exists once a real
browser has laid the page out.

Issue [#679](https://github.com/Netcracker/qubership-integration-platform/issues/679) is the
example that prompted this note. Both of its complaints are of that kind:

1. After you submit a renamed snapshot, the input border stays visible, so the field looks
   unsaved.
2. Labels render too small in the table column, but at the right size while the label editor
   is open.

Neither is reachable from `jsdom`. It has no layout engine, no cascade resolution for the
Ant Design token stylesheet, and no notion of a computed pixel size. A Jest suite can assert
that `<EntityLabels>` received three labels; it cannot assert that they came out 12px in one
state and 14px in another.

The same blind spot covers the rest of the visual surface: column resizing, sticky table
headers under `scroll.y`, the VS Code webview theme bridge, Monaco sizing, and the graph
canvas.

## Where this came up

We are building a skill that takes a GitHub issue, fixes it, and opens a pull request with an
autonomy label. For a visual bug, the agent has to produce evidence a reviewer can check
without rebuilding the stack. Screenshots taken through a harness-specific browser are not
that: they live in a chat transcript, and only one harness has them.

Playwright produces PNG files on disk and a script that reproduces them. That is attachable
to a pull request and it runs anywhere `bash` runs, which matters because Codex and Cursor
have no browser of their own.

The near-term plan runs Playwright from a scratch directory through `npx`, with nothing added
to `ui/package.json`. This document argues the other option: adopt it properly.

## Proposed shape

```text
ui/
  e2e/
    fixtures/           # deterministic API responses
    pages/              # page objects: SnapshotsPage, ChainsPage, ...
    specs/
      snapshots.spec.ts
      chains.spec.ts
    playwright.config.ts
```

- `@playwright/test` as a `devDependency` of `ui`.
- `npm -w @netcracker/qip-ui run e2e` and `... run e2e:ui` (headed, for debugging).
- Chromium only for the first cut. Firefox and WebKit add runtime and catch little for an
  internal tool with a known browser target.

### Mock the backend, do not stand up the stack

This is the decision that determines whether the whole thing is affordable, so it belongs
near the top.

A full-stack run needs PostgreSQL, Consul, OpenSearch, runtime-catalog, engine, and
sessions-management, plus the Maven build that produces their jars. On a pull-request gate
that is minutes of setup for a test that checks a border color.

Most UI defects, including both halves of #679, need only a deterministic page. Intercept the
network at the Playwright level and serve fixtures:

```ts
await page.route("**/v1/catalog/chains/*/snapshots", (route) =>
  route.fulfill({ json: snapshotsFixture }),
);
```

The tests then run against `vite preview` on a built bundle, with no Docker at all. Setup
drops to an `npm ci` and a browser download.

Keep a small full-stack suite if the team wants one, but run it on a schedule or on demand,
never on every pull request. Route the fixtures through the same types the app uses
(`ui/src/api/apiTypes.ts`), so a renamed DTO field breaks `check-types` instead of silently
producing a test that passes against a shape the backend no longer sends.

### Seeding, if a full-stack suite happens

Creating a chain and compiling a snapshot through the runtime-catalog API is already
documented in the `runtime-catalog-api-testing` skill:

```bash
ID=$(curl -s -X POST http://localhost:8091/v1/chains -H 'Content-Type: application/json' \
      -d '{"name":"e2e-chain","labels":[]}' | jq -r .id)
curl -s -X POST http://localhost:8091/v1/catalog/chains/$ID/snapshots
```

Prefix every seeded entity with a unique run token and delete it afterward. Do not reuse the
recipes by copying them here; call the skill's contract and let one place stay authoritative.

## CI

A new job in `.github/workflows/ui-build.yaml`, after `npm-build`:

```yaml
  e2e:
    needs: npm-build
    runs-on: ubuntu-latest
    steps:
      # checkout, setup-node 22.x, npm ci --legacy-peer-deps, build schemas
      - run: npx playwright install --with-deps chromium
        working-directory: ui
      - run: npm run build
        working-directory: ui
      - run: npm run e2e
        working-directory: ui
      - uses: actions/upload-artifact@...
        if: failure()
        with:
          name: playwright-report
          path: ui/playwright-report
```

Two details worth settling before anyone writes YAML:

- **Sonar is unaffected.** `ui-build.yaml` passes `-Dsonar.sources=src` and
  `-Dsonar.tests=src`, and the Jest suites already live outside `src` in `ui/tests/`. A new
  `ui/e2e/` directory falls outside both, so it will not enter the quality gate and will not
  dilute the coverage number that `qualitygate.wait=true` blocks on.
- **Jest will collect the e2e specs unless told not to.** `ui/jest.config.ts` sets
  `testMatch: ["**/__tests__/**/*.?([mc])[jt]s?(x)", "**/?(*.)+(spec|test).?([mc])[jt]s?(x)"]`
  against the workspace root, so `ui/e2e/specs/snapshots.spec.ts` matches. Add `e2e/` to
  `testPathIgnorePatterns`, or `npm test` starts trying to run Playwright specs in `jsdom`.

## What this costs

- **Browser download.** 115 MB of Chromium per environment, measured. Cached in CI and paid
  again by anyone who runs `npm ci` in a fresh container. Not a one-time cost locally either:
  the browser revision Playwright pins moves with the package, so a version bump re-downloads.
- **Flakiness.** Browser tests fail for reasons unrelated to the change under review. Ant
  Design animations, `ResizeObserver` timing, and Monaco initialization are the usual
  suspects, and `ui/jest.config.ts` already carries comments about the last two biting the
  Jest suite. Budget for a triage habit, not just for writing the tests.
- **Maintenance.** Page objects drift when the UI is refactored. A suite nobody repairs gets
  skipped, then deleted, and the effort is lost.
- **Review latency.** Every minute added to the pull-request gate is paid by every pull
  request, including the ones that touch only Java.

The flakiness cost is the one that decides whether this survives. A required check that fails
20% of the time on unrelated pull requests gets marked non-required within a month. Start the
job as non-blocking, measure the false-failure rate for a few weeks, and promote it to
required only if the number justifies it.

## A first cut worth shipping

Five to eight specs, chosen because they break often and cost the most when they break:

1. Snapshots table: rename a snapshot inline, confirm the field returns to its read state.
2. Snapshots table: label sizing is identical whether the label editor is open or closed.
   (This is #679, and it pins the fix.)
3. Chains list: filter, sort, and column resize survive a reload.
4. Table with horizontal scroll: the header stays aligned with the body under `scroll.y`,
   the case `tableScroll.ts` exists to handle.
5. Chain graph: the editor opens and a node can be selected.
6. Theme: light and dark render the same page without unreadable contrast.

Visual regression through `toHaveScreenshot()` is tempting and should wait. Golden images
need a baseline per platform, and a font difference between a developer's machine and the CI
runner turns every pull request red. Assert on computed styles and on the DOM until the suite
has earned trust.

## Alternatives

**Keep the scratch approach.** The agent writes a throwaway Playwright script per issue,
outside git. Zero cost to the team, and evidence still lands in the pull request. What it
loses is accumulation: the check that proves #679 is fixed disappears the moment the pull
request merges, so nothing stops a later refactor from bringing it back.

**Push harder on `@testing-library/react`.** Already a dependency, already used. Worth doing
for logic, but it shares Jest's blind spot, since it also runs in `jsdom`. It cannot see the
two defects in #679.

**Cypress.** Comparable capability. Playwright wins on parallelism, on multi-tab and iframe
support that the VS Code webview would eventually need, and on a first-party
`@playwright/test` runner that needs no plugin stack.

## Questions for the team

1. Do we want an accumulating browser suite at all, or is per-issue evidence enough?
2. Mocked backend, full stack, or both suites at different cadences?
3. Blocking pull-request gate, non-blocking gate, or nightly?
4. Who owns a flaky spec: the author of the failing pull request, or a rotation?
5. Does the VS Code webview get its own suite, given it loads the qip-ui library bundle
   rather than the app build?
