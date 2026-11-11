// The read paths of `serviceApiRead.ts` against the Jest fake of `vscode` from `tests/fake-vscode/`:
// the documents of `src/web/test/workspace/` are read from disk and seeded into the fake's
// filesystem, so each case drives the real `VSCodeFileApi` over a real file tree.
//
// The two other `serviceApiRead` suites in this directory pass their own factory to
// `jest.mock("vscode", …)` and assert which calls a function made. These cases go through
// `moduleNameMapper` instead and assert what the functions read back, which is what covers the
// lookup, recovery and skip-on-parse-failure branches.
//
// `serviceApiRead.ts:13` reads `fileApi` from the delegating facade in `fileApiProvider`, whose
// default backing object throws `FileApi not configured`, so every case bridges the two with
// `setFileApi`.
import * as fs from "fs";
import * as path from "path";
import { resetWorkspace, seedWorkspace, workspaceUri } from "../fake-vscode";
import { bindFileApi } from "../fake-vscode/fileApi";
import { FileCacheService } from "../../src/web/services/FileCacheService";
import * as read from "../../src/web/response/serviceApiRead";

const FIXTURE_ROOT = path.join(__dirname, "../../src/web/test/workspace");

const CHAIN_ID = "1419390b-7423-4fab-853f-67c653e16a86";
const SERVICE_ID = "3b2e9742-139c-4750-bb76-adaf1cc9e3f0";
const CONTEXT_ID = "269327ed-e44d-491e-9f86-4a3010d63108";
const MCP_ID = "0edab1e2-c311-4718-8411-adb16bfa5d54";
const GROUP_ID = `${SERVICE_ID}-orders`;
const SPEC_ID = `${GROUP_ID}-1.0.0`;
const OPERATION_ID = `${SPEC_ID}-createOrder`;
const ENVIRONMENT_ID = "718316bc-0733-4a43-b6bb-95ac7c242df5";

const CHAIN_FILE = `chains/${CHAIN_ID}/${CHAIN_ID}.chain.cip.yaml`;
const SERVICE_FILE = `services/${SERVICE_ID}/${SERVICE_ID}.service.cip.yaml`;
const GROUP_FILE = `services/${SERVICE_ID}/${GROUP_ID}.specification-group.cip.yaml`;
const SPEC_FILE = `services/${SERVICE_ID}/${SPEC_ID}.specification.cip.yaml`;
const CONTEXT_FILE = `services/${CONTEXT_ID}/${CONTEXT_ID}.context-service.cip.yaml`;
const MCP_FILE = `services/${MCP_ID}/${MCP_ID}.mcp-service.cip.yaml`;

// Enough of the fixture workspace to reach every read path: a chain, an INTERNAL service with one
// specification group and one specification, and the context and MCP services.
const FIXTURE_FILES = [
  CHAIN_FILE,
  SERVICE_FILE,
  GROUP_FILE,
  SPEC_FILE,
  CONTEXT_FILE,
  MCP_FILE,
];

// `parseStructuredContent` tries JSON and then YAML, and an unterminated flow sequence fails both.
const UNPARSEABLE = "name: [\n";

// `readDirectory` answers the names in the order they were written, and the readers that look for
// one document return on the first match, so a corrupted file only stands in front of the fixture's
// if it is seeded first. `seedBrokenSpecificationFirst` reseeds the workspace to put it there.
const BROKEN_SPEC_FILE = `services/${SERVICE_ID}/0-broken.specification.cip.yaml`;

