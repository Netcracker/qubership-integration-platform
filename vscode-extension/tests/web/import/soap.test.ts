// The SOAP specification import, driven end to end through the router over the Jest fake of
// `vscode`: the router case, `SpecificationImportApiHandler`, `SpecificationImportService`,
// `WsdlParser`, `SoapSchemaGenerator`, `SpecificationGroupService`, `EnvironmentService` and
// `SystemService`, with the real `VSCodeFileApi` writing into the fake's filesystem. The case
// asserts the files that end up on disk, byte for byte, against the golden tree in
// `src/web/test/golden/import/soap/`, which mirrors the service folder the import leaves behind and
// is what the level-3 case posts to the catalog.
//
// Determinism comes from the test. `freezeSourceIds` replaces `crypto.randomUUID` with one counter
// over all three call sites on the path: value 1 is the `importId`, which no file carries, values 2
// and 3 are the `specificationSources[].id`s in the order the files are posted, and value 4 is the
// environment id, which the service file also carries as `activeEnvironmentId`. The files are
// therefore posted **WSDL first**, since the other order moves those two ids and the golden would
// disagree with it. The clock needs no freeze: no `createdWhen` reaches a written file.
//
// The golden was generated from this fixture and then read against it by hand:
//
//   - two operations, `createOrder` and `getOrder`, in the order `wsdl:binding` declares them.
//     `WsdlParser.collectOperationsV11` takes them from the binding alone, so the `wsdl:portType`
//     entries contribute nothing
//   - both carry `method: POST` and `path: ""`: a SOAP operation has neither
//   - each request schema wraps the `wsdl:part`'s `element` — `createOrderRequest`,
//     `getOrderRequest` — and each response the matching output part. `getOrderResponse` is
//     declared `type="tns:Order"`, so its definition is a `$ref`
//   - `Type_…Order` carries `orderId` and `total` from `OrderSummary` through `xsd:complexContent`
//     as well as its own `customer` and `lines`. That is the second of the two defects Task 1 of
//     the plan found and fixed; before the fix the two inherited fields were dropped
//   - `Type_…Payment` comes out `anyOf: [{ type: string }, { type: string }]`. The `xsd:choice` arm
//     resolves each option through `buildSchemaFromElement`, which looks an inline `xsd:element` up
//     among the top-level declarations and misses, so the option names `card` and `invoiceRef` are
//     nowhere. The golden records that; it is a narrower third candidate defect, reported and not
//     fixed
//   - `shippingAddress` references `ext:AddressType` from the `.xsd` and comes out
//     `{ type: string }`: `collectSchemas` walks only `wsdl:types`, so a standalone `.xsd`
//     contributes no schema entry
//   - `specificationSources` holds both files, `mainSource: true` on `orders.wsdl` alone, each
//     `fileName` under `source-<specificationId>/`, and each `sourceHash` a real digest of the
//     source — which is why the case compares the two files under `resources/` with the fixtures
//     rather than only with the golden copies of them
//   - the service file is written twice, by `ensureSystemProtocol` and then by the environment the
//     `soap:address` produces, and only the second write is visible here: `protocol` and
//     `extendedProtocol` uppercase, and an environment named after the `wsdl:port`
import * as fs from "fs";
import * as path from "path";
import type { ExtensionContext } from "vscode";
import * as yaml from "yaml";
import { resetWorkspace, workspaceTree } from "../../fake-vscode";
import { bindFileApi } from "../../fake-vscode/fileApi";
import type { FrozenIds } from "../../helpers/freezeSourceIds";
import { freezeSourceIds } from "../../helpers/freezeSourceIds";
import { seedServiceDocument } from "../../helpers/seedServiceDocument";
import { FileCacheService } from "../../../src/web/services/FileCacheService";
import { getApiResponse } from "../../../src/web/response/apiRouter";
import type { SerializedFile } from "../../../src/web/api-services/importApiTypes";

