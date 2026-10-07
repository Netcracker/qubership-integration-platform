---
name: docs-authoring
description: >
  Update the product documentation under help/docs/ for a feature. Use after
  implementing a feature, or when asked to "update the docs" or "document
  issue #N". Maps changed code to the right doc page, follows the help/docs
  structure and style, and leaves edits unstaged for human review.
---

# Feature documentation authoring

Product documentation lives in-repo at `help/docs/`. The UI and the VS Code
extension consume it at runtime (see `ui/docs/DOCUMENTATION_INTEGRATION.md`
for the fetch-and-index mechanics). Docs are organized **by the product feature
and UI area, not by backend module**, so start from what the user sees, then
find the code that changed behind it.

## Working rules

- **Read the linked issue in full first.** The issue description is the
  source of truth for *why* a feature exists and how it should behave —
  edge cases the diff alone won't show. If an issue number is given, run
  `gh issue view <N>` before writing anything.
- **Never commit, push, or open a pull request unless the prompt asks for
  it.** Leave doc edits as unstaged working-tree changes for a human to
  review and commit.
- **Don't describe planned behavior as shipped.** Every UI label, parameter,
  and route you document must match the actual code diff and the issue.
- **Ask before creating a new page or section folder.** If a change doesn't
  map to an existing page, search `help/docs/` for the closest match first;
  don't invent a new subtree silently.

## Documentation layout

`help/docs/` holds nine top-level sections, each a `NN__Title` folder. Every
topic is its own `N__Title_Name` folder with one snake_case `.md` file and an
optional `img/` folder.

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

Each section has a landing page (`overview.md`, `chains.md`, `services.md`,
`admin_tools.md`, `dev_tools.md`, `how_to.md`, `observability.md`,
`troubleshooting.md`, `features.md`). Most of them list their pages as
`- [Title](N__Folder/page.md) - one-line summary`; when you add, move, or
remove a page, update that list. Moving a page also means fixing every
relative link to its old path: `grep -rn "<old folder>" help/docs`.

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

Two pages are indices over the whole tree: `00__Overview/4__Glossary/glossary.md`
has one `###` entry per term, each ending in a link to the page that owns it,
and `07__Troubleshooting/troubleshooting.md` has the symptom table. A new term
gets a glossary entry; a new failure mode a reader can hit gets a symptom row.

## Code area to doc mapping

Docs are feature-organized, so a code change usually maps to a UI area rather
than to a one-to-one file. Use this to find the affected page(s):

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
- `schemas/**` (JSON Schema for chains, services, elements) → the matching
  element page under `01__Chains/1__Graph/1__Elements_Library/`. File names
  map to element types: `http_trigger.md` documents the `http-trigger`
  element.
- `ui/**` and `vscode-extension/**` → both render the whole `help/docs` tree;
  UI feature areas map one-to-one to the numbered sections. Integration
  mechanics belong in `ui/docs/DOCUMENTATION_INTEGRATION.md`, not in
  `help/docs/`.
- `infrastructure/**` → no `help/docs/` coverage. Documented only in
  `infrastructure/README.md` and the ADRs under `infrastructure/docs/adr/`.

## Documentation style

Match the existing pages — verify against a neighbor before you write.

- **One H1 per page** (`# Title`), then `## Section` headings. Pages often
  place a `---` horizontal rule directly under an H2 as a divider.
- **Topic pages share one H2 skeleton**: `Description`, `Process
  Initialization`, `User Interface`, `Data Storage`, `Configuration`, and
  `Constraints` where they apply. Keep the order and leave out a section that
  has nothing to say rather than writing a placeholder. Landing pages use
  `Description` and a link list instead (`Topics`, `Tasks`).
- **No YAML front matter.** Pages start straight at the `# Title`.
- **No changelog, "Since", or version-history sections.** Don't add one.
- **Third-person, product-reference voice** ("The HTTP Trigger exposes the
  chain over HTTP"). Name UI elements in bold.
- **Parameter tables** use the columns `Parameter | Mandatory | Data Type |
  Description | Sample`, where Mandatory is `M`, `O`, or `C`.
- **Callouts** use the `> ℹ️ **Note:**` form already in the pages.
- **Cross-links are relative** between doc pages
  (`../../1__Routing/9__Try-Catch-Finally/try-catch-finally.md`). Images live
  in the page's `img/` folder.
- **Navigation is derived from folder order**, not a nav file. A new
  `N__Title_Name` folder slots in by its numeric prefix; underscores in the
  name become spaces in the display title. There is no `mkdocs.yml` or
  `SUMMARY.md` to update.

## Task flow

1. Read the linked issue in full (`gh issue view <N>`).
2. Review the change (`git status --short`, `git diff origin/main...HEAD`)
   to see which UI areas and elements the code touched.
3. Map each changed path to its doc page(s) using the mapping above; search
   `help/docs/` for the closest match if a path isn't listed.
4. Read each affected page in full before editing.
5. Write the update:
   - New feature → add a section or a `N__Title_Name` page folder following
     the neighbor's structure, including at least one parameter table or
     runnable example.
   - Changed behavior → update the description, tables, and samples; remove
     stale text about the old behavior.
   - Breaking change → add a `> ℹ️ **Note:**` callout, and a short migration
     note if a migration path exists.
   - New or moved page → update the landing page's link list, the glossary,
     and the troubleshooting table where the change reaches them.
6. Verify every parameter, label, route, and sample against the diff and the
   issue.
7. End with a plain-text summary of which files you changed and why. Do not
   write that summary into any file.