// A chain whose element points at the fixture specification, which is how the readers fill the
// `chains` field of a group, a specification and an operation.
const CALLER_CHAIN_ID = "8cf1a6b2-5f30-4de6-9a1b-6b1c2f4e7d90";
const CALLER_CHAIN = {
  [`chains/${CALLER_CHAIN_ID}/${CALLER_CHAIN_ID}.chain.cip.yaml`]:
    `id: ${CALLER_CHAIN_ID}\nname: orders-caller\ncontent:\n  elements:\n` +
    `  - id: 5b7d3c21-9e84-4f52-8a06-1d2e3f4a5b6c\n    properties:\n` +
    `      integrationSystemId: ${SERVICE_ID}\n` +
    `      integrationSpecificationGroupId: ${GROUP_ID}\n` +
    `      integrationSpecificationId: ${SPEC_ID}\n` +
    `      integrationOperationId: ${OPERATION_ID}\n`,
};

function seedFixtureWorkspace(): void {
  const files: Record<string, string> = {};
  for (const relativePath of FIXTURE_FILES) {
    files[relativePath] = fs.readFileSync(path.join(FIXTURE_ROOT, relativePath), "utf8");
  }
  seedWorkspace(files);
}

// The file API caches a uri per id, and the cache is module state that `clearMocks` does not reach,
// so each case starts from an empty one as it starts from an empty filesystem.
function bindFreshFileApi(): void {
  FileCacheService.getInstance().clearAll();
  bindFileApi();
}

// Starts the workspace over with the corrupted specification ahead of the fixture's, so a reader
// that returns on the first match reaches the corrupted one first.
function seedBrokenSpecificationFirst(): void {
  resetWorkspace();
  seedWorkspace({ [BROKEN_SPEC_FILE]: UNPARSEABLE });
  seedFixtureWorkspace();
  bindFreshFileApi();
}

