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
  getApiSpecifications,
  getEnvironments,
  getService,
  getServices,
  getSpecificationModel,
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
  serviceTypeFromSchema,
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

// The `from-backend` project is runtime-catalog's golden post-#553 export tree, copied in by
// `pretest:integration`. These ids are its, so they change only when the backend's capture does.
const BACKEND_EXTERNAL_ID = "svc-external";
const BACKEND_INTERNAL_ID = "svc-internal";
const BACKEND_IMPLEMENTED_ID = "svc-implemented";
const BACKEND_CONTEXT_ID = "ctx-golden";
const BACKEND_MCP_ID = "mcp-golden";

// `from-backend-dotted` is the golden `post553-dotted` set: the same exporter over an api group and
// an api whose ids carry dots, which every real export produces and no other set has.
const BACKEND_DOTTED_SERVICE_ID = "svc-observe";
const BACKEND_DOTTED_GROUP_ID = "grp-helix-observe-3.2";
const BACKEND_DOTTED_API_ID = "api-helix-observe-3.2-1.0.0";

let root: vscode.Uri;
let networkCalls: string[] = [];
let realFetch: typeof globalThis.fetch;

function fixture(...segments: string[]): vscode.Uri {
  return vscode.Uri.joinPath(root, ...segments);
}

function serviceFile(project: string, id: string, extension: string) {
  return fixture(project, id, `${id}${extension}`);
}

/**
 * The same, for the backend tree: an archive nests its services under `services/`, and its ids are
 * not the UUIDs the hand-written projects use, so `serviceFile`'s two-level layout does not reach it.
 */
