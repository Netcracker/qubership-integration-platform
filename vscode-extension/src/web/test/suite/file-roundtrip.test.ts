import * as assert from "assert";
import * as vscode from "vscode";
import * as yaml from "yaml";
import Ajv from "ajv";
import { getApiResponse } from "../../response";
import { QipExplorerItem, QipExplorerProvider } from "../../qipExplorer";
import {
  FIXTURES,
  SCHEMA,
  SUITE_TIMEOUT,
  activateExtension,
  closeAllEditors,
  copyFolder,
  expectCustomEditor,
  fileNameOf,
  folderOf,
  golden,
  holdsFor,
  listTree,
  readText,
  readYaml,
  useExtensionModules,
  waitFor,
  workspaceRoot,
  workspaceUri,
  writeYaml,
} from "./harness";

const CHAIN_SCHEMA = `${SCHEMA}/chain`;

// The document schemas from schemas/src/main/resources/qip-model, bundled as text by webpack. The
// path is `qipModelDir` in webpack.config.js.
function documentSchemas(): Ajv {
  const sources = require.context("../../../../../schemas/src/main/resources/qip-model", true, /\.schema\.yaml$/);
  const ajv = new Ajv({ allErrors: true, discriminator: true, keywords: ["subtype", "resourceType", "metaInfo"] });
  for (const key of sources.keys()) {
    const schema = yaml.parse(sources(key));
    ajv.addSchema(schema, schema.$id);
  }
  return ajv;
}

function assertValidChain(ajv: Ajv, document: any): void {
  assert.strictEqual(document.$schema, CHAIN_SCHEMA);
  const valid = ajv.validate(CHAIN_SCHEMA, document);
  assert.ok(valid, `the saved chain fails chain.schema.yaml: ${ajv.errorsText()}`);
}

// Calls the router in the test bundle's copy of the modules with a message shaped the way the webview
// sends one. The wiring from the webview to the running extension's router (`enrichWebview`) is not
// exercised.
async function callRouter(type: string, payload: unknown, documentUri: vscode.Uri): Promise<unknown> {
  return getApiResponse({ requestId: crypto.randomUUID(), type, payload } as any, documentUri);
}

suite("Chain file round trip", function () {
  this.timeout(SUITE_TIMEOUT);

  const copy = "chains/roundtrip";
  const chainUri = () => workspaceUri(`${copy}/${fileNameOf(FIXTURES.chain)}`);
  let ajv: Ajv;
  let original: any;

  suiteSetup(async () => {
    await activateExtension();
    useExtensionModules();
    ajv = documentSchemas();
    original = await readYaml(workspaceUri(FIXTURES.chain));
  });

  setup(() => copyFolder(folderOf(FIXTURES.chain), copy));

  teardown(async () => {
    await closeAllEditors();
    await vscode.workspace.fs.delete(workspaceUri(copy), { recursive: true });
  });

  test("the catalog-exported fixture is a valid chain document", () => {
    assertValidChain(ajv, original);
  });

  test("a change made through the extension's API saves a valid chain with only that change", async () => {
    await vscode.commands.executeCommand("vscode.open", chainUri());
    await expectCustomEditor("qip.chainFile.editor", chainUri());

    await callRouter(
      "updateChain",
      {
        id: original.id,
        chain: {
          name: "vsc-roundtrip-chain",
          description: "Saved by the round-trip test",
          labels: [
            { name: "roundtrip", technical: false },
            { name: "technical-label", technical: true },
          ],
        },
      },
      chainUri(),
    );

    const saved = await readText(chainUri());
    assert.strictEqual(saved, golden(fileNameOf(FIXTURES.chain)), "the saved chain differs from its golden copy");
    const document = yaml.parse(saved);
    assertValidChain(ajv, document);
    assert.deepStrictEqual(document, {
      ...original,
      name: "vsc-roundtrip-chain",
      content: { ...original.content, description: "Saved by the round-trip test", labels: ["roundtrip"] },
    });

    const editorDocument = vscode.workspace.textDocuments.find(
      (candidate) => candidate.uri.toString() === chainUri().toString(),
    );
    assert.ok(editorDocument, "the chain editor holds no document for the file");
    await waitFor(() => editorDocument.getText() === saved, "the chain editor to show the saved file").catch(() =>
      assert.fail(`the chain editor still shows:\n${editorDocument.getText()}`),
    );
    assert.strictEqual(editorDocument.isDirty, false);
  });

  test("opening a chain in its editor leaves every byte in place", async () => {
    const before = await readText(chainUri());
    // Earlier suites open the fixture too; a write on open would already have reformatted it.
    assert.notStrictEqual(before, yaml.stringify(yaml.parse(before)), "the fixture lost the catalog's formatting");
    await vscode.commands.executeCommand("vscode.open", chainUri());
    await expectCustomEditor("qip.chainFile.editor", chainUri());
    // The editor resolves after its tab appears, so a write on open can land later.
    await holdsFor(async () => assert.strictEqual(await readText(chainUri()), before), 2000);
  });

  // The extension writes a chain as `yaml.stringify` of the parsed document, so its first save of a
  // catalog export drops the `---` marker and the quotes. What it keeps is the content, and a second
  // save changes nothing.
  test("a save with nothing changed keeps the content, and saving again keeps every byte", async () => {
    const update = () => callRouter("updateChain", { id: original.id, chain: {} }, chainUri());

    await update();
    const first = await readText(chainUri());
    assert.deepStrictEqual(yaml.parse(first), original);
    assertValidChain(ajv, yaml.parse(first));

    await update();
    assert.strictEqual(await readText(chainUri()), first);
  });
});

