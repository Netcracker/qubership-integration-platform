// The gRPC specification import, driven end to end through the router over the Jest fake of
// `vscode`, as `tests/web/import/soap.test.ts` drives the SOAP one: the router case,
// `SpecificationImportApiHandler`, `SpecificationImportService`, `ProtoSpecificationParser`,
// `ProtoOperationResolver`, `ProtoTypeDefinitionBuilder`, `SpecificationGroupService`,
// `EnvironmentService` and `SystemService`, with the real `VSCodeFileApi` writing into the fake's
// filesystem. The case asserts the files that end up on disk, byte for byte, against the golden
// tree in `src/web/test/golden/import/grpc/`, which mirrors the service folder the import leaves
// behind and is what the level-3 case posts to the catalog.
//
// `freezeSourceIds` makes the ids deterministic: value 1 is the `importId`, which no file carries,
// value 2 is the one `specificationSources[].id`, and value 3 is the environment id, which the
// service file also carries as `activeEnvironmentId`.
//
// The environment is the gRPC-only part of this path. `extractSpecificationData` fails to parse a
// `.proto` as JSON or YAML and logs `Error reading file orders.proto`, so no `soap:address`-style
// candidate exists and `buildGrpcFallbackCandidates` supplies `grpc://localhost:50051` instead. It
// fires because `ensureSystemProtocol` has already set the system's protocol to `grpc`, and it
// reaches `createEnvironment` only because the seed carries no `environments` key.
//
// The golden was generated from this fixture and then read against it by hand:
//
//   - two operations, `OrderService.CreateOrder` and `OrderService.ListOrders`, in the order the
//     service declares them. The name is `<service>.<rpc>` and `method` is the bare rpc name
//   - `path` is `test.orders.grpc.OrderService`, built from the `java_package` option, while each
//     schema `$id` is built from the proto package `orders.v1`. The fixture sets the two apart so
//     that a swap between them shows up here
//   - `x-grpc.responseStream` is true for `ListOrders` alone, and no rpc streams its request
//   - each rpc's comment reaches `specification.summary`, and each message's comment reaches its
//     definition's `description`
//   - `orders.v1.Order` carries the four field shapes the fixture exists for: `lines` as an array
//     of the nested `orders.v1.Order.Line`, `status` as a `$ref` to the enum with its three values,
//     `attributes` as an object whose `additionalProperties` is the map's value type, and
//     `signature` and `total` as `$ref`s into the builtin `bytes` and `double` definitions
//   - `definitions` holds the transitive closure of each operation's root type and nothing else:
//     `CreateOrderRequest` pulls in `Order`, and `Order` pulls in the enum, the nested message and
//     the three builtins
//   - `specificationSources` holds the one posted file, `mainSource: true`, its `fileName` under
//     `source-<specificationId>/`, and a `sourceHash` that is a real digest of the source — which
//     is why the case compares the copy under `resources/` with the fixture rather than only with
//     the golden copy of it
//   - the service file is written twice, by `ensureSystemProtocol` and then by the environment, and
//     only the second write is visible here: `protocol` and `extendedProtocol` uppercase, and an
//     environment named after the specification group
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

// The seeded service and the frozen prefix are this protocol's own: the goldens are imported
// into one catalog in parallel at level 3, so a shared source or environment id would collide.
const SERVICE_ID = "67c00000-0000-4000-8000-000000000000";
const ID_PREFIX = "67c09ec0";

const GROUP_NAME = "orders";
const GROUP_ID = `${SERVICE_ID}-${GROUP_NAME}`;
const SPECIFICATION_ID = `${GROUP_ID}-1.0.0`;
const SERVICE_DIR = `services/${SERVICE_ID}`;

const GOLDEN_DIR = path.join(__dirname, "../../../src/web/test/golden/import/grpc");

const PROTO = "orders.proto";

describe("the gRPC import", () => {
  let frozen: FrozenIds;

  beforeEach(() => {
    frozen = resetImportWorkspace(ID_PREFIX);
  });

  afterEach(() => {
    frozen.restore();
  });

  test("writes the golden tree for a proto file", async () => {
    const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });

    const result = await getApiResponse(
      {
        type: "importSpecificationGroup",
        payload: {
          systemId: SERVICE_ID,
          name: GROUP_NAME,
          files: [fixtureFile(PROTO)],
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
    // A fully successful import shows the user nothing. This path logs a swallowed parse failure on
    // the way to the fallback environment, so a regression that promotes it to a dialog changes no
    // byte on disk.
    expect(errorMessagesShown()).toEqual([]);

    // The written documents, read ahead of the byte comparison: a changed operation or a lost
    // environment then fails by name rather than inside a whole-tree diff. The streaming flag only
    // the `x-grpc` extension carries is the second structural assertion.
    const tree = workspaceTree();
    const specification = yaml.parse(
      tree[`${SERVICE_DIR}/${SPECIFICATION_ID}.specification.cip.yaml`],
    );
    expect(
      specification.content.operations.map((operation: any) => [operation.name, operation.method]),
    ).toEqual([
      ["OrderService.CreateOrder", "CreateOrder"],
      ["OrderService.ListOrders", "ListOrders"],
    ]);
    expect(
      specification.content.operations.map(
        (operation: any) => operation.specification["x-grpc"].responseStream,
      ),
    ).toEqual([false, true]);

    // The environment the gRPC fallback created, which no other protocol in this plan produces
    // from a missing address.
    const service = yaml.parse(tree[`${SERVICE_DIR}/${SERVICE_ID}.service.cip.yaml`]);
    expect(service.content.environments).toHaveLength(1);
    expect(service.content.environments[0].address).toBe("grpc://localhost:50051");
    expect(service.content.activeEnvironmentId).toBe(service.content.environments[0].id);

    const golden = goldenTree(GOLDEN_DIR);
    expect(tree).toEqual(expectedServiceTree(SERVICE_DIR, golden));

    // The source file is copied through `file.text()` and re-encoded, so the golden copy of it has
    // to equal the fixture: a transformation on the way would otherwise be frozen into the golden
    // and never noticed.
    expect(golden[`resources/source-${SPECIFICATION_ID}/${PROTO}`]).toBe(fixtureText(PROTO));

    expect(frozen.issued).toHaveLength(3);
  });
});
