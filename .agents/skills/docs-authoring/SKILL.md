---
name: docs-authoring
description: >
  Create or maintain product documentation under help/docs/, including assets
  and structure, from an issue, PR, commit, local changes, or a sufficiently
  specific request.
---

# Product documentation authoring

Product documentation lives in `help/docs/`, organized by UI area and product feature. The UI and VS Code extension consume it; see
`ui/docs/DOCUMENTATION_INTEGRATION.md` for the fetch-and-index mechanics.

Before drafting or revising documentation, load `english-developer-style` and
follow its wording guidance. This skill governs content selection, structure,
placement, and repository conventions.

## Select the change

Resolve the task from available context before asking for missing information.
Read a supplied issue and its relevant implementation or PR. For an issue,
use `gh issue view <N> --json title,body,comments` (plain output fails on
Projects classic). A clear correction request needs no issue or branch diff.

Choose the comparison base from the PR, target branch, or request. Read the
relevant committed diff and staged, unstaged, and newly created content;
`git status --short` alone is insufficient. Preserve unrelated working-tree
edits. Use the issue for intent and the selected revision's implementation,
configuration, and tests for behavior, including relevant code outside the
diff. Report material discrepancies; do not present unimplemented requirements
as available.

Decide what the reader needs to do or understand differently, then map that
need to pages and read the affected pages in full. Leave documentation unchanged
when the existing text, icon assets, and navigation already cover the change,
and explain why. Add a justified topic within an established section when the
task authorizes it. Ask only when missing information or a structural choice
materially affects the result and cannot be resolved from context.

## Choose the content

Make the smallest documentation update that fully meets the reader's need.
Use plain, direct language and preserve necessary conditions and limitations.
Update the existing owning page where possible, and link to existing
explanations instead of duplicating them. Add sections, tables, examples, or
glossary entries only when they improve understanding. Keep unrelated rewrites,
reorganizations, and new tooling outside the task. Scale verification to the
change.

Choose structure by purpose: procedures need prerequisites, imperative actions,
and an observable result; references describe behavior and relevant options;
troubleshooting connects symptoms to checks and corrective action. Use
descriptive sentences for references. Match established terminology and
navigation conventions. Remove stale or duplicate material in the affected
passage; leave unrelated pages and historical formatting outside the update.

Verify the relevant client, engine variant, permissions, and configuration when
they affect the reader's actions. Establish VS Code availability from the
extension's actual product flow and configuration. For a new operation confirmed
unavailable there, place this exact marker beside that operation, without
implying that other operations on the page are unavailable:

```markdown
**`⛔ Not available via the VS Code extension`**
```

## Documentation layout

`help/docs/` holds nine top-level sections, each a `NN__Title` folder. Every
topic is its own `N__Title_Name` folder with a `readme.md` and an optional
`img/` folder. Every page, a landing page included, is a `readme.md`.

- `00__Overview/` — orientation and concepts: Getting Started, Apache Camel
  context, general functions, architecture, glossary.
- `01__Chains/` — the chain editor and the full element library under
  `1__Graph/1__Elements_Library/` (Routing, Files, Composite Triggers,
  Services, Transformation, Triggers, Senders, Grouping), plus Snapshots,
  Deployments, Sessions, Logging, Masking, Properties, Testing.
- `02__Services/` — external, inner-cloud, implemented, context, and MCP
  services.
- `03__Admin_Tools/` — domains, variables, audit, import instructions,
  sessions, access control, design templates, live exchanges, testing.
- `04__Dev_Tools/` — MaaS, diagnostics.
- `05__How_To/` — one page per recurring task, each written as ordered steps
  with a stated outcome.
- `06__Observability/` — what the platform records about its own work:
  platform logging, metrics and session monitoring.
- `07__Troubleshooting/` — symptom, likely cause, and what to check, plus the
  error reference.
- `08__Features/` — platform mechanisms and the parameters that control them:
  system properties, token processing, retention settings, database
  multitenancy.

Each section has a landing page, `<section>/readme.md`, such as
`01__Chains/readme.md`. Most of them list their pages as
`- [Title](N__Folder/readme.md) - one-line summary`; when you add, move, or
remove a page, update that list. Search repository-wide with `rg` for references
to moved or removed paths, and check affected heading links when headings change.

`00__Overview/` holds orientation material, not a screen. Sections 01 through
04 mirror the product's UI areas, so a feature that has a screen belongs there.
The last four are organized by reader intent instead, and the genre decides
which one a new page goes into:

- A task a user performs repeatedly, with a beginning and an end, goes to
  `05__How_To/` — not into the reference page for the screen it uses. Link
  from that reference page to the how-to.
- A record the platform writes about its own work — a log type, a metric —
  goes to `06__Observability/`.
- A symptom a reader is diagnosing goes to `07__Troubleshooting/`, as a row
  linking to the page that holds the detail.
- A mechanism the platform performs with no screen to drive it, and the
  parameters that tune it, goes to `08__Features/`. Keep it distinct from
  `03__Admin_Tools/`, which documents a tab inside the product.

Two pages are indices over the whole tree: `00__Overview/4__Glossary/readme.md`
has one `###` entry per term, each ending in a link to the page that owns it,
and `07__Troubleshooting/readme.md` has the symptom table. Add entries for
reusable product concepts and actionable failures a reader can encounter.
Link to the owning explanation instead of duplicating it.

## Code area to doc mapping

Docs are feature-organized, so a code change usually maps to a UI area rather
than to a one-to-one file. Use these mappings as search starting points:

