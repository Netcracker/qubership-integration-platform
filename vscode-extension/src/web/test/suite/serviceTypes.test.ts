// End-to-end cover for the per-type service file names, in a real VS Code host over a real file
// system. The jest suites mock `vscode`, so the two things they cannot answer are settled here:
// whether the `filenamePattern` selectors in package.json really route each name to its own custom
// editor, and whether a conversion behaves the same when `vscode.workspace.fs` is doing the work.
//
// The host is offline. Everything below reads the fixture projects under
// `tests/fixtures/service-projects/`, which vscode-test-web mounts as a virtual workspace whose
// writes stay in memory, so a conversion never touches the committed fixtures.

import * as assert from "assert";
import * as vscode from "vscode";

import { VSCodeFileApi } from "../../response/file/fileApiImpl";
import { setFileApi } from "../../response/file/fileApiProvider";
import {
  getEnvironments,
  getService,
  getServices,
} from "../../response/serviceApiRead";
import {
  createEnvironment,
  updateService,
} from "../../response/serviceApiModify";
import {
  getExtensionsForFile,
  getExtensionsForUri,
} from "../../response/file/fileExtensions";
import {
  resolveServiceType,
  serviceSchemaUrlForType,
  serviceTypeFromUri,
} from "../../response/file/serviceFileType";
import { ProjectConfigService } from "../../services/ProjectConfigService";
import { QipExplorerItem, QipExplorerProvider } from "../../qipExplorer";
import { IntegrationSystemType } from "../../api-services/servicesTypes";
import { ContentParser } from "../../api-services/parsers/ContentParser";

const EXTERNAL_ID = "11111111-1111-4111-8111-111111111111";
const INTERNAL_ID = "22222222-2222-4222-8222-222222222222";
const IMPLEMENTED_ID = "33333333-3333-4333-8333-333333333333";
const CONTEXT_ID = "44444444-4444-4444-8444-444444444444";
const MCP_ID = "55555555-5555-4555-8555-555555555555";
const LEGACY_EXTERNAL_ID = "66666666-6666-4666-8666-666666666666";
const LEGACY_INTERNAL_ID = "77777777-7777-4777-8777-777777777777";
const MIXED_LEGACY_ID = "88888888-8888-4888-8888-888888888888";
const MIXED_TYPED_ID = "99999999-9999-4999-8999-999999999999";
const ACME_EXTERNAL_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const ACME_INTERNAL_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

let root: vscode.Uri;
let networkCalls: string[] = [];
let realFetch: typeof globalThis.fetch;

function fixture(...segments: string[]): vscode.Uri {
  return vscode.Uri.joinPath(root, ...segments);
}

function serviceFile(project: string, id: string, extension: string) {
  return fixture(project, id, `${id}${extension}`);
}

async function exists(uri: vscode.Uri): Promise<boolean> {
  try {
    await vscode.workspace.fs.stat(uri);
    return true;
  } catch {
    return false;
  }
}

async function readYaml(uri: vscode.Uri): Promise<any> {
  return ContentParser.parseContent(
    new TextDecoder().decode(await vscode.workspace.fs.readFile(uri)),
  );
}

