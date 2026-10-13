/** Stops the preview server the `ui-server` project started, after every project depending on it. */
import { test } from "@playwright/test";
import { stopUiServer } from "../../support/ui-server.js";

test("the UI preview server is stopped", { tag: ["@ui", "@tier1"] }, async () => {
  await stopUiServer();
});
