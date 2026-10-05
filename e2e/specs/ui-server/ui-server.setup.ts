/**
 * Serves the UI bundle on 4200 before the `ui` project opens a page.
 *
 * Collected by `testMatch: /.*\.setup\.ts$/` on the `ui-server` project, for the reason the `seed`
 * project gives: a setup project that collects zero tests satisfies its dependents.
 */
import { test } from "@playwright/test";
import { ensureUiServer, UI_PORT } from "../../support/ui-server.js";

test("the UI bundle answers on port 4200", { tag: ["@ui", "@tier1"] }, async () => {
  // A cold build of the bundle, `fetch-docs` included, takes minutes.
  test.setTimeout(600_000);
  const { pid, built } = await ensureUiServer();
  console.log(
    pid === null
      ? `[ui-server] port ${UI_PORT} already answers; using it as found`
      : `[ui-server] vite preview started on ${UI_PORT} (pid ${pid})${built ? " after a rebuild" : ""}`,
  );
});
