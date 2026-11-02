/**
 * The `vscode-server` setup's command line and the freshness check that decides its two builds.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { SCHEMA_OUTPUTS } from "../../support/schemas-build.js";
import {
  EXTENSION_BUILD,
  EXTENSION_INPUTS,
  EXTENSION_OUTPUT,
  serverCommand,
  staleBuilds,
  UI_LIB_BUILDS,
  UI_LIB_INPUTS,
  UI_LIB_OUTPUT,
  VSCODE_COMMIT,
} from "../../support/vscode-server.js";
import { withEnv } from "../../support/with-env.js";

/** Each input and output of the two builds, relative to the repository root; `path.join` resolves the `../`. */
const UI_LIB = UI_LIB_INPUTS.map((each) => path.join("ui", each));
const EXTENSION = EXTENSION_INPUTS.map((each) => path.join("vscode-extension", each));
const OUTPUTS = [path.join("ui", UI_LIB_OUTPUT), path.join("vscode-extension", EXTENSION_OUTPUT)];

/**
 * A repository tree holding every input and output, at 100 s and 200 s, with `changes` applied on
 * top: a number sets that path's mtime in seconds, `null` leaves it out. A path whose name holds a
 * dot is a file, any other path a directory holding one file.
 */
function repoTree(changes: Record<string, number | null>): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-vscode-server-"));
  const mtimes: Record<string, number | null> = {
    ...Object.fromEntries([...UI_LIB, ...EXTENSION].map((each) => [each, 100])),
    ...Object.fromEntries(OUTPUTS.map((each) => [each, 200])),
    ...changes,
  };
  for (const [relative, seconds] of Object.entries(mtimes)) {
    if (seconds === null) continue;
    const file = path.basename(relative).includes(".") ? path.join(root, relative) : path.join(root, relative, "file");
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, "");
    fs.utimesSync(file, seconds, seconds);
  }
  return root;
}

test.describe("vscode-server", () => {
  test("the server runs the pinned build on the fixture workspace", () => {
    const root = repoRoot();
    const { command, args, cwd } = withEnv({ E2E_VSCODE_COMMIT: undefined }, () => serverCommand(root));
    expect(command).toBe(path.join(root, "node_modules", ".bin", "vscode-test-web"));
    expect(fs.existsSync(command)).toBe(true);
    const workspace = path.join(root, "e2e", "fixtures", "vscode", "workspace");
    expect(args).toEqual([
      "--browser",
      "none",
      "--host",
      "localhost",
      "--port",
      "3917",
      "--quality",
      "insiders",
      "--commit",
      VSCODE_COMMIT,
      "--extensionDevelopmentPath=.",
      workspace,
    ]);
    expect(VSCODE_COMMIT).toMatch(/^[0-9a-f]{40}$/);
    // The server exits on a workspace path that is not a directory.
    expect(path.isAbsolute(workspace) && fs.statSync(workspace).isDirectory()).toBe(true);
    // `.` resolves against the working directory to the extension.
    const manifest = JSON.parse(fs.readFileSync(path.join(cwd, "package.json"), "utf-8")) as { name: string };
    expect(manifest.name).toBe("@netcracker/qip-vscode-extension");
  });

  test("E2E_VSCODE_COMMIT overrides the pinned build", () => {
    const commit = "0123456789abcdef0123456789abcdef01234567";
    const { args } = withEnv({ E2E_VSCODE_COMMIT: commit }, () => serverCommand("/repo"));
    expect(args[args.indexOf("--commit") + 1]).toBe(commit);
  });

  test("the builds skip the scripts that build the schemas", () => {
    expect(UI_LIB_BUILDS).toEqual([
      ["run", "build:lib:bundled", "-w", "@netcracker/qip-ui"],
      ["run", "build:lib:types", "-w", "@netcracker/qip-ui"],
    ]);
    expect(EXTENSION_BUILD).toEqual(["run", "compile-web", "-w", "@netcracker/qip-vscode-extension"]);
    const scripts = (dir: string) =>
      (JSON.parse(fs.readFileSync(path.join(repoRoot(), dir, "package.json"), "utf-8")) as { scripts: Record<string, string> })
        .scripts;
    // `prepare-deps`, which builds the schemas again, runs only from the extension's `build`.
    expect(scripts("vscode-extension")["compile-web"]).toBe("webpack");
    for (const [dir, name] of [["ui", "build:lib:bundled"], ["ui", "build:lib:types"], ["vscode-extension", "compile-web"]]) {
      expect(scripts(dir)[name], name).toBeDefined();
      expect(scripts(dir)[`pre${name}`], `pre${name}`).toBeUndefined();
    }
  });

  test("every input exists or is the output of an earlier build", () => {
    const built = [...SCHEMA_OUTPUTS.map((each) => path.join("schemas", each)), path.join("ui", UI_LIB_OUTPUT)];
    const tracked = [...UI_LIB, ...EXTENSION].filter((each) => !built.includes(each));
    expect(tracked.filter((each) => !fs.existsSync(path.join(repoRoot(), each)))).toEqual([]);
  });

  test("current when both outputs are newer than their inputs", async () => {
    expect(await staleBuilds(repoTree({}))).toEqual({ uiLib: false, extension: false });
  });

  test("a library input rebuilds both, an extension input one", async () => {
    for (const input of new Set([...UI_LIB, ...EXTENSION])) {
      const uiLib = UI_LIB.includes(input);
      expect(await staleBuilds(repoTree({ [input]: 300 })), input).toEqual({ uiLib, extension: true });
    }
    // The cases the setup meets most: a schema build, a library build, and an edit to the manifest.
    expect(await staleBuilds(repoTree({ "schemas/dist": 300 }))).toEqual({ uiLib: true, extension: true });
    expect(await staleBuilds(repoTree({ "ui/dist-lib": 300 }))).toEqual({ uiLib: false, extension: true });
    expect(await staleBuilds(repoTree({ "vscode-extension/package.json": 300 }))).toEqual({
      uiLib: false,
      extension: true,
    });
  });

  test("a missing output rebuilds it and what is built from it", async () => {
    expect(await staleBuilds(repoTree({ "ui/dist-lib": null }))).toEqual({ uiLib: true, extension: true });
    expect(await staleBuilds(repoTree({ "vscode-extension/dist/.e2e-build": null }))).toEqual({
      uiLib: false,
      extension: true,
    });
  });
});