describe("the service read paths over the workspace fixtures", () => {
  beforeEach(() => {
    resetWorkspace();
    seedFixtureWorkspace();
    bindFreshFileApi();
  });

  test("reads the service id out of the file it is given", async () => {
    await expect(read.getCurrentServiceId(workspaceUri(SERVICE_FILE))).resolves.toBe(SERVICE_ID);
  });

  test("rejects the service id of a file that does not parse", async () => {
    seedWorkspace({ "services/broken/broken.service.cip.yaml": UNPARSEABLE });

    await expect(
      read.getCurrentServiceId(workspaceUri("services/broken/broken.service.cip.yaml")),
    ).rejects.toThrow(/Failed to parse content/);
  });

  test("answers the main service file uri with the uri it was given", async () => {
    const fileUri = workspaceUri(SERVICE_FILE);

    await expect(read.getMainServiceFileUri(fileUri)).resolves.toBe(fileUri);
  });

  test("finds the service by id when the open file is a chain", async () => {
    await expect(read.getService(workspaceUri(CHAIN_FILE), SERVICE_ID)).resolves.toMatchObject({
      id: SERVICE_ID,
      name: "e2e-service-roundtrip-internal",
      type: "INTERNAL",
      protocol: "http",
    });
  });

  test("rejects a service id no file in the workspace carries", async () => {
    await expect(read.getService(workspaceUri(CHAIN_FILE), "9f1c0e2a-absent")).rejects.toThrow(
      /Unable to find file with extension/,
    );
  });

  test("lists the open context service without searching", async () => {
    await expect(read.getContextServices(workspaceUri(CONTEXT_FILE))).resolves.toEqual([
      {
        id: CONTEXT_ID,
        name: "vsc-fixture-context",
        description: "Context service for the extension integration tests",
        type: "CONTEXT",
        labels: [],
      },
    ]);
  });

  test("searches the workspace for context services when the open file is a chain", async () => {
    const services = await read.getContextServices(workspaceUri(CHAIN_FILE));

    expect(services.map((service) => service.id)).toEqual([CONTEXT_ID]);
  });

  test("lists the open MCP service without searching", async () => {
    await expect(read.getMcpServices(workspaceUri(MCP_FILE))).resolves.toEqual([
      {
        id: MCP_ID,
        name: "vsc-fixture-mcp",
        description: "MCP service for the extension integration tests",
        instructions: "Answer with the fixture data.",
        identifier: "vscFixtureMcp",
        labels: [],
      },
    ]);
  });

  test("searches the workspace for MCP services when the open file is a chain", async () => {
    const services = await read.getMcpServices(workspaceUri(CHAIN_FILE));

    expect(services.map((service) => service.id)).toEqual([MCP_ID]);
  });

  test("reads the MCP service the file holds", async () => {
    await expect(read.getMcpService(workspaceUri(MCP_FILE), MCP_ID)).resolves.toMatchObject({
      id: MCP_ID,
      identifier: "vscFixtureMcp",
    });
  });

  test("rejects an MCP service id the file does not hold", async () => {
    await expect(read.getMcpService(workspaceUri(MCP_FILE), CONTEXT_ID)).rejects.toThrow(
      /service ID mismatch/,
    );
  });

  test("reads one environment of the service", async () => {
    await expect(
      read.getEnvironment(workspaceUri(SERVICE_FILE), SERVICE_ID, ENVIRONMENT_ID),
    ).resolves.toMatchObject({
      id: ENVIRONMENT_ID,
      name: "internal",
      address: "http://e2e-internal.invalid:8080",
      sourceType: "MANUAL",
    });
  });

  test("finds the service by id before reading an environment", async () => {
    await expect(
      read.getEnvironment(workspaceUri(CHAIN_FILE), SERVICE_ID, ENVIRONMENT_ID),
    ).resolves.toMatchObject({ id: ENVIRONMENT_ID, name: "internal" });
  });

  test("rejects an environment id the service does not carry", async () => {
    await expect(
      read.getEnvironment(workspaceUri(SERVICE_FILE), SERVICE_ID, "4d9b1f0c-absent"),
    ).rejects.toThrow(/Unable to find environment with id = 4d9b1f0c-absent/);
  });

  test("lists the environments of the service", async () => {
    const environments = await read.getEnvironments(workspaceUri(SERVICE_FILE), SERVICE_ID);

    expect(environments).toHaveLength(1);
    expect(environments[0]).toMatchObject({ id: ENVIRONMENT_ID, properties: { soTimeout: "120000" } });
  });

  test("finds the service by id before listing its environments", async () => {
    const environments = await read.getEnvironments(workspaceUri(CHAIN_FILE), SERVICE_ID);

    expect(environments.map((environment) => environment.id)).toEqual([ENVIRONMENT_ID]);
  });

  test("rejects the environments of a service id no file carries", async () => {
    await expect(read.getEnvironments(workspaceUri(CHAIN_FILE), "9f1c0e2a-absent")).rejects.toThrow(
      /Unable to find file with extension/,
    );
  });

  test("builds the specification group of the service", async () => {
    const groups = await read.getApiSpecifications(workspaceUri(SERVICE_FILE), SERVICE_ID);

    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({
      id: GROUP_ID,
      name: "orders",
      systemId: SERVICE_ID,
      synchronization: false,
      chains: [],
    });
    expect(groups[0].specifications.map((spec) => spec.id)).toEqual([SPEC_ID]);
  });

  test("finds the service by id before building its specification groups", async () => {
    const groups = await read.getApiSpecifications(workspaceUri(CHAIN_FILE), SERVICE_ID);

    expect(groups.map((group) => group.id)).toEqual([GROUP_ID]);
  });

  test("rejects specification groups asked for under another service id", async () => {
    await expect(read.getApiSpecifications(workspaceUri(SERVICE_FILE), MCP_ID)).rejects.toThrow(
      /ServiceId mismatch/,
    );
  });

  test("skips a specification group file that does not parse", async () => {
    seedWorkspace({
      [`services/${SERVICE_ID}/broken.specification-group.cip.yaml`]: UNPARSEABLE,
    });

    const groups = await read.getApiSpecifications(workspaceUri(SERVICE_FILE), SERVICE_ID);

    expect(groups.map((group) => group.id)).toEqual([GROUP_ID]);
  });

  test("reports the chain that uses the group, the specification and the operation", async () => {
    seedWorkspace(CALLER_CHAIN);
    const caller = { id: CALLER_CHAIN_ID, name: "orders-caller" };

    // `SpecificationGroup` and `Specification` declare no `chains` field, although both readers
    // fill one, so the case reads it structurally.
    const [group] = (await read.getApiSpecifications(
      workspaceUri(SERVICE_FILE),
      SERVICE_ID,
    )) as any[];

    expect(group.chains).toEqual([caller]);
    expect(group.specifications[0].chains).toEqual([caller]);
    expect(group.specifications[0].operations[0].chains).toEqual([caller]);
  });

  test("picks the latest specification of the service", async () => {
    await expect(
      read.getLatestApiSpecification(workspaceUri(SERVICE_FILE), SERVICE_ID),
    ).resolves.toMatchObject({ id: SPEC_ID, version: "1.0.0" });
  });

  test("builds the specification model of the group", async () => {
    const specifications = await read.getSpecificationModel(
      workspaceUri(SERVICE_FILE),
      SERVICE_ID,
      GROUP_ID,
    );

    expect(specifications).toHaveLength(1);
    expect(specifications[0]).toMatchObject({
      id: SPEC_ID,
      name: "1.0.0",
      version: "1.0.0",
      deprecated: false,
      specificationGroupId: GROUP_ID,
      systemId: SERVICE_ID,
      chains: [],
    });
    expect(specifications[0].createdWhen).toBeGreaterThan(0);
    expect(specifications[0].operations).toMatchObject([
      { id: OPERATION_ID, name: "createOrder", method: "POST", path: "/orders" },
    ]);
  });

  test("skips a specification file that does not parse while building the model", async () => {
    seedBrokenSpecificationFirst();

    const specifications = await read.getSpecificationModel(
      workspaceUri(SERVICE_FILE),
      SERVICE_ID,
      GROUP_ID,
    );

    expect(specifications.map((spec) => spec.id)).toEqual([SPEC_ID]);
  });

  test("finds the service by id before building its specification model", async () => {
    const specifications = await read.getSpecificationModel(
      workspaceUri(CHAIN_FILE),
      SERVICE_ID,
      GROUP_ID,
    );

    expect(specifications.map((spec) => spec.id)).toEqual([SPEC_ID]);
  });

  test("falls back to the open file when the specification model names an absent service", async () => {
    await expect(
      read.getSpecificationModel(workspaceUri(CHAIN_FILE), "9f1c0e2a-absent", GROUP_ID),
    ).resolves.toEqual([]);
  });

  test("reads the operations of the specification", async () => {
    await expect(read.getOperations(workspaceUri(SERVICE_FILE), SPEC_ID)).resolves.toMatchObject([
      {
        id: OPERATION_ID,
        name: "createOrder",
        method: "POST",
        path: "/orders",
        modelId: SPEC_ID,
        chains: [],
      },
    ]);
  });

  test("finds the service by the model id prefix before reading its operations", async () => {
    const operations = await read.getOperations(workspaceUri(CHAIN_FILE), SPEC_ID);

    expect(operations.map((operation) => operation.id)).toEqual([OPERATION_ID]);
  });

  test("skips a specification file that does not parse while reading the operations", async () => {
    seedBrokenSpecificationFirst();

    const operations = await read.getOperations(workspaceUri(SERVICE_FILE), SPEC_ID);

    expect(operations.map((operation) => operation.id)).toEqual([OPERATION_ID]);
  });

  test("answers no operations for a model id the service does not hold", async () => {
    await expect(read.getOperations(workspaceUri(SERVICE_FILE), "absent")).resolves.toEqual([]);
  });

  test("rejects operations for a model id that names neither a service nor a specification", async () => {
    await expect(
      read.getOperations(workspaceUri(CHAIN_FILE), "9f1c0e2a-absent-0000-0000-000000000000"),
    ).rejects.toThrow(/Unable to find file with extension/);
  });

  test("reads the schemas of one operation", async () => {
    const info = await read.getOperationInfo(workspaceUri(SERVICE_FILE), OPERATION_ID);

    expect(info.id).toBe(OPERATION_ID);
    expect(Object.keys(info.requestSchema)).toEqual(["application/xml", "application/json"]);
    expect(Object.keys(info.responseSchemas)).toEqual(["201", "422"]);
    expect(info.specification).toMatchObject({ operationId: "createOrder" });
  });

  test("finds the service by the operation id prefix before reading its schemas", async () => {
    await expect(
      read.getOperationInfo(workspaceUri(CHAIN_FILE), OPERATION_ID),
    ).resolves.toMatchObject({ id: OPERATION_ID });
  });

  test("rejects an operation id no specification beside the open file carries", async () => {
    await expect(
      read.getOperationInfo(workspaceUri(CHAIN_FILE), "9f1c0e2a-absent-0000-0000-000000000000"),
    ).rejects.toThrow(/Operation with id 9f1c0e2a-absent-0000-0000-000000000000 not found/);
  });

  test("skips a specification file that does not parse while reading an operation", async () => {
    seedBrokenSpecificationFirst();

    await expect(
      read.getOperationInfo(workspaceUri(SERVICE_FILE), OPERATION_ID),
    ).resolves.toMatchObject({ id: OPERATION_ID });
  });

  test("lists the open service without searching", async () => {
    const services = await read.getServices(workspaceUri(SERVICE_FILE));

    expect(services.map((service) => service.id)).toEqual([SERVICE_ID]);
  });

  test("searches the workspace for services when the open file is a chain", async () => {
    const services = await read.getServices(workspaceUri(CHAIN_FILE));

    expect(services.map((service) => service.id)).toEqual([SERVICE_ID]);
  });
});

