# The browser layer

`specs/ui/` drives Chromium through nginx on 8080, against the production bundle `ui-server`
serves on 4200. A case covers what only a browser shows: layout, computed styles, focus, a screen
redrawn with no reload, a screen that throws. A fact the API can assert belongs in `specs/api/`,
and a fact jsdom renders the same way belongs in Jest under `ui/tests/`. Data is created through
the API and read in the browser; a case whose subject is the creation flow says so in its spec
header. No `toHaveScreenshot()`: a golden image needs a baseline per platform.

Every spec that opens a page imports `test` and `expect` from `support/page-guard.ts`
(`spa-fallthrough.spec.ts` opens none: it sits here only because it needs the bundle on 4200). Its
automatic fixture fails the case on an uncaught exception, a `console.error`, or a 4xx or 5xx answer
to a request the page sent under `/api/`, and names the route. A message the product cannot stop
logging gets an allowlist entry in `support/page-problems.ts` with its reason, added together with
the first case that meets it. A guard failure clears `test.fail()`, so a pinned case fails for real
on a problem that is not its defect. A page object under `pages/` holds locators and the few
interactions worth naming, and no assertion.

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
- An option in a freshly opened antd list is picked through `pickOption` in
  `support/pick-option.ts`. The list opens scaled up from 0.8, so a click during the animation can
  press on one option, release over another, and pick nothing. `pickOption` repeats the click, an
  exception to rule 9 in `SKILL.md`. A pick in a single select closes the list, so a click that
  landed is never repeated. A multiple select stays open, so `pickOption` relies on the caller's
  `picked` locator to see the pick land, and repeats the click only while the list reports no
  option of that name selected, because a second click would clear the pick.
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
no click-to-add. A connection is a `dragTo` too, from the node's `data-handlepos="right"` handle to
the other node's `data-handlepos="left"` one (`ChainGraphPage.connect`); a container hides its
handles, so connect plain elements. Save a downloaded export under `download.suggestedFilename()`
before uploading it again: the catalog picks the parser by the file's extension, and the path
Playwright downloads to has none. The project has no `expect` timeout of its own and no warm-up
navigation: against
the bundle, a warm `/chains` shows its first row in 1.1–6.2 s.

A mutation check on `ui/src` (rule 17 in `SKILL.md`) costs a `vite build` each way, about a minute. Make it in the
working tree with a backup of the file, revert it before the commit, and record in the spec header
what was changed and which assertion went red, as rule 17 says.

## The VS Code webview

`specs/vscode/` drives the extension's editors inside VS Code for the web, which `vscode-server`
serves on 3917 with the extension of this checkout and the workspace `fixtures/vscode/workspace/`.
A case covers what the editor shows and what its webview sends the extension: a write the extension
makes to a file, a tab a message opens, a notification. A behavior of the extension host alone,
such as a command or a read through `workspace.fs`, belongs in the extension's integration suite.
The selector and waiting rules above hold inside the webview too.

A case starts with `await vscode.open()`, where the `vscode` fixture is a `VsCodePage` over the
case's `page`. Each case runs in Playwright's per-test `context`, never a worker-scoped one: VS Code
for the web restores open editors and `workspaceState` from IndexedDB, so a shared context would
open one case on the editors the case before it left.

**Every spec imports `test` and `expect` from `support/vscode-guard.ts`, never from
`page-guard.ts`,** whose fixtures need the stack. Its automatic guard fails the case on an uncaught
exception or a `console.error` in any frame, the extension host's included. It cannot tell the
frames apart, because an error from an inline or evaluated script carries no location URL, so
`ALLOWED` holds the one error every load reports, with the run that showed it in `shownBy`. A case
that meets a product error declares it with `expectConsoleError`, naming the issue in a comment:

- a string matches the message exactly, and a `RegExp` matches a pattern;
- `withAnyStack(message)` builds the pattern of a message followed by its stack, whose line numbers
  in `extension.js` change with every build of the extension;
- a declaration that never matches fails the case, so declare an error only where it arrives every
  time. An error that arrives only sometimes, such as Monaco's `Event` (#1223), is kept out of the
  case where the case can avoid it: the chain editor case opens a form with no script editor;
- `allowConsoleError` lets an error through without requiring it. A pinned case uses it for an
  error its fix may stop raising, since a declared error that stops arriving fails the case for
  real, and for an error it cannot avoid that arrives only sometimes.

A guard failure clears `test.fail()` here as well. The `offsiteAnswers` fixture answers every
request to a host other than `localhost` and `*.localhost` with an empty 200: the workbench asks the
internet for its experiment assignments and its chat and Marketplace manifests, and a request that
fails logs a `console.error`.

`VsCodePage` presses the key of Quick Open or the command palette again while no quick input shows,
up to 20 times, an exception to rule 9 in `SKILL.md`. The workbench registers its keybindings some
time after it renders and shows no state that marks it, and a key pressed before that opens
nothing. A press that opened the input is not repeated.

**Frames.** `VsCodePage.webview` is the content frame of the visible webview,
`iframe.webview.ready:visible` and then `#active-frame`, since a webview a case left stays in the
DOM hidden. A page object that serves both projects takes `Page | FrameLocator`, as
`ChainGraphPage`, `TableView`, and `pickOption` do. The workbench itself gives Quick Open and its
editors no role, so `VsCodePage` locates them by their classes, which stay the same within the
pinned build.

**Opening a file.** `VsCodePage.openFile` types the file's path relative to the workspace into Quick
Open, which finds a file by that path only, and found none at the workspace root: the export case
opens those from the explorer. The project keeps the Windows user agent of `Desktop Chrome`, so the
keybindings are Ctrl on every host, and Quick Open names a file's folder with backslashes, which
`VsCodePage` matches. `compareActiveFileWith` opens the chain diff the same way.

**Reading a file back.** The extension writes the file, and the case reads it through the workbench:
`readAsText` reopens the editor as text, selects and copies the text, and reads the clipboard in
the top frame, turning `\r\n` into `\n`. The in-memory file system fires its change event after a
debounce, and the text editor can still show an earlier write, so pass `readAsText` the predicate
of the state the case asserts and let it poll. The workbench remembers the last editor of a file,
so after `readAsText` it opens that file as text, and `reopenWith(displayName)` returns to the
custom editor. Parse a chain with `parseChain` from `support/vscode-workspace.ts`, and compare a
whole file with a golden under `fixtures/vscode/goldens/`, after `normalizeIds` from
`support/vscode-golden.ts` has numbered the ids the extension generated.

**Messages.** The `messages` fixture records every request a webview sends and the answer the
extension posts back. Wait on it rather than on the screen where the screen cannot show the state:
`messages.answered()` for the end of a save whose button stays enabled (#1226), and an empty
`messages.unanswered()` before opening an editor that a redraw would reset. A type the extension
never answers counts only through `sentMessage`, after the case asserts the tab it opened.

**Running and breaking a case.** Run the project with its dependencies, never `--no-deps`: one
worker, one run at a time on port 3917. A mutation edits the extension under
`vscode-extension/src/web/response/`, or the UI under `ui/src/`, and runs the spec file with its
dependencies. `vscode-server` finds the source newer than its output and rebuilds: a UI edit runs
`build:lib:bundled` and `build:lib:types` into `ui/dist-lib` and then `compile-web`, and an extension
edit runs `compile-web` alone, about 17 s. The next page loads the new bundle. Undo the edit itself
and run the file again to see it green. A filed defect a case meets is pinned with `test.fail()`
and its issue number, or worked around with a comment naming it, as `ChainGraphPage.openElement`
waits for the focus move of #1236 before a case touches a field.