- `runtime-catalog/**` (chains, elements, deployments, snapshots,
  specifications, systems, variables) → `help/docs/01__Chains/`
  (chains, graph, `2__Snapshots`, `3__Deployments`, `7__Properties`),
  `help/docs/02__Services/`, and the `help/docs/03__Admin_Tools/` tabs backed
  by the catalog: `2__Variables`, `3__Audit`, `4__Import_Instructions`,
  `7__Design_Templates`, `8__Live_Exchanges`.
- `engine/**` and `micro-engine/**` (Apache Camel execution engines) →
  each runnable element page under `01__Chains/1__Graph/1__Elements_Library/`,
  plus the cross-cutting pages: `06__Observability/` (logs, metrics),
  `07__Troubleshooting/1__Error_Reference/` (error codes and headers), and
  `08__Features/` (system properties, token processing, retention,
  multitenancy).
- `sessions-management/**` → `help/docs/01__Chains/4__Sessions/`,
  `help/docs/03__Admin_Tools/5__Sessions/`, and the session section of
  `08__Features/3__Retention_Settings/`.
- `testing-service/**` → `help/docs/01__Chains/8__Testing/` (one chain) and
  `help/docs/03__Admin_Tools/9__Testing/` (all chains, test runs).
- `schemas/**` → the actual subject: chain and graph pages for chain schemas,
  `02__Services/` for service schemas, and the matching element page for
  element schemas, such as `6__Triggers/1__HTTP_Trigger/readme.md` for
  `http-trigger`.
- `integration-build-pipeline/**` → the affected element, import, or feature
  page when compiler templates or import migrations change documented behavior.
- `ui/**` and `vscode-extension/**` → the affected UI area. Both consume the
  documentation tree; this does not establish feature availability.
  Integration mechanics belong in `ui/docs/DOCUMENTATION_INTEGRATION.md`.
- `infrastructure/**` → the affected product page when deployment configuration
  controls documented behavior. Keep deployment procedures in infrastructure
  documentation, such as `infrastructure/docker/README.md`.

## Formatting and assets

- Use one H1 per page, followed by H2 sections as needed. Pages start at the
  title without YAML frontmatter. Keep version history and changelogs out.
- Name UI elements in bold.
- **Parameter tables** use the columns `Parameter | Mandatory | Data Type |
  Description | Sample`, where Mandatory is `M`, `O`, or `C`.
- Use relative links between pages, ending in `/readme.md`. Keep images in
  the page's local `img/` folder.

Apply these callout forms to new or revised callouts. A single statement uses
only its corresponding emoji, even when it wraps across lines. Two or more
distinct points use the exact label `Notes` or `Warning` and a list:

```markdown
> ℹ️ <single informational statement>

> ⚠️ <single warning statement>

> ℹ️ **Notes:**
>
> - <first informational point>
> - <second informational point>

> ⚠️ **Warning:**
>
> - <first warning point>
> - <second warning point>
```

For a breaking change, use the same callout rule and include a short migration
note when a migration path exists. Do not reformat unrelated callouts.

For each UI icon added or updated by the change, trace the actual affected
control, starting with `ui/src/icons/IconDefinitions.tsx` and `ui/src/assets/`,
then its imports or provider as needed. Copy the matching asset into the owning
page's `img/` directory, or update its existing copy. Reference it beside the
control or operation with meaningful alt text, such as
`![Refresh](img/refresh.svg)`. For component or library icons, obtain a faithful
static asset from that implementation. Report an unresolved source instead of
guessing. Check the image's appearance and link in the documentation renderer.

## Structure and navigation

Preserve numbered folders, `readme.md` pages, and relative links. Navigation
comes from `ui/scripts/build-doc-index.mjs`, which sorts paths lexicographically:
`10__Split_Async` precedes `1__`, rather than following `9__`. Text after `__`
becomes the display title, with underscores as spaces; there is no separate nav
file. Element folder names are functional identifiers:
`ui/scripts/element-documentation-mapping.mjs` derives Help destinations from
them and applies aliases. For an element-page move or rename, verify the
element's Help destination as well as links and navigation. Keep broad renaming
or ordering cleanup outside unrelated updates.

After additions, removals, moves, or renames anywhere beneath `help/docs/`,
including nested topics, elements, pages, and image directories, compare the
resulting tree with this skill's layout, mappings, naming, path examples,
asset guidance, and navigation rules. Record that the skill remains accurate
or name the maintained rules needing an update. Make those edits when authorized
by the task; otherwise report the follow-up. A structural change can require
no skill edit.

## Verify and hand off

- Verify changed claims, labels, routes, samples, and capability restrictions
  against the selected implementation evidence.
- Check changed Markdown with `.github/linters/.markdownlint.json`, and check
  affected links and image paths. Reuse the link-checker configuration in
  `.github/workflows/link-checker.yaml` where applicable.
- For new or moved pages, image or navigation changes, or formatting changes to
  tables or callouts, run `npm -w @netcracker/qip-ui run fetch-docs` and inspect the
  affected result. `help/docs/` is the source; `ui/public/doc/` is generated.
  For a running-stack browser check, use `http://localhost:8080` through nginx.
- Do not rely on UI or extension build workflows for a docs-only PR: their
  path filters omit `help/docs/`. A prose correction needs no full stack build.

Leave edits unstaged by default. Respect authorization already given in the
conversation for later commit or PR work. End with a short summary of changed
pages and assets, why they changed, checks performed, and unresolved factual
or structural-guidance questions. A no-change result includes its reason.
Keep this handoff out of product documentation.
