/** Stops the server the `vscode-server` project started, after every project depending on it. */
import { test } from "@playwright/test";
import { stopVsCodeServer } from "../../support/vscode-server.js";

test("the VS Code server is stopped", { tag: ["@infra"] }, async () => {
  await stopVsCodeServer();
});