suite("Explorer tree", function () {
  this.timeout(SUITE_TIMEOUT);

  const project = "nested/team-a/project-x";
  const chainId = "5d0f3c1e-8c1a-4c2e-9a53-0b1f7a6e2d41";
  const serviceId = "a4c7e2b9-3f60-4d8e-b1a2-6e9d0c5f7b38";
  let explorer: QipExplorerProvider;

  suiteSetup(async () => {
    await activateExtension();
    explorer = new QipExplorerProvider(useExtensionModules());

    const chain = await readYaml(workspaceUri(FIXTURES.chain));
    await writeYaml(`${project}/chains/${chainId}/${chainId}.chain.cip.yaml`, {
      ...chain,
      id: chainId,
      name: "vsc-nested-chain",
    });
    const service = await readYaml(workspaceUri(FIXTURES.contextService));
    await writeYaml(`${project}/services/${serviceId}/${serviceId}.context-service.cip.yaml`, {
      ...service,
      id: serviceId,
      name: "vsc-nested-context",
    });
  });

  suiteTeardown(() => vscode.workspace.fs.delete(workspaceUri("nested"), { recursive: true }));

  async function category(label: string): Promise<QipExplorerItem[]> {
    const root = await explorer.getChildren();
    const item = root.find((candidate) => candidate.label === label);
    assert.ok(item, `no ${label} category among ${JSON.stringify(root.map((each) => each.label))}`);
    return explorer.getChildren(item);
  }

  async function filesEndingWith(...suffixes: string[]): Promise<string[]> {
    const files = await listTree(workspaceRoot());
    return files.filter((file) => suffixes.some((suffix) => file.endsWith(suffix)));
  }

  function relative(uri: vscode.Uri | undefined): string | undefined {
    return uri?.path.substring(workspaceRoot().path.replace(/\/$/, "").length + 1);
  }

  test("the root holds the Chains and Services categories", async () => {
    const root = await explorer.getChildren();
    assert.deepStrictEqual(
      root.map((item) => [item.label, item.contextValue]),
      [
        ["Chains", "qip-chains-category"],
        ["Services", "qip-services-category"],
      ],
    );
  });

  test("Chains lists every chain file, however deep", async () => {
    const chains = await category("Chains");
    assert.deepStrictEqual(
      chains.map((item) => relative(item.fileUri)).sort(),
      await filesEndingWith(".chain.cip.yaml"),
    );

    const nested = chains.find((item) => item.id === chainId);
    assert.ok(nested, "the nested chain is missing");
    assert.strictEqual(nested.label, `vsc-nested-chain-${chainId}`);
    assert.strictEqual(nested.description, "2 elements, 1 connections");
    assert.strictEqual(nested.contextValue, "qip-chain");
    assert.deepStrictEqual(
      (await explorer.getChildren(nested)).map((item) => [item.label, item.description]),
      [
        ["HTTP Trigger", "http-trigger element"],
        ["Script", "script element"],
      ],
    );
  });

  test("Services lists every service file, however deep", async () => {
    const services = await category("Services");
    assert.deepStrictEqual(
      services.map((item) => relative(item.fileUri)).sort(),
      await filesEndingWith(".service.cip.yaml", ".context-service.cip.yaml", ".mcp-service.cip.yaml"),
    );

    const nested = services.find((item) => item.id === serviceId);
    assert.ok(nested, "the nested service is missing");
    assert.strictEqual(nested.label, `vsc-nested-context-${serviceId}`);
    assert.strictEqual(nested.description, "CONTEXT service");
    assert.strictEqual(nested.contextValue, "qip-service");
  });

  test("a nested item opens its file in the matching editor", async () => {
    const nested = (await category("Chains")).find((item) => item.id === chainId)!;
    const command = explorer.getTreeItem(nested).command;
    assert.strictEqual(command?.command, "qip.revealInExplorer");
    try {
      await vscode.commands.executeCommand(command.command, ...(command.arguments ?? []));
      await expectCustomEditor("qip.chainFile.editor", nested.fileUri!);
    } finally {
      await closeAllEditors();
    }
  });
});
