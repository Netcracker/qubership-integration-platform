/**
 * Serves VS Code for the web on 3917, with this checkout's extension, before the `vscode` project
 * opens a page.
 *
 * Collected by `testMatch: /.*\.setup\.ts$/` on the `vscode-server` project, for the reason the
 * `seed` project gives: a setup project that collects zero tests satisfies its dependents.
 */
import { test } from "@playwright/test";
import { ensureVsCodeServer, VSCODE_PORT } from "../../support/vscode-server.js";

test("the VS Code workbench answers on port 3917", { tag: ["@infra"] }, async () => {
  // A cold build of the UI library takes minutes, and a cold cache downloads the pinned build.
  test.setTimeout(600_000);
  const { pid, built } = await ensureVsCodeServer();
  console.log(
    `[vscode-server] vscode-test-web started on ${VSCODE_PORT} (pid ${pid})` +
      (built.length ? ` after building ${built.join(" and ")}` : ""),
  );
});
