import * as assert from "assert";
import * as vscode from "vscode";
import { QipExplorerProvider } from "../../qipExplorer";
import {
  FIXTURES,
  SCHEMA,
  SUITE_TIMEOUT,
  WindowStubs,
  activateExtension,
  activeTab,
  closeAllEditors,
  copyFolder,
  describeInput,
  exists,
  expectCustomEditor,
  expectWebviewPanel,
  fileNameOf,
  folderOf,
  golden,
  holdsFor,
  listDirectory,
  listTree,
  manifest,
  openTabLabels,
  readText,
  readYaml,
  suffixOf,
  useExtensionModules,
  waitFor,
  withPrompts,
  workspaceRoot,
  workspaceUri,
  writeYaml,
} from "./harness";

const EDITORS = [
  { viewType: "qip.chainFile.editor", fixture: FIXTURES.chain },
  { viewType: "qip.serviceFile.editor", fixture: FIXTURES.service },
  { viewType: "qip.contextServiceFile.editor", fixture: FIXTURES.contextService },
  { viewType: "qip.mcpServiceFile.editor", fixture: FIXTURES.mcpService },
];

// The ids the golden files carry in place of the one a create command generates.
const GOLDEN_IDS: Record<string, string> = {
  CHAIN: "834696cd-aaeb-47dc-875d-6765b2439fe2",
  EXTERNAL: "4587b5f6-5963-4809-810f-65b498aaa70c",
  CONTEXT: "8c167f39-dec0-4713-836c-ed25951446db",
  MCP: "134deac4-0703-4bfa-a1a3-0912fdc2cc8a",
};

// Compares a created file with its golden copy, the generated id replaced by the golden one.
async function assertGolden(file: vscode.Uri, generatedId: string, kind: string, suffix: string): Promise<void> {
  const id = GOLDEN_IDS[kind];
  const written = (await readText(file)).split(generatedId).join(id);
  assert.strictEqual(written, golden(`${id}${suffix}`), `the ${kind} file differs from src/web/test/golden/${id}${suffix}`);
}

// Runs a command that writes into the workspace root and returns the one directory it added.
async function newRootDirectory(run: () => Thenable<unknown>): Promise<string> {
  const before = await listDirectory(workspaceRoot());
  await run();
  const added = (await listDirectory(workspaceRoot())).filter((name) => !before.includes(name));
  assert.strictEqual(added.length, 1, `expected one new directory, got ${JSON.stringify(added)}`);
  return added[0];
}

// Runs a command with the error popup captured, and returns the messages it raised. `stubs` answers
// the other prompts the command raises, inside the same capture.
async function errorsFrom(run: () => Thenable<unknown>, stubs: WindowStubs = {}): Promise<string[]> {
  const errors: string[] = [];
  await withPrompts(
    {
      ...stubs,
      showErrorMessage: (message) => {
        errors.push(message);
        return undefined;
      },
    },
    run,
  );
  return errors;
}

