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
for the fetch-and-index mechanics). Docs are organized **by product feature
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

`help/docs/` holds five top-level sections, each a `NN__Title` folder. Every
topic is its own `N__Title_Name` folder with one snake_case `.md` file and an
optional `img/` folder.

- `00__Overview/` — platform concepts (token processing, Apache Camel
  context, chain configuration, access control).
- `01__Chains/` — the chain editor and the full element library under
  `1__Graph/1__Elements_Library/` (Routing, Files, Composite Triggers,
  Services, Transformation, Triggers, Senders, Grouping), plus Snapshots,
  Deployments, Sessions, Logging, Masking, Properties.
- `02__Services/` — external, inner-cloud, implemented, context, and MCP
  services.
- `03__Admin_Tools/` — domains, variables, audit, import, sessions, access
  control, design templates, live exchanges.
- `04__Dev_Tools/` — MaaS, diagnostics.

Each section has a landing page (`overview.md`, `chains.md`, `graph.md`,
`services.md`, `admin_tools.md`, `dev_tools.md`).

## Code area to doc mapping

Docs are feature-organized, so a code change usually maps to a UI area rather
than to a one-to-one file. Use this to find the affected page(s):

- `runtime-catalog/**` (chains, elements, deployments, snapshots,
  specifications, systems, variables) → `help/docs/01__Chains/`
  (chains, graph, `2__Snapshots`, `3__Deployments`, `7__Properties`),
  `help/docs/02__Services/`, and `help/docs/03__Admin_Tools/2__Variables/`.
- `engine/**` and `micro-engine/**` (Apache Camel execution engines) →
  the runtime behavior described in `help/docs/00__Overview/` and in each
  runnable element page under
  `01__Chains/1__Graph/1__Elements_Library/`.
- `sessions-management/**` → `help/docs/01__Chains/4__Sessions/` and
  `help/docs/03__Admin_Tools/5__Sessions/`.
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
3. Map each changed path to its doc page(s) using the table above; search
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
6. Verify every parameter, label, route, and sample against the diff and the
   issue.
7. End with a plain-text summary of which files you changed and why. Do not
   write that summary into any file.