function backendServiceFile(project: string, id: string, extension: string) {
  return fixture(project, "services", id, `${id}${extension}`);
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

    // The three plain types share one name and therefore one editor; the two typeless kinds keep
    // their own. The per-type entry is the leftover a #553 version wrote, still registered so an
    // unconverted file opens.
    const cases: [vscode.Uri, string][] = [
      [
        serviceFile("new-format", EXTERNAL_ID, ".service.qip.yaml"),
        "qip.serviceFile.editor",
      ],
      [
        serviceFile("new-format", INTERNAL_ID, ".service.qip.yaml"),
        "qip.serviceFile.editor",
      ],
      [
        serviceFile("new-format", IMPLEMENTED_ID, ".service.qip.yaml"),
        "qip.serviceFile.editor",
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
        serviceFile("mixed", MIXED_TYPED_ID, ".external-service.qip.yaml"),
        "qip.externalServiceFile.editor",
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
      4,
      "the plain, context, MCP and per-type editors are four distinct view types",
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

    // And these from `$schema` alone.
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
    // The acme services group by their own app's names, whatever file is current: the walk
    // resolves both maps per file, and the truncated acme uri types through the schema file name.
    assert.deepStrictEqual(
      groups.get("External")?.sort(),
      [
        EXTERNAL_ID,
        MIXED_TYPED_ID,
        BACKEND_DOTTED_SERVICE_ID,
        ACME_EXTERNAL_ID,
        LEGACY_EXTERNAL_ID,
        BACKEND_EXTERNAL_ID,
      ].sort(),
    );
    assert.deepStrictEqual(
      groups.get("Internal")?.sort(),
      [
        INTERNAL_ID,
        LEGACY_INTERNAL_ID,
        BACKEND_INTERNAL_ID,
        ACME_INTERNAL_ID,
      ].sort(),
    );
    assert.deepStrictEqual(groups.get("Implemented")?.sort(), [
      IMPLEMENTED_ID,
      MIXED_LEGACY_ID,
      BACKEND_IMPLEMENTED_ID,
    ]);
    // The backend tree's two special kinds land in their own groups rather than in `Unknown`, which
    // the group-keys assertion above would have shown as a sixth key.
    assert.deepStrictEqual(groups.get("Context")?.sort(), [
      CONTEXT_ID,
      BACKEND_CONTEXT_ID,
    ]);
    assert.deepStrictEqual(groups.get("MCP")?.sort(), [MCP_ID, BACKEND_MCP_ID]);
  });

  // A pre-#553 document: the plain `$schema` and the type in the body. The name has been the
  // current one all along, so what converts is the carrier alone — nothing moves on disk.
  test("editing a pre-#553 service converts its carrier in place", async function (this: Mocha.Context) {
    this.timeout(120000);

    const fileUri = serviceFile(
      "old-format",
      LEGACY_EXTERNAL_ID,
      ".service.qip.yaml",
    );
    const before = await readYaml(fileUri);

    const viewType = await openAndReadViewType(fileUri);
    assert.strictEqual(viewType, "qip.serviceFile.editor");

    const updated = await updateService(fileUri, LEGACY_EXTERNAL_ID, {
      description: "Converted in the web host",
    });

    assert.strictEqual(
      updated.integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );

    const after = await readYaml(fileUri);
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
      "a typed $schema must not restate the type in the body",
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

    // Nothing moved, so the folder holds exactly the one file it held before and the editor tab
    // still points at a file that exists.
    const folderEntries = await vscode.workspace.fs.readDirectory(
      fixture("old-format", LEGACY_EXTERNAL_ID),
    );
    assert.deepStrictEqual(
      folderEntries.map(([name]) => name),
      [`${LEGACY_EXTERNAL_ID}.service.qip.yaml`],
    );
    assert.ok(
      openTabPaths().includes(fileUri.path),
      "the editor tab no longer points at the file it was opened on",
    );

    const environment = await createEnvironment(fileUri, LEGACY_EXTERNAL_ID, {
      name: "staging",
      address: "https://staging.test",
    });
    const withEnvironment = await readYaml(fileUri);
    assert.strictEqual(withEnvironment.content.environments.length, 2);
    assert.ok(
      withEnvironment.content.environments.some(
        (candidate: { id: string }) => candidate.id === environment.id,
      ),
      "the environment added after the conversion is missing from the file",
    );
    assert.deepStrictEqual(
      (await getEnvironments(fileUri, LEGACY_EXTERNAL_ID)).map(
        (candidate) => candidate.name,
      ),
      ["Production", "staging"],
    );

    await closeAllEditors();
  });

  // The one rename left: a per-type name a #553 version wrote goes back to the plain one. Every
  // stale-uri path hangs off this, because the panel keeps the uri it was opened on.
  test("editing a per-type service renames it back and keeps working through the stale uri", async function (this: Mocha.Context) {
    this.timeout(120000);

    const perTypeUri = serviceFile(
      "mixed",
      MIXED_TYPED_ID,
      ".external-service.qip.yaml",
    );
    const currentUri = serviceFile(
      "mixed",
      MIXED_TYPED_ID,
      ".service.qip.yaml",
    );
    const before = await readYaml(perTypeUri);

    const viewType = await openAndReadViewType(perTypeUri);
    assert.strictEqual(viewType, "qip.externalServiceFile.editor");

    const updated = await updateService(perTypeUri, MIXED_TYPED_ID, {
      description: "Renamed back in the web host",
    });

    assert.ok(await exists(currentUri), "the current file was not written");
    assert.ok(!(await exists(perTypeUri)), "the per-type file was not deleted");
    assert.strictEqual(
      updated.integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );

    const after = await readYaml(currentUri);
    assert.strictEqual(after.id, before.id);
    assert.strictEqual(after.name, before.name);
    assert.strictEqual(after.$schema, before.$schema);
    assert.strictEqual(
      after.content.description,
      "Renamed back in the web host",
    );

    // The backend finds a dotted-id service through the folder name alone, so the folder keeps the
    // id even though the file name changed.
    const folderEntries = await vscode.workspace.fs.readDirectory(
      fixture("mixed", MIXED_TYPED_ID),
    );
    assert.deepStrictEqual(
      folderEntries.map(([name]) => name),
      [`${MIXED_TYPED_ID}.service.qip.yaml`],
    );

    // The panel the edit came from still points at the file the conversion deleted, because VS Code
    // does not follow the rename. That is cosmetic; what matters is that the next request from that
    // panel still works, which is what the operations below exercise.
    assert.ok(
      openTabPaths().includes(perTypeUri.path),
      "the editor tab no longer points at the deleted per-type file",
    );
    assert.ok(
      !openTabPaths().includes(currentUri.path),
      "the editor tab followed the conversion after all",
    );

    const secondSave = await updateService(perTypeUri, MIXED_TYPED_ID, {
      description: "Saved again through the stale uri",
    });
    assert.strictEqual(
      secondSave.description,
      "Saved again through the stale uri",
    );
    assert.strictEqual(
      (await readYaml(currentUri)).content.description,
      "Saved again through the stale uri",
    );
    assert.ok(
      !(await exists(perTypeUri)),
      "the second save recreated the per-type file",
    );

    // The api level is read through the same stale uri, and used to fail on the deleted path rather
    // than report the service having no api groups.
    assert.deepStrictEqual(
      await getApiSpecifications(perTypeUri, MIXED_TYPED_ID),
      [],
    );

    // A delete that fails leaves both files on disk for good, which the delete path swallows on
    // purpose. The list and the tree show the current one, so a read handed the per-type uri has to
    // land there too rather than on the document that lost the precedence race.
    await vscode.workspace.fs.writeFile(
      perTypeUri,
      new TextEncoder().encode(
        [
          `id: ${MIXED_TYPED_ID}`,
          "$schema: http://qubership.org/schemas/product/qip/internal-service.schema.yaml",
          "name: Mixed typed partners",
          "content:",
          "  description: superseded",
          "  protocol: HTTP",
          "",
        ].join("\n"),
      ),
    );
    try {
      const bothOnDisk = await getService(perTypeUri, MIXED_TYPED_ID);
      assert.strictEqual(
        bothOnDisk.integrationSystemType,
        IntegrationSystemType.EXTERNAL,
        "the read landed on the per-type sibling",
      );
      assert.strictEqual(
        bothOnDisk.description,
        "Saved again through the stale uri",
      );

      // A write through that same uri has to land on the same file the read did. Following the uri
      // instead applied the edit to the superseded body and wrote that over the current file, which
      // reverted every save since the conversion.
      const savedThroughPerType = await updateService(
        perTypeUri,
        MIXED_TYPED_ID,
        { name: "Mixed typed partners, renamed" },
      );
      assert.strictEqual(
        savedThroughPerType.integrationSystemType,
        IntegrationSystemType.EXTERNAL,
      );
      const currentAfterWrite = await readYaml(currentUri);
      assert.strictEqual(
        currentAfterWrite.name,
        "Mixed typed partners, renamed",
      );
      assert.strictEqual(
        currentAfterWrite.content.description,
        "Saved again through the stale uri",
        "the write carried the superseded body over the current file",
      );
      assert.strictEqual(
        (await readYaml(perTypeUri)).content.description,
        "superseded",
        "the write went through the superseded file",
      );
    } finally {
      await vscode.workspace.fs.delete(perTypeUri);
    }

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
        BACKEND_EXTERNAL_ID,
        BACKEND_INTERNAL_ID,
        BACKEND_IMPLEMENTED_ID,
        BACKEND_DOTTED_SERVICE_ID,
      ].sort(),
    );

    // The converted service now states its type in `$schema` and no longer in the body.
    const converted = services.find(
      (service) => service.id === LEGACY_EXTERNAL_ID,
    );
    assert.strictEqual(
      converted?.integrationSystemType,
      IntegrationSystemType.EXTERNAL,
    );
    // One from the fixture plus the one the conversion test added: the carrier changed and the
    // environments came through untouched.
    assert.strictEqual(converted?.environments?.length, 2);

    const groups = serviceGroups(await readServiceGroups());
    assert.deepStrictEqual(
      groups.get("External")?.sort(),
      [
        EXTERNAL_ID,
        LEGACY_EXTERNAL_ID,
        MIXED_TYPED_ID,
        BACKEND_EXTERNAL_ID,
        BACKEND_DOTTED_SERVICE_ID,
        ACME_EXTERNAL_ID,
      ].sort(),
    );
    assert.deepStrictEqual(
      groups.get("Internal")?.sort(),
      [
        INTERNAL_ID,
        LEGACY_INTERNAL_ID,
        BACKEND_INTERNAL_ID,
        ACME_INTERNAL_ID,
      ].sort(),
    );
  });

  test("a project config with its own appName still resolves the type", async function (this: Mocha.Context) {
    this.timeout(60000);

    const externalUri = serviceFile(
      "custom-config",
      ACME_EXTERNAL_ID,
      ".service.acme.yaml",
    );
    const internalUri = serviceFile(
      "custom-config",
      ACME_INTERNAL_ID,
      ".service.acme.yaml",
    );

    // The document carries the type before any config is loaded: the acme schema url is one this
    // installation configures nowhere, so only the schema file name can answer.
    assert.strictEqual(
      serviceTypeFromSchema((await readYaml(externalUri)).$schema, {
        service: "urn:none",
        externalService: "urn:none",
        internalService: "urn:none",
        implementedService: "urn:none",
        contextService: "urn:none",
        mcpService: "urn:none",
      }),
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
      assert.strictEqual(extensions.service, ".service.acme.yaml");
      assert.strictEqual(
        resolveServiceType(
          externalUri,
          await readYaml(externalUri),
          configService.getCurrentConfig().schemaUrls,
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

  // --- the backend-produced projects ---------------------------------------------------------
  //
  // Everything below reads `from-backend` and `from-backend-dotted`, which `pretest:integration`
  // copies out of runtime-catalog's golden corpus. The assertions above already cover the two
  // writers agreeing on the *rule*; these cover them agreeing on the *artifact*, which is the only
  // thing a released pair of versions has in common.

  test("every backend-written service file opens in its own custom editor", async function (this: Mocha.Context) {
    this.timeout(120000);

    const cases: [vscode.Uri, string][] = [
      [
        backendServiceFile(
          "from-backend",
          BACKEND_EXTERNAL_ID,
          ".service.qip.yaml",
        ),
        "qip.serviceFile.editor",
      ],
      [
        backendServiceFile(
          "from-backend",
          BACKEND_INTERNAL_ID,
          ".service.qip.yaml",
        ),
        "qip.serviceFile.editor",
      ],
      [
        backendServiceFile(
          "from-backend",
          BACKEND_IMPLEMENTED_ID,
          ".service.qip.yaml",
        ),
        "qip.serviceFile.editor",
      ],
      [
        backendServiceFile(
          "from-backend",
          BACKEND_CONTEXT_ID,
          ".context-service.qip.yaml",
        ),
        "qip.contextServiceFile.editor",
      ],
      [
        backendServiceFile(
          "from-backend",
          BACKEND_MCP_ID,
          ".mcp-service.qip.yaml",
        ),
        "qip.mcpServiceFile.editor",
      ],
      [
        backendServiceFile(
          "from-backend-dotted",
          BACKEND_DOTTED_SERVICE_ID,
          ".service.qip.yaml",
        ),
        "qip.serviceFile.editor",
      ],
    ];

    for (const [uri, expected] of cases) {
      assert.ok(
        await exists(uri),
        `${uri.path} is missing from the copied tree`,
      );
      const viewType = await openAndReadViewType(uri);
      assert.strictEqual(
        viewType,
        expected,
        `${uri.path.split("/").pop()} opened in ${viewType}`,
      );
      await closeAllEditors();
    }
  });

  test("a backend-written api group and api read back under their dotted ids", async function (this: Mocha.Context) {
    this.timeout(60000);

    const serviceUri = backendServiceFile(
      "from-backend-dotted",
      BACKEND_DOTTED_SERVICE_ID,
      ".service.qip.yaml",
    );

    // The file names are `<id>.api-group.qip.yaml` and `<id>.api.qip.yaml` over ids carrying dots.
    // Reading the id back means stripping the whole extension end-anchored; anchoring on the first
    // dot the way the backend does for a *service* name would answer `grp-helix-observe` here.
    const groups = await getApiSpecifications(
      serviceUri,
      BACKEND_DOTTED_SERVICE_ID,
    );
    assert.deepStrictEqual(
      groups.map((group) => group.id),
      [BACKEND_DOTTED_GROUP_ID],
    );

    const apis = await getSpecificationModel(
      serviceUri,
      BACKEND_DOTTED_SERVICE_ID,
      BACKEND_DOTTED_GROUP_ID,
    );
    assert.deepStrictEqual(
      apis.map((api) => api.id),
      [BACKEND_DOTTED_API_ID],
    );
  });

  test("re-saving a backend service leaves its file name unchanged", async function (this: Mocha.Context) {
    this.timeout(60000);

    const folder = fixture("from-backend", "services", BACKEND_EXTERNAL_ID);
    const serviceUri = backendServiceFile(
      "from-backend",
      BACKEND_EXTERNAL_ID,
      ".service.qip.yaml",
    );
    const before = (await vscode.workspace.fs.readDirectory(folder))
      .map(([name]) => name)
      .sort();

    await updateService(serviceUri, BACKEND_EXTERNAL_ID, {
      description: "Saved by the extension over a backend-written file",
    });

    // `serviceFileWrite.writeServiceInCurrentFormat` returns early when the name it computes equals
    // the current one. A rename here means the two writers disagree about what "current" is, which
    // is the whole coupling this project exists to catch — and it fails silently in production,
    // because the extension renames and the backend then discovers nothing.
    assert.deepStrictEqual(
      (await vscode.workspace.fs.readDirectory(folder))
        .map(([name]) => name)
        .sort(),
      before,
      "the extension renamed a file the backend had just written",
    );
    assert.strictEqual(
      (await readYaml(serviceUri)).content.description,
      "Saved by the extension over a backend-written file",
    );
    // The `$schema` states the type, so the write must not put the field back in the body.
    assert.strictEqual(
      (await readYaml(serviceUri)).content.integrationSystemType,
      undefined,
      "a typed $schema must not restate the type in the body",
    );
  });
});