suite("Custom editors", function () {
  this.timeout(SUITE_TIMEOUT);

  suiteSetup(activateExtension);
  teardown(closeAllEditors);

  test("the manifest contributes the four editors this suite opens", () => {
    const contributed = manifest().contributes.customEditors.map(
      (editor: { viewType: string }) => editor.viewType,
    );
    assert.deepStrictEqual(contributed.sort(), EDITORS.map((editor) => editor.viewType).sort());
  });

  for (const { viewType, fixture } of EDITORS) {
    test(`${viewType} is the default editor for ${suffixOf(fixture)}`, async () => {
      await vscode.commands.executeCommand("vscode.open", workspaceUri(fixture));
      await expectCustomEditor(viewType, workspaceUri(fixture));
    });
  }

  // The manifest's `priority.diffEditor` and its `workbench.diffEditorAssociations` default both ask for this.
  test("a chain diff opens in the chain editor", async () => {
    const copy = `${folderOf(FIXTURES.chain)}/diff-copy.chain.cip.yaml`;
    await vscode.workspace.fs.copy(workspaceUri(FIXTURES.chain), workspaceUri(copy), { overwrite: true });
    try {
      await vscode.commands.executeCommand("vscode.diff", workspaceUri(FIXTURES.chain), workspaceUri(copy), "chain diff");
      // The input of a custom diff tab exposes its view type alone, so the title is what tells the
      // diff apart from the chain editor opened on one of its two files.
      const tab = await waitFor(() => (activeTab()?.label === "chain diff" ? activeTab() : undefined), "the diff tab").catch(() =>
        assert.fail(`expected the "chain diff" tab, got ${activeTab()?.label}: ${describeInput(activeTab()?.input)}`),
      );
      assert.ok(!(tab.input instanceof vscode.TabInputTextDiff), "expected the chain editor, got a text diff");
      assert.strictEqual((tab.input as { viewType?: string }).viewType, "qip.chainFile.editor");
    } finally {
      await closeAllEditors();
      await vscode.workspace.fs.delete(workspaceUri(copy));
    }
  });
});

suite("Create commands", function () {
  this.timeout(SUITE_TIMEOUT);

  suiteSetup(activateExtension);
  teardown(closeAllEditors);

  test("qip.createChain writes a chain named by the prompt into a folder named by its id", async () => {
    const folder = await newRootDirectory(() =>
      withPrompts({ showInputBox: () => "vsc-created-chain" }, () =>
        vscode.commands.executeCommand("qip.createChain"),
      ),
    );
    try {
      assert.deepStrictEqual(await listDirectory(workspaceUri(folder)), [`${folder}.chain.cip.yaml`]);
      assert.deepStrictEqual(await readYaml(workspaceUri(`${folder}/${folder}.chain.cip.yaml`)), {
        $schema: `${SCHEMA}/chain`,
        id: folder,
        name: "vsc-created-chain",
        content: {},
      });
      await assertGolden(workspaceUri(`${folder}/${folder}.chain.cip.yaml`), folder, "CHAIN", ".chain.cip.yaml");
    } finally {
      await vscode.workspace.fs.delete(workspaceUri(folder), { recursive: true });
    }
  });

  // `createEmptyChain(true)` writes into the folder above the workspace. This host mounts the
  // workspace at the root of its filesystem, where `Uri.joinPath(root, "..")` resolves to the root
  // again, so the chain lands in the workspace and this host cannot tell the two create commands
  // apart. Only the dismissed prompt is pinned here, then: `qip.createService` writes nothing when
  // its name prompt is dismissed, and this command falls back to a name.
  test('qip.createChainParent names the chain "New Chain" when the name prompt is dismissed', async () => {
    const folder = await newRootDirectory(() =>
      withPrompts({ showInputBox: () => undefined }, () =>
        vscode.commands.executeCommand("qip.createChainParent"),
      ),
    );
    try {
      const chain = await readYaml(workspaceUri(`${folder}/${folder}.chain.cip.yaml`));
      assert.strictEqual(chain.name, "New Chain");
      assert.strictEqual(chain.id, folder);
    } finally {
      await vscode.workspace.fs.delete(workspaceUri(folder), { recursive: true });
    }
  });

  const SERVICE_TYPES = [
    { pick: "EXTERNAL", suffix: ".service.cip.yaml", schema: "service" },
    { pick: "INTERNAL", suffix: ".service.cip.yaml", schema: "service" },
    { pick: "IMPLEMENTED", suffix: ".service.cip.yaml", schema: "service" },
    { pick: "CONTEXT", suffix: ".context-service.cip.yaml", schema: "context-service" },
    { pick: "MCP", suffix: ".mcp-service.cip.yaml", schema: "mcp-service" },
  ];

  for (const { pick, suffix, schema } of SERVICE_TYPES) {
    test(`qip.createService writes a ${pick} service as *${suffix}`, async () => {
      const answers: Record<string, string> = {
        "Enter new service name": `vsc-created-${pick.toLowerCase()}`,
        "Enter MCP service identifier": "vscCreatedMcp",
        "Enter service description (optional)": `Created as ${pick}`,
      };
      const folder = await newRootDirectory(() =>
        withPrompts(
          {
            showInputBox: (options) => answers[options?.prompt ?? ""],
            showQuickPick: (items) => items.find((item) => item.value === pick),
          },
          () => vscode.commands.executeCommand("qip.createService"),
        ),
      );
      try {
        const fileName = `${folder}${suffix}`;
        assert.deepStrictEqual(await listDirectory(workspaceUri(folder)), [fileName]);
        const service = await readYaml(workspaceUri(`${folder}/${fileName}`));
        assert.strictEqual(service.$schema, `${SCHEMA}/${schema}`);
        assert.strictEqual(service.id, folder);
        assert.strictEqual(service.name, `vsc-created-${pick.toLowerCase()}`);
        assert.strictEqual(service.content.description, `Created as ${pick}`);
        if (schema === "service") {
          assert.strictEqual(service.content.integrationSystemType, pick);
        } else {
          assert.strictEqual(service.content.integrationSystemType, undefined);
        }
        if (pick === "MCP") {
          assert.strictEqual(service.content.identifier, "vscCreatedMcp");
        }
        if (GOLDEN_IDS[pick]) {
          await assertGolden(workspaceUri(`${folder}/${fileName}`), folder, pick, suffix);
        }
      } finally {
        await vscode.workspace.fs.delete(workspaceUri(folder), { recursive: true });
      }
    });
  }

  test("qip.createService writes nothing when the name prompt is dismissed", async () => {
    const before = await listTree(workspaceRoot());
    await withPrompts(
      {
        showInputBox: () => undefined,
        showQuickPick: (items) => items.find((item) => item.value === "INTERNAL"),
      },
      () => vscode.commands.executeCommand("qip.createService"),
    );
    assert.deepStrictEqual(await listTree(workspaceRoot()), before);
  });
});