/** Polls until the probe answers, because a tab appears a turn after the open command resolves. */
async function waitFor<T>(
  probe: () => T | undefined,
  timeoutMs = 15000,
): Promise<T | undefined> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = probe();
    if (value !== undefined) {
      return value;
    }
    if (Date.now() >= deadline) {
      return undefined;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

/** Whether the request stays on the vscode-test-web server rather than leaving for a real host. */
function isLocalUrl(url: string): boolean {
  const localHosts = ["localhost", "127.0.0.1", "[::1]", "::1", ""];
  try {
    return localHosts.includes(new URL(url, self.location.href).hostname);
  } catch {
    return false;
  }
}

function tabViewType(tab: vscode.Tab | undefined): string | undefined {
  const input = tab?.input as { viewType?: unknown } | undefined;
  return typeof input?.viewType === "string" ? input.viewType : undefined;
}

function tabPath(tab: vscode.Tab): string | undefined {
  const input = tab.input as { uri?: { path?: unknown } } | undefined;
  return typeof input?.uri?.path === "string" ? input.uri.path : undefined;
}

function openTabPaths(): string[] {
  return vscode.window.tabGroups.all
    .flatMap((group) => group.tabs)
    .map(tabPath)
    .filter((path): path is string => path !== undefined);
}

/** Opens the file the way a double-click does and reports the editor VS Code picked. */
async function openAndReadViewType(
  uri: vscode.Uri,
): Promise<string | undefined> {
  await vscode.commands.executeCommand("vscode.open", uri);
  return waitFor(() => {
    const tab = vscode.window.tabGroups.activeTabGroup.activeTab;
    return tabPath(tab!) === uri.path ? tabViewType(tab) : undefined;
  });
}

async function closeAllEditors(): Promise<void> {
  await vscode.commands.executeCommand("workbench.action.closeAllEditors");
}

function serviceGroups(items: QipExplorerItem[]): Map<string, string[]> {
  return new Map(
    items.map((group) => [
      group.label,
      (group.children ?? []).map((service) => service.id),
    ]),
  );
}

async function readServiceGroups(): Promise<QipExplorerItem[]> {
  const provider = new QipExplorerProvider({
    subscriptions: [],
    extensionUri: root,
  } as unknown as vscode.ExtensionContext);
  const rootItems = await provider.getChildren();
  const services = rootItems.find((item) => item.label === "Services");
  assert.ok(services, "the tree has no Services category");
  return provider.getChildren(services);
}

suite("Service types in the web host", () => {
  suiteSetup(async function (this: Mocha.Context) {
    this.timeout(60000);

    const folders = vscode.workspace.workspaceFolders;
    assert.ok(
      folders && folders.length > 0,
      "no workspace folder is open — run through `npm run test:integration`, " +
        "which mounts tests/fixtures/service-projects",
    );
    root = folders[0].uri;

    // The test bundle is a webpack entry of its own, so its module instances are separate from the
    // running extension's. It needs its own FileApi; only `getLibrary` reads the context.
    setFileApi(
      new VSCodeFileApi({
        extensionUri: root,
      } as unknown as vscode.ExtensionContext),
    );

    const extension = vscode.extensions.all.find((candidate) =>
      candidate.id.endsWith("qip-vscode-extension"),
    );
    assert.ok(extension, "the extension under development is not installed");
    await extension.activate();

    // The host has no outbound network. The mounted workspace itself is served over the local test
    // server, so record only what is aimed past it.
    networkCalls = [];
    realFetch = globalThis.fetch;
    globalThis.fetch = ((input: any, init?: any) => {
      const url = String(input?.url ?? input);
      if (!isLocalUrl(url)) {
        networkCalls.push(url);
      }
      return realFetch(input, init);
    }) as typeof globalThis.fetch;
  });

  suiteTeardown(async () => {
    // Restore before asserting: a failed assertion must not leave the wrapper on the host, where
    // every suite after this one would inherit it.
    globalThis.fetch = realFetch;
    await closeAllEditors();
    assert.deepStrictEqual(
      networkCalls,
      [],
      "the suite must stay offline, but it reached hosts other than the test server",
    );
  });

  test("each service file kind opens in its own custom editor", async function (this: Mocha.Context) {
    this.timeout(120000);

    const cases: [vscode.Uri, string][] = [
      [
        serviceFile("new-format", EXTERNAL_ID, ".external-service.qip.yaml"),
        "qip.externalServiceFile.editor",
      ],
      [
        serviceFile("new-format", INTERNAL_ID, ".internal-service.qip.yaml"),
        "qip.internalServiceFile.editor",
      ],
      [
        serviceFile(
          "new-format",
          IMPLEMENTED_ID,
          ".implemented-service.qip.yaml",
        ),
        "qip.implementedServiceFile.editor",
      ],
      [
        serviceFile("new-format", CONTEXT_ID, ".context-service.qip.yaml"),
        "qip.contextServiceFile.editor",
      ],
      [
        serviceFile("new-format", MCP_ID, ".mcp-service.qip.yaml"),
        "qip.mcpServiceFile.editor",
      ],
      [
        serviceFile("old-format", LEGACY_EXTERNAL_ID, ".service.qip.yaml"),
        "qip.serviceFile.editor",
      ],
    ];

    for (const [uri, expected] of cases) {
      const viewType = await openAndReadViewType(uri);
      assert.strictEqual(
        viewType,
        expected,
        `${uri.path.split("/").pop()} opened in ${viewType}`,
      );
      await closeAllEditors();
    }

    assert.strictEqual(
      new Set(cases.map(([, viewType]) => viewType)).size,
      cases.length,
      "two file kinds share an editor",
    );
  });

  test("an old-format project needs no user action", async function (this: Mocha.Context) {
    this.timeout(60000);

    const services = await getServices(root);
    const byId = new Map(services.map((service) => [service.id, service]));

    // The legacy name states no type, so these two come from `content.integrationSystemType`.
    assert.strictEqual(
      byId.get(LEGACY_EXTERNAL_ID)?.integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );
    assert.strictEqual(
      byId.get(LEGACY_INTERNAL_ID)?.integrationSystemType,
      IntegrationSystemType.INTERNAL,
    );
    assert.strictEqual(byId.get(LEGACY_EXTERNAL_ID)?.environments?.length, 1);
    assert.strictEqual(byId.get(LEGACY_EXTERNAL_ID)?.protocol, "http");

    // And these from the name alone.
    assert.strictEqual(
      byId.get(EXTERNAL_ID)?.integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );
    assert.strictEqual(
      byId.get(INTERNAL_ID)?.integrationSystemType,
      IntegrationSystemType.INTERNAL,
    );
    assert.strictEqual(
      byId.get(IMPLEMENTED_ID)?.integrationSystemType,
      IntegrationSystemType.IMPLEMENTED,
    );
    assert.strictEqual(
      byId.get(MIXED_TYPED_ID)?.integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );
    assert.strictEqual(
      byId.get(MIXED_LEGACY_ID)?.integrationSystemType,
      IntegrationSystemType.IMPLEMENTED,
    );
  });

  test("the explorer groups old and new names side by side", async function (this: Mocha.Context) {
    this.timeout(60000);

    const groups = serviceGroups(await readServiceGroups());

    assert.deepStrictEqual(
      [...groups.keys()],
      ["External", "Internal", "Implemented", "Context", "MCP"],
      "an empty group is rendered, or the group order moved",
    );
    assert.deepStrictEqual(groups.get("External")?.sort(), [
      EXTERNAL_ID,
      LEGACY_EXTERNAL_ID,
      MIXED_TYPED_ID,
    ]);
    assert.deepStrictEqual(groups.get("Internal")?.sort(), [
      INTERNAL_ID,
      LEGACY_INTERNAL_ID,
    ]);
    assert.deepStrictEqual(groups.get("Implemented")?.sort(), [
      IMPLEMENTED_ID,
      MIXED_LEGACY_ID,
    ]);
    assert.deepStrictEqual(groups.get("Context"), [CONTEXT_ID]);
    assert.deepStrictEqual(groups.get("MCP"), [MCP_ID]);
  });

  test("editing an old-format service converts it and keeps every field", async function (this: Mocha.Context) {
    this.timeout(120000);

    const legacyUri = serviceFile(
      "old-format",
      LEGACY_EXTERNAL_ID,
      ".service.qip.yaml",
    );
    const typedUri = serviceFile(
      "old-format",
      LEGACY_EXTERNAL_ID,
      ".external-service.qip.yaml",
    );
    const before = await readYaml(legacyUri);

    // Open the document the way a user edits it, so the panel state after the conversion is
    // observable rather than assumed.
    const viewType = await openAndReadViewType(legacyUri);
    assert.strictEqual(viewType, "qip.serviceFile.editor");

    const updated = await updateService(legacyUri, LEGACY_EXTERNAL_ID, {
      description: "Converted in the web host",
    });

    assert.ok(await exists(typedUri), "the typed file was not written");
    assert.ok(!(await exists(legacyUri)), "the legacy sibling was not deleted");
    assert.strictEqual(
      updated.integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );

    const after = await readYaml(typedUri);
    assert.strictEqual(after.id, before.id);
    assert.strictEqual(after.name, before.name);
    assert.strictEqual(after.content.description, "Converted in the web host");
    assert.strictEqual(
      after.$schema,
      "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
    );
    assert.strictEqual(
      after.content.integrationSystemType,
      undefined,
      "a typed name must not restate the type in the body",
    );
    assert.strictEqual(after.content.protocol, before.content.protocol);
    assert.strictEqual(
      after.content.activeEnvironmentId,
      before.content.activeEnvironmentId,
    );
    assert.deepStrictEqual(
      after.content.environments,
      before.content.environments,
    );
    // An older claim survives, so the backend still migrates the document through 105.
    assert.strictEqual(after.content.migrations, "[100, 101, 102, 103, 104]");

    // The backend finds a converted dotted-id service through the folder name alone, so the folder
    // must keep the id even though the file name changed.
    const folderEntries = await vscode.workspace.fs.readDirectory(
      fixture("old-format", LEGACY_EXTERNAL_ID),
    );
    assert.deepStrictEqual(
      folderEntries.map(([name]) => name),
      [`${LEGACY_EXTERNAL_ID}.external-service.qip.yaml`],
    );

    // The panel the edit came from still points at the file the conversion deleted, because VS Code
    // does not follow the rename. That is cosmetic; what matters is that the next request from that
    // panel still works, which is what the second operation below exercises.
    assert.ok(
      openTabPaths().includes(legacyUri.path),
      "the editor tab no longer points at the deleted legacy file",
    );
    assert.ok(
      !openTabPaths().includes(typedUri.path),
      "the editor tab followed the conversion after all",
    );

    // A second operation issued with the uri the panel still holds — a second save, or adding an
    // environment — used to fail on the deleted path, which left the service editor broken after
    // the first save on any old-format service.
    const secondSave = await updateService(legacyUri, LEGACY_EXTERNAL_ID, {
      description: "Saved again through the stale uri",
    });
    assert.strictEqual(
      secondSave.description,
      "Saved again through the stale uri",
    );
    assert.strictEqual(
      (await readYaml(typedUri)).content.description,
      "Saved again through the stale uri",
    );
    assert.ok(
      !(await exists(legacyUri)),
      "the second save recreated the legacy file",
    );

    const environment = await createEnvironment(legacyUri, LEGACY_EXTERNAL_ID, {
      name: "staging",
      address: "https://staging.test",
    });
    const withEnvironment = await readYaml(typedUri);
    assert.strictEqual(withEnvironment.content.environments.length, 2);
    assert.ok(
      withEnvironment.content.environments.some(
        (candidate: { id: string }) => candidate.id === environment.id,
      ),
      "the environment added through the stale uri is missing from the file",
    );
    assert.deepStrictEqual(
      (await getEnvironments(legacyUri, LEGACY_EXTERNAL_ID)).map(
        (candidate) => candidate.name,
      ),
      ["Production", "staging"],
    );

    await closeAllEditors();
  });

  test("the converted project still lists every service exactly once", async function (this: Mocha.Context) {
    this.timeout(60000);

    const services = await getServices(root);
    const ids = services.map((service) => service.id).sort();

    assert.deepStrictEqual(
      ids,
      [
        EXTERNAL_ID,
        INTERNAL_ID,
        IMPLEMENTED_ID,
        LEGACY_EXTERNAL_ID,
        LEGACY_INTERNAL_ID,
        MIXED_LEGACY_ID,
        MIXED_TYPED_ID,
      ].sort(),
    );

    // The converted service now states its type in the name and no longer in the body.
    const converted = services.find(
      (service) => service.id === LEGACY_EXTERNAL_ID,
    );
    assert.strictEqual(
      converted?.integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );
    // One from the fixture plus the one the previous test added through the stale uri: the
    // conversion carried the environments over and the second write landed on the typed file.
    assert.strictEqual(converted?.environments?.length, 2);

    const groups = serviceGroups(await readServiceGroups());
    assert.deepStrictEqual(groups.get("External")?.sort(), [
      EXTERNAL_ID,
      LEGACY_EXTERNAL_ID,
      MIXED_TYPED_ID,
    ]);
    assert.deepStrictEqual(groups.get("Internal")?.sort(), [
      INTERNAL_ID,
      LEGACY_INTERNAL_ID,
    ]);
  });

  test("a project config with its own appName still resolves the type", async function (this: Mocha.Context) {
    this.timeout(60000);

    const externalUri = serviceFile(
      "custom-config",
      ACME_EXTERNAL_ID,
      ".external-service.acme.yaml",
    );
    const internalUri = serviceFile(
      "custom-config",
      ACME_INTERNAL_ID,
      ".internal-service.acme.yaml",
    );

    // The name carries the type before any config is loaded: the app name is read off the name.
    assert.strictEqual(
      serviceTypeFromUri(externalUri),
      IntegrationSystemType.EXTERNAL,
    );
    assert.strictEqual(
      (await getService(externalUri, ACME_EXTERNAL_ID)).integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );
    assert.strictEqual(
      (await getService(internalUri, ACME_INTERNAL_ID)).integrationSystemType,
      IntegrationSystemType.INTERNAL,
    );

    const configService = ProjectConfigService.getInstance();
    try {
      await configService.loadConfigFromUri(
        fixture("custom-config", ".config.qip.yaml"),
      );

      const extensions = getExtensionsForUri(externalUri);
      assert.strictEqual(extensions.appName, "acme");
      assert.strictEqual(
        extensions.externalService,
        ".external-service.acme.yaml",
      );
      assert.strictEqual(
        resolveServiceType(
          externalUri,
          await readYaml(externalUri),
          extensions,
        ),
        IntegrationSystemType.EXTERNAL,
      );

      // A write would stamp the project's own schema URL, not the built-in one.
      assert.strictEqual(
        serviceSchemaUrlForType(
          IntegrationSystemType.EXTERNAL,
          configService.getCurrentConfig().schemaUrls,
        ),
        "http://acme.example.com/schemas/acme/external-service",
      );
    } finally {
      configService.clearCache();
    }

    // The default app is unaffected once the custom config is dropped again.
    assert.strictEqual(getExtensionsForFile().appName, "qip");
  });
});
