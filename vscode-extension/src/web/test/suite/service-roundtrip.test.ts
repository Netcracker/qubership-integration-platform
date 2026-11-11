// A change made through the extension's API, saved back over each of the three workspace services.
//
// A save reformats: the extension writes `yaml.stringify` of the parsed document, which drops the
// `---` marker and the scalar quotes a catalog export carries. So a golden under `../golden/` is the
// reformatted output rather than a copy of its fixture, and each case makes the two claims that hold
// of a save: the saved bytes equal the golden, and the parsed document equals the fixture with the
// name change applied.
//
// The context and MCP goldens carry an import case in `e2e/specs/api/extension-output.spec.ts` as
// well. The INTERNAL golden is checked here only, because
// `e2e/specs/api/service-roundtrip.spec.ts` owns `3b2e9742-139c-4750-bb76-adaf1cc9e3f0` in the
// parallel-safe `api` project: a one-document service imported under that id would race that case's
// own import, and each teardown would delete the other's subject.
import * as assert from "assert";
import * as vscode from "vscode";
import * as yaml from "yaml";
import {
  FIXTURES,
  SUITE_TIMEOUT,
  activateExtension,
  callRouter,
  copyFolder,
  fileNameOf,
  folderOf,
  golden,
  readText,
  readYaml,
  useExtensionModules,
  workspaceUri,
} from "./harness";

const NEW_NAME = "vsc-round-trip-service";

interface ServiceCase {
  kind: string;
  fixture: string;
  /** The message the webview sends to change the name, with the payload `apiRouter.ts` reads. */
  request: (id: string) => { type: string; payload: unknown };
  /** The document the save leaves on disk, parsed. */
  expected: (fixture: any) => unknown;
}

const CASES: ServiceCase[] = [
  {
    kind: "INTERNAL",
    fixture: FIXTURES.service,
    request: (id) => ({ type: "updateService", payload: { id, service: { name: NEW_NAME } } }),
    // This path reads through `getMainService`, which normalizes with `ServiceNormalizer`: it fills
    // the fields the fixture omits, and the save writes them back.
    expected: (fixture) => ({
      ...fixture,
      name: NEW_NAME,
      content: {
        ...fixture.content,
        description: "",
        extendedProtocol: "",
        specification: "",
        labels: [],
        environments: fixture.content.environments.map((environment: any) => ({
          ...environment,
          description: "",
        })),
      },
    }),
  },
  {
    kind: "context",
    fixture: FIXTURES.contextService,
    request: (id) => ({ type: "updateContextService", payload: { id, service: { name: NEW_NAME } } }),
    expected: (fixture) => ({ ...fixture, name: NEW_NAME }),
  },
  {
    kind: "MCP",
    fixture: FIXTURES.mcpService,
    // `updateMcpService` reads its request under `request`, where the other two read `service`.
    request: (id) => ({ type: "updateMcpService", payload: { id, request: { name: NEW_NAME } } }),
    expected: (fixture) => ({ ...fixture, name: NEW_NAME }),
  },
];

suite("Service file round trip", function () {
  this.timeout(SUITE_TIMEOUT);

  suiteSetup(async () => {
    await activateExtension();
    useExtensionModules();
  });

  for (const each of CASES) {
    const fileName = fileNameOf(each.fixture);
    const copy = `services/roundtrip-${each.kind.toLowerCase()}`;

    test(`a change through the extension's API saves the ${each.kind} service as its golden`, async () => {
      const fileUri = workspaceUri(`${copy}/${fileName}`);
      // The copy is made inside the `try`, so a failure between it and the first assertion still
      // leaves the teardown to remove it: the explorer cases in file-roundtrip.test.ts assert the
      // exact service list and would pick up the residue.
      try {
        await copyFolder(folderOf(each.fixture), copy);
        const fixture = await readYaml(workspaceUri(each.fixture));
        const { type, payload } = each.request(fixture.id);
        await callRouter(type, payload, fileUri);

        const saved = await readText(fileUri);
        assert.strictEqual(
          saved,
          golden(fileName),
          `the saved ${each.kind} service differs from src/web/test/golden/${fileName}`,
        );
        assert.deepStrictEqual(yaml.parse(saved), each.expected(fixture));
      } finally {
        await vscode.workspace.fs.delete(workspaceUri(copy), { recursive: true });
      }
    });
  }
});