suite("Delete commands", function () {
  this.timeout(SUITE_TIMEOUT);

  suiteSetup(activateExtension);
  teardown(closeAllEditors);

  // Each case deletes its own copy, so the fixtures stay intact for the suites that follow.
  const CASES = [
    { command: "qip.deleteChain", fixture: FIXTURES.chain, copy: "chains/delete-chain" },
    { command: "qip.deleteService", fixture: FIXTURES.service, copy: "services/delete-service" },
    { command: "qip.deleteService", fixture: FIXTURES.contextService, copy: "services/delete-context" },
    { command: "qip.deleteService", fixture: FIXTURES.mcpService, copy: "services/delete-mcp" },
  ];

  for (const { command, fixture, copy } of CASES) {
    const fileName = fileNameOf(fixture);

    test(`${command} removes ${suffixOf(fixture)} with its whole folder`, async () => {
      await copyFolder(folderOf(fixture), copy);
      try {
        const confirmations: string[] = [];
        await withPrompts(
          {
            showWarningMessage: (message, options) => {
              confirmations.push(message);
              assert.deepStrictEqual(options, { modal: true });
              return "Delete";
            },
          },
          () =>
            vscode.commands.executeCommand(command, {
              fileUri: workspaceUri(`${copy}/${fileName}`),
              label: "delete-me",
            }),
        );
        assert.strictEqual(confirmations.length, 1);
        assert.match(confirmations[0], /"delete-me"/);
        const leftovers = (await exists(workspaceUri(copy))) ? await listTree(workspaceUri(copy)) : [];
        assert.deepStrictEqual(leftovers, [], `${copy} still holds files`);
        assert.strictEqual(await exists(workspaceUri(copy)), false, `${copy} is still there`);
        assert.ok(await exists(workspaceUri(fixture)), "the fixture the copy came from is gone");
      } finally {
        if (await exists(workspaceUri(copy))) {
          await vscode.workspace.fs.delete(workspaceUri(copy), { recursive: true });
        }
      }
    });
  }

  test("qip.deleteChain deletes nothing when the confirmation is dismissed", async () => {
    const copy = "chains/keep-me";
    await copyFolder(folderOf(FIXTURES.chain), copy);
    try {
      const before = await listTree(workspaceUri(copy));
      await withPrompts({ showWarningMessage: () => undefined }, () =>
        vscode.commands.executeCommand("qip.deleteChain", {
          fileUri: workspaceUri(`${copy}/${fileNameOf(FIXTURES.chain)}`),
          label: "keep-me",
        }),
      );
      assert.deepStrictEqual(await listTree(workspaceUri(copy)), before);
    } finally {
      await vscode.workspace.fs.delete(workspaceUri(copy), { recursive: true });
    }
  });
});