// The fixture workspace keeps every specification beside the service that owns it, and the service
// id is the first five dash-separated parts of a specification id. Two read paths only run where
// neither holds, so they get a workspace of their own.
const MINIMAL_WORKSPACE = {
  "chains/ch-1/ch-1.chain.cip.yaml": "id: ch-1\nname: minimal chain\ncontent:\n  elements: []\n",
  "services/svc-1/svc-1.service.cip.yaml":
    "id: svc-1\nname: minimal service\ncontent:\n  protocol: http\n",
  "specifications/spec-1.specification.cip.yaml":
    "id: spec-1\nname: ping-api\ncontent:\n  parentId: grp-1\n  operations:\n" +
    "  - id: op-1\n    name: ping\n    method: GET\n    path: /ping\n",
  "specifications/spec-2.specification.cip.yaml": "id: spec-2\nname: no-content-api\n",
};

describe("the service read paths where a specification stands on its own", () => {
  beforeEach(() => {
    resetWorkspace();
    seedWorkspace(MINIMAL_WORKSPACE);
    bindFreshFileApi();
  });

  test("finds the specification by its model id when the id carries no service prefix", async () => {
    await expect(
      read.getOperations(workspaceUri("chains/ch-1/ch-1.chain.cip.yaml"), "spec-1"),
    ).resolves.toMatchObject([
      { id: "op-1", name: "ping", method: "GET", path: "/ping", modelId: "spec-1", chains: [] },
    ]);
  });

  test("answers no operations for a specification file that carries no content", async () => {
    await expect(
      read.getOperations(workspaceUri("chains/ch-1/ch-1.chain.cip.yaml"), "spec-2"),
    ).resolves.toEqual([]);
  });

  test("answers no latest specification for a service that has no group", async () => {
    await expect(
      read.getLatestApiSpecification(workspaceUri("services/svc-1/svc-1.service.cip.yaml"), "svc-1"),
    ).resolves.toBeUndefined();
  });
});