// The seeded service and the frozen prefix are this protocol's own: the three goldens are imported
// into one catalog in parallel at level 3, so a shared source or environment id would collide.
const SERVICE_ID = "50a90000-0000-4000-8000-000000000000";
const ID_PREFIX = "50a9da7a";

const GROUP_NAME = "orders";
const GROUP_ID = `${SERVICE_ID}-${GROUP_NAME}`;
const SPECIFICATION_ID = `${GROUP_ID}-1.0.0`;
const SERVICE_DIR = `services/${SERVICE_ID}`;
const SOURCE_DIR = `${SERVICE_DIR}/resources/source-${SPECIFICATION_ID}`;

const FIXTURE_DIR = path.join(__dirname, "../../fixtures/specifications");
const GOLDEN_DIR = path.join(__dirname, "../../../src/web/test/golden/import/soap");

const WSDL = "orders.wsdl";
const XSD = "orders-types.xsd";

function fixtureText(name: string): string {
  return fs.readFileSync(path.join(FIXTURE_DIR, name), "utf8");
}

// What the webview posts: the file's bytes with its metadata, which the import turns back into a
// `File`.
function serializedFile(name: string): SerializedFile {
  const bytes = new TextEncoder().encode(fixtureText(name));
  return {
    name,
    size: bytes.byteLength,
    type: "text/xml",
    lastModified: 0,
    content: bytes.buffer as ArrayBuffer,
  };
}

// The golden directory keyed by path relative to it, which is also its path relative to the
// service folder. A walk rather than a list of names, so a golden file nobody asserts cannot be
// left behind.
function goldenTree(directory = GOLDEN_DIR, prefix = ""): Record<string, string> {
  const tree: Record<string, string> = {};
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      Object.assign(tree, goldenTree(path.join(directory, entry.name), relativePath));
    } else {
      tree[relativePath] = fs.readFileSync(path.join(directory, entry.name), "utf8");
    }
  }
  return tree;
}

describe("the SOAP import", () => {
  let frozen: FrozenIds;

  beforeEach(() => {
    resetWorkspace();
    // The cache is a singleton over module state, which `clearMocks` does not touch, so a
    // document this case seeds would otherwise be read back from the previous one.
    FileCacheService.getInstance().clearAll();
    bindFileApi();
    frozen = freezeSourceIds(ID_PREFIX);
  });

  afterEach(() => {
    frozen.restore();
  });

  test("writes the golden tree for a WSDL and its schema", async () => {
    const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });

    const result = await getApiResponse(
      {
        type: "importSpecificationGroup",
        payload: {
          systemId: SERVICE_ID,
          name: GROUP_NAME,
          files: [serializedFile(WSDL), serializedFile(XSD)],
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

    const golden = goldenTree();
    const expected: Record<string, string> = {
      services: "<Directory>",
      [SERVICE_DIR]: "<Directory>",
      [`${SERVICE_DIR}/resources`]: "<Directory>",
      [SOURCE_DIR]: "<Directory>",
    };
    for (const [relativePath, text] of Object.entries(golden)) {
      expected[`${SERVICE_DIR}/${relativePath}`] = text;
    }
    expect(workspaceTree()).toEqual(expected);

    // The two source files are copied through `file.text()` and re-encoded, so the golden copies
    // of them have to equal the fixtures: a transformation on the way would otherwise be frozen
    // into the golden and never noticed.
    expect(golden[`resources/source-${SPECIFICATION_ID}/${WSDL}`]).toBe(fixtureText(WSDL));
    expect(golden[`resources/source-${SPECIFICATION_ID}/${XSD}`]).toBe(fixtureText(XSD));

    // One structural assertion beside the bytes, so a failure names the operation that changed
    // before it names the line that did.
    const specification = yaml.parse(golden[`${SPECIFICATION_ID}.specification.cip.yaml`]);
    expect(
      specification.content.operations.map((operation: any) => [operation.name, operation.method]),
    ).toEqual([
      ["createOrder", "POST"],
      ["getOrder", "POST"],
    ]);
    expect(frozen.issued).toHaveLength(4);
  });
});