suite("Open commands", function () {
  this.timeout(SUITE_TIMEOUT);

  suiteSetup(activateExtension);
  teardown(closeAllEditors);

  for (const fixture of [FIXTURES.chain, FIXTURES.service]) {
    test(`qip.openInTextEditor opens ${suffixOf(fixture)} as text`, async () => {
      const uri = workspaceUri(fixture);
      await vscode.commands.executeCommand("qip.openInTextEditor", { fileUri: uri });
      const input = await waitFor(() => activeTab()?.input, "the text tab");
      assert.ok(input instanceof vscode.TabInputText, `expected a text editor, got ${describeInput(input)}`);
      assert.strictEqual(input.uri.toString(), uri.toString());
      assert.strictEqual(vscode.window.activeTextEditor?.document.uri.toString(), uri.toString());
    });
  }

  for (const { viewType, fixture } of EDITORS) {
    test(`qip.revealInExplorer opens ${suffixOf(fixture)} in ${viewType}`, async () => {
      await vscode.commands.executeCommand("qip.revealInExplorer", { fileUri: workspaceUri(fixture) });
      await expectCustomEditor(viewType, workspaceUri(fixture));
    });
  }

  // The command takes no argument and raises no prompt: it opens the editor on no document at all.
  test("qip.open opens the offline chain editor webview", async () => {
    await vscode.commands.executeCommand("qip.open");
    await expectWebviewPanel("QIP Offline Chain Editor");
  });
});

