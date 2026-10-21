// The OpenAPI specification import, driven end to end through the router over the Jest fake of
// `vscode`, as `tests/web/import/soap.test.ts` drives the SOAP one: the router case,
// `SpecificationImportApiHandler`, `SpecificationImportService`, `OpenApiSpecificationParser`,
// `CipSpecificationGenerator`, `SpecificationGroupService`, `EnvironmentService` and
// `SystemService`, with the real `VSCodeFileApi` writing into the fake's filesystem. The case
// asserts the files that end up on disk, byte for byte, against the golden tree in
// `src/web/test/golden/import/openapi/`.
//
// This protocol and AsyncAPI were the two the import covered with no end-to-end case of their own:
// `detectProtocolFromParsedContent` returns HTTP for a document carrying `openapi` or `swagger`,
// and `SpecificationProcessorService` dispatches it to `OpenApiSpecificationParser`. The fixture is
// a `.yaml` file, so the protocol comes from the parsed content rather than from an extension
// table — the only route by which an OpenAPI document is detected at all.
//
// `freezeSourceIds` makes the ids deterministic, and this path mints three for one file: the
// `importId`, which no file carries, the `specificationSources[].id`, and the environment id, which
// the service file also carries as `activeEnvironmentId`. The environment is created because the
// document declares a `servers` entry, which `extractOpenApiServers` turns into a candidate named
// after the server's `description`.
//
// Until #1081 the count was four: `createOperationsFromOpenApi` took the whole specification
// `createCipSpecificationFromOpenApi` builds, read `content.operations` off it and threw the rest
// away, including a `specificationSources[].id` that reached no file. It now calls
// `buildOperationsFromOpenApi`, and the count is what the written document carries.
//
// The golden was generated from this fixture and then read against it by hand:
//
//   - three operations, in the order the document declares them: `listOrders` and `createOrder`
//     under `/orders`, then `getOrder` under `/orders/{orderId}`. `createCipSpecificationFromOpenApi`
//     walks `paths` and then each path item's methods, keeping the document's own order
//   - `name` is the `operationId`, `method` is the method uppercased and `path` is the template as
//     written. No operation here omits `operationId`, so `generateOperationId` is not reached
//   - `specification` is the operation object with its fields reordered by
//     `reorderSpecificationFields`, which puts `summary` and `responses` ahead of `operationId` and
//     wraps each parameter through `processParameter`
//   - `requestSchema` is the parameter list for an operation that declares no body, and a resolved
//     draft-07 document keyed by media type for one that does. Each entry of `responseSchemas` is
//     such a document too: a `$ref` into `components.schemas` resolves, carrying the `$id` the
//     catalog's schema namespace uses, and an array response keeps the reference as
//     `#/definitions/Order` with the definition beside it. The references inside `specification`
//     are left as written, because that field is the operation object itself
//   - the environment is named after the server entry and carries
//     `http://orders.qip.test/api/v1`, and the service file is written twice: once by
//     `ensureSystemProtocol` and once for the environment, of which only the second is visible here
import * as path from "path";
import type { ExtensionContext } from "vscode";
import * as yaml from "yaml";
import { workspaceTree } from "../../fake-vscode";
import type { FrozenIds } from "../../helpers/freezeSourceIds";
import {
  errorMessagesShown,
  expectedServiceTree,
  fixtureFile,
  fixtureText,
  goldenTree,
  resetImportWorkspace,
} from "../../helpers/importHarness";
import { seedServiceDocument } from "../../helpers/seedServiceDocument";
import { getApiResponse } from "../../../src/web/response/apiRouter";

// The seeded service and the frozen prefix are this protocol's own: the goldens are imported into
// one catalog in parallel at level 3, so a shared source or environment id would collide.
const SERVICE_ID = "0ae10000-0000-4000-8000-000000000000";
const ID_PREFIX = "0ae1d0c5";

const GROUP_NAME = "orders";
const GROUP_ID = `${SERVICE_ID}-${GROUP_NAME}`;
const SPECIFICATION_ID = `${GROUP_ID}-1.0.0`;
const SERVICE_DIR = `services/${SERVICE_ID}`;

const GOLDEN_DIR = path.join(__dirname, "../../../src/web/test/golden/import/openapi");

const DOCUMENT = "orders-openapi.yaml";

describe("the OpenAPI import", () => {
  let frozen: FrozenIds;

  beforeEach(() => {
    frozen = resetImportWorkspace(ID_PREFIX);
  });

  afterEach(() => {
    frozen.restore();
  });

  test("writes the golden tree for an OpenAPI document", async () => {
    const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });

    const result = await getApiResponse(
      {
        type: "importSpecificationGroup",
        payload: {
          systemId: SERVICE_ID,
          name: GROUP_NAME,
          files: [fixtureFile(DOCUMENT, "application/yaml")],
        },
      } as any,
      serviceFileUri,
      {} as ExtensionContext,
    );

    expect(result).toEqual({
      id: `${ID_PREFIX}-0000-4000-8000-000000000001`,
      specificationGroupId: GROUP_ID,
      done: true,
    });
    // A fully successful import shows the user nothing, so a regression that turns a logged failure
    // into a dialog changes no byte on disk and would otherwise pass.
    expect(errorMessagesShown()).toEqual([]);

    // The written documents, read ahead of the byte comparison: a changed operation then fails here,
    // naming the operation, rather than inside a whole-tree diff.
    const tree = workspaceTree();
    const specification = yaml.parse(
      tree[`${SERVICE_DIR}/${SPECIFICATION_ID}.specification.cip.yaml`],
    );
    expect(
      specification.content.operations.map((operation: any) => [
        operation.name,
        operation.method,
        operation.path,
      ]),
    ).toEqual([
      ["listOrders", "GET", "/orders"],
      ["createOrder", "POST", "/orders"],
      ["getOrder", "GET", "/orders/{orderId}"],
    ]);

    // The environment the `servers` entry produced, which is what sets this golden apart from the
    // GraphQL one.
    const service = yaml.parse(tree[`${SERVICE_DIR}/${SERVICE_ID}.service.cip.yaml`]);
    expect(service.content.environments).toHaveLength(1);
    expect(service.content.environments[0].address).toBe("http://orders.qip.test/api/v1");
    expect(service.content.environments[0].name).toBe("Orders test stand");
    expect(service.content.activeEnvironmentId).toBe(
      `${ID_PREFIX}-0000-4000-8000-000000000003`,
    );

    const golden = goldenTree(GOLDEN_DIR);
    expect(tree).toEqual(expectedServiceTree(SERVICE_DIR, golden));

    // The source file is copied through `file.text()` and re-encoded, so the golden copy of it has
    // to equal the fixture: a transformation on the way would otherwise be frozen into the golden
    // and never noticed.
    expect(golden[`resources/source-${SPECIFICATION_ID}/${DOCUMENT}`]).toBe(
      fixtureText(DOCUMENT),
    );

    expect(frozen.issued).toHaveLength(3);
  });
});
