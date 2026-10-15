# The browser layer

`specs/ui/` drives Chromium through nginx on 8080, against the production bundle `ui-server`
serves on 4200. A case covers what only a browser shows: layout, computed styles, focus, a screen
redrawn with no reload, a screen that throws. A fact the API can assert belongs in `specs/api/`,
and a fact jsdom renders the same way belongs in Jest under `ui/tests/`. Data is created through
the API and read in the browser; a case whose subject is the creation flow says so in its spec
header. No `toHaveScreenshot()`: a golden image needs a baseline per platform.

Every spec that opens a page imports `test` and `expect` from `support/page-guard.ts`
(`spa-fallthrough.spec.ts` opens none: it sits here only because it needs the bundle on 4200). Its
automatic fixture fails the case on an uncaught exception, a `console.error`, or a 4xx or 5xx
answer to a request the page sent under `/api/`, and names the route. A message the product cannot
stop logging gets an allowlist entry there with its reason, added together with the first case that
meets it. A guard failure clears `test.fail()`, so a pinned case fails for real on a problem that is
not its defect. A page object under `pages/` holds locators and the few interactions worth naming,
and no assertion.

**Selectors**, in this order:

1. A role and an accessible name: `getByRole("dialog", { name: "Create service" })`. A list is
   global, so a row is found by what the case created:
   `getByRole("row").filter({ has: page.getByRole("link", { name, exact: true }) })`.
2. A `data-testid` for a control with no accessible name, usually an antd icon-only button. The id
   goes on each button, never on the toolbar around it, since a container id still leaves the
   buttons inside to positional selection. It is named `<screen>-<action>` (`chains-compare`,
   `snapshots-create`, `test-case-save`). A button a shared hook renders gets a
   `<span data-testid>` at the call site (`chains-column-settings`), and the case selects the
   button inside it. A named button gets no id. Where the missing name is an accessibility gap,
   restore the attribute antd's own component carries instead: `TreeExpandIcon` got `aria-label`
   and `aria-expanded` rather than an id. Adding an id to `ui/src` is in scope. It is an attribute
   on the button, or the `<span>` around a hook-rendered one, and changes no behavior; a Jest mock
   that reported an id is updated to report the shipped one.
3. `getByTitle` where antd renders the name only as a `title`: a Select's visible option, whose
   listbox options stay hidden, and a Segmented option.
4. Never a CSS class; antd renames them between minor versions. An id the library itself renders
   is fine: `@xyflow/react` gives each node `data-testid="rf__node-<element id>"` and each edge the
   name `Edge from <id> to <id>`.

Three more selector rules, each met by a case under `specs/ui/`:

- A multiple Select is closed with Escape. Tab picks the active option again, which clears it.
- After a save, antd can keep the hidden loading icon in a button, whose name then reads
  `loading Apply`, so the locator matches `/Apply$/` (`pages/ChainTabsPage.ts`).
- An entity a UI case creates carries a `callToken` beside the run token, in its name or its route,
  so `--repeat-each` runs on one run token do not collide on it.

A Monaco editor is located by the id its mount site passes through `@monaco-editor/react`'s
`wrapperProps`, always ending in `-editor` (`script-editor`, `snapshot-xml-editor`,
`session-body-before-editor`). That id is attached at mount, about 1.3 s before the editor can be
used, so wait on `.monaco-editor[data-uri]` inside it, which appears once the model is attached
(`chain-graph.spec.ts` reads a script this way). Counting `.monaco-editor` nodes cannot tell editors
apart: one editor renders two.

**Waiting.** No fixed sleep. Wait with a web-first assertion (`toBeVisible`, `toBeHidden`,
`toHaveURL`, `toHaveCount`), with `expect.poll` for a value read through the API or measured in
the page, and with `expect(...).toPass()` for a block that throws until the page settles.

- Where the defect would be a missing redraw, assert the change with no reload: the tag clears in
  place, the row leaves, the deployment badge moves on the event poll. Reload only when
  persistence is the subject.
- After closing a dialog or a menu, assert it `toBeHidden()` before the next step. antd motion is
  not suppressed: measured, a dropdown settles in about 130 ms. A spec that shortens it with
  injected CSS sets `animation-duration`, never `animation: none`: rc-motion waits for
  `animationend`, and with no animation the element never becomes visible.
- A control shown only while its row is hovered is clicked after `row.hover()`
  (`chains-row-actions`, `live-exchanges-terminate`).
- Read a computed style only after the state behind it settles. The theme cases wait for
  `data-theme`, for the `theme-switching` class to go, and for the last `theme-variables-updated`
  event, which an init script records, to name the mode. The contrast read also throws while a
  `CSSTransition` runs on the element or an ancestor, and `toPass` retries it: a color read
  mid-transition returns the old value.
- An absence that holds while the screen is still loading is read only after the answer that
  decides it: the testing section is hidden while `/testing-service/mode` is in flight, so the case
  awaits `page.waitForResponse` on it before `toHaveCount(0)`.
- A screen that looks up a record the stack writes asynchronously opens only after the case has
  waited for that record through the API. Test Case Runs asks sessions-management once per
  session, and a lookup before the session is written answers 404, which the guard fails.
- `waitForLoadState("networkidle")` belongs to `routes-smoke.spec.ts` alone, where every load
  request has to answer before the guard has seen its status.
- `page.route` rewrites an answer to reach a state the stack cannot be switched to per case (the
  testing service's `/mode`); `page.waitForResponse` and `page.on("request")` observe what the page
  sent.

The `ui` project pins the viewport at 1600x900. The canvas renders only the nodes inside the
viewport once a real interaction has happened, so a graph case asserts over a chain that fits it
(`http-echo`) or over the node it just touched. A case that needs another size calls
`page.setViewportSize` itself. Dragging nodes and the automatic layout stay uncovered until a
defect asks for them; adding an element from the library is a `dragTo`, because the library has
no click-to-add. The project has no `expect` timeout of its own and no warm-up navigation: against
the bundle, a warm `/chains` shows its first row in 1.1–6.2 s.

A mutation check on `ui/src` (rule 17 in `SKILL.md`) costs a `vite build` each way, about a minute. Make it in the
working tree with a backup of the file, revert it before the commit, and record in the pull request
description what was changed and which assertion went red.