suite("Export image commands", function () {
  this.timeout(SUITE_TIMEOUT);

  // The qip-ui webview renders the image and posts it back, so a level-2 case reaches the panel and no
  // further: nothing writes a file here. The startup payload reaches the panel through a WeakMap in the
  // extension's own copy of apiRouter, which this bundle cannot read, so each case asserts the panel
  // and the paths the payload is built from.
  const outputDir = workspaceUri("export-images-out").toString();

  suiteSetup(activateExtension);
  teardown(closeAllEditors);

  // The panel is awaited inside the capture, because `errorsFrom` restores the popup as soon as its
  // callback resolves: a message raised while the panel opens would otherwise go unrecorded.
  test("qip.exportImages opens the export webview for the chain it is given", async () => {
    const errors = await errorsFrom(async () => {
      await vscode.commands.executeCommand("qip.exportImages", workspaceUri(FIXTURES.chain).toString(), { outputDir });
      await expectWebviewPanel("QIP Export Images");
    });
    assert.deepStrictEqual(errors, []);
  });

  test("qip.exportImages reports a missing output directory and opens nothing", async () => {
    const errors = await errorsFrom(() => vscode.commands.executeCommand("qip.exportImages", undefined, {}));
    assert.deepStrictEqual(errors, ["outputDir is required"]);
    await holdsFor(async () => assert.deepStrictEqual(openTabLabels(), []), 1000);
  });

  // The payload carries a chain id per path, so the handler parses every path before the panel opens.
  test("qip.exportImages reports a path it cannot read as a chain and opens nothing", async () => {
    const absent = workspaceUri("chains/absent.chain.cip.yaml");
    const errors = await errorsFrom(() =>
      vscode.commands.executeCommand("qip.exportImages", absent.toString(), { outputDir }),
    );
    assert.strictEqual(errors.length, 1, `expected one message, got ${JSON.stringify(errors)}`);
    assert.match(errors[0], /^Failed to export QIP chain images: /);
    assert.ok(errors[0].includes(absent.toString()), `the message does not name the file: ${errors[0]}`);
    await holdsFor(async () => assert.deepStrictEqual(openTabLabels(), []), 1000);
  });

  test("qip.exportImagesFromExplorer asks for an output folder, then opens the export webview", async () => {
    let asked: vscode.OpenDialogOptions | undefined;
    const errors = await errorsFrom(
      async () => {
        await vscode.commands.executeCommand("qip.exportImagesFromExplorer");
        await expectWebviewPanel("QIP Export Images");
      },
      {
        showOpenDialog: (options) => {
          asked = options;
          return [workspaceUri("export-images-out")];
        },
      },
    );
    assert.deepStrictEqual(errors, []);
    assert.strictEqual(asked?.canSelectFolders, true, "the dialog does not ask for a folder");
    assert.strictEqual(asked?.canSelectFiles, false);
  });

  test("qip.exportImagesFromExplorer opens nothing when the folder dialog is dismissed", async () => {
    await withPrompts({ showOpenDialog: () => undefined }, () =>
      vscode.commands.executeCommand("qip.exportImagesFromExplorer"),
    );
    await holdsFor(async () => assert.deepStrictEqual(openTabLabels(), []), 1000);
  });
});

suite("Explorer commands", function () {
  this.timeout(SUITE_TIMEOUT);

  const chainId = "7c3a1f58-6b24-4d0e-9f31-5a8c2e4b7d16";
  const chainPath = `refreshed/${chainId}/${chainId}.chain.cip.yaml`;
  let explorer: QipExplorerProvider;

  suiteSetup(async () => {
    await activateExtension();
    useExtensionModules();
    explorer = new QipExplorerProvider();
  });

  teardown(closeAllEditors);

  // The provider the command refreshes is private to extension.ts and a tree view's rendered content
  // has no API, so the case pins what is reachable: the command is registered, `refresh` fires the
  // event the tree view redraws on, and the tree data is read from the filesystem rather than from a
  // cache, so a file written behind the explorer is listed on the next read.
  test("qip.refreshExplorer is registered, and a refresh fires the event and re-reads the files", async () => {
    const commands = await vscode.commands.getCommands(true);
    assert.ok(commands.includes("qip.refreshExplorer"), "qip.refreshExplorer is not registered");
    await vscode.commands.executeCommand("qip.refreshExplorer");

    const fired: unknown[] = [];
    const subscription = explorer.onDidChangeTreeData((event) => fired.push(event));
    try {
      explorer.refresh();
      assert.strictEqual(fired.length, 1, "refresh fired no tree data event");
    } finally {
      subscription.dispose();
    }

    try {
      const chain = await readYaml(workspaceUri(FIXTURES.chain));
      await writeYaml(chainPath, { ...chain, id: chainId, name: "vsc-refreshed-chain" });
      const root = await explorer.getChildren();
      const category = root.find((item) => item.label === "Chains");
      assert.ok(category, "no Chains category in the tree");
      const listed = await explorer.getChildren(category);
      const refreshed = listed.find((item) => item.id === chainId);
      assert.ok(refreshed, `the chain written behind the explorer is missing from ${JSON.stringify(listed.map((item) => item.id))}`);
      assert.strictEqual(refreshed.label, `vsc-refreshed-chain-${chainId}`);
      assert.strictEqual(refreshed.fileUri?.toString(), workspaceUri(chainPath).toString());
    } finally {
      await vscode.workspace.fs.delete(workspaceUri("refreshed"), { recursive: true });
    }
  });
});
