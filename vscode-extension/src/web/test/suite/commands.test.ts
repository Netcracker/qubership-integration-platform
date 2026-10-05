import * as assert from "assert";
import * as vscode from "vscode";
import {
  FIXTURES,
  SCHEMA,
  SUITE_TIMEOUT,
  activateExtension,
  activeTab,
  closeAllEditors,
  copyFolder,
  describeInput,
  exists,
  expectCustomEditor,
  fileNameOf,
  folderOf,
  golden,
  listDirectory,
  listTree,
  manifest,
  readText,
  readYaml,
  suffixOf,
  waitFor,
  withPrompts,
  workspaceRoot,
  workspaceUri,
} from "./harness";

// `revealed` is false for a file `qip.revealInExplorer` opens in the chain editor rather than in its
// own, which is the defect the Open commands suite pins.
const EDITORS = [
  { viewType: "qip.chainFile.editor", fixture: FIXTURES.chain, revealed: true },
  { viewType: "qip.serviceFile.editor", fixture: FIXTURES.service, revealed: true },
  { viewType: "qip.contextServiceFile.editor", fixture: FIXTURES.contextService, revealed: false },
  { viewType: "qip.mcpServiceFile.editor", fixture: FIXTURES.mcpService, revealed: false },
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
    const copy = `${folderOf(FIXTURES.chain)}/diff-copy.chain.qip.yaml`;
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
      assert.deepStrictEqual(await listDirectory(workspaceUri(folder)), [`${folder}.chain.qip.yaml`]);
      assert.deepStrictEqual(await readYaml(workspaceUri(`${folder}/${folder}.chain.qip.yaml`)), {
        $schema: `${SCHEMA}/chain`,
        id: folder,
        name: "vsc-created-chain",
        content: {},
      });
      await assertGolden(workspaceUri(`${folder}/${folder}.chain.qip.yaml`), folder, "CHAIN", ".chain.qip.yaml");
    } finally {
      await vscode.workspace.fs.delete(workspaceUri(folder), { recursive: true });
    }
  });

  const SERVICE_TYPES = [
    { pick: "EXTERNAL", suffix: ".service.qip.yaml", schema: "service" },
    { pick: "INTERNAL", suffix: ".service.qip.yaml", schema: "service" },
    { pick: "IMPLEMENTED", suffix: ".service.qip.yaml", schema: "service" },
    { pick: "CONTEXT", suffix: ".context-service.qip.yaml", schema: "context-service" },
    { pick: "MCP", suffix: ".mcp-service.qip.yaml", schema: "mcp-service" },
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

  for (const { viewType, fixture } of EDITORS.filter((each) => each.revealed)) {
    test(`qip.revealInExplorer opens ${suffixOf(fixture)} in ${viewType}`, async () => {
      await vscode.commands.executeCommand("qip.revealInExplorer", { fileUri: workspaceUri(fixture) });
      await expectCustomEditor(viewType, workspaceUri(fixture));
    });
  }

  // Pins a defect (docs/product-defects.md): `qip.revealInExplorer` opens context and MCP services in
  // the chain editor. The case fails once the command opens their own editors; then assert that instead.
  for (const { fixture } of EDITORS.filter((each) => !each.revealed)) {
    test(`qip.revealInExplorer opens ${suffixOf(fixture)} in the chain editor`, async () => {
      const uri = workspaceUri(fixture);
      await vscode.commands.executeCommand("qip.revealInExplorer", { fileUri: uri });
      await expectCustomEditor("qip.chainFile.editor", uri);
    });
  }
});
