// The AsyncAPI specification import, driven end to end through the router over the Jest fake of
// `vscode`, as `tests/web/import/soap.test.ts` drives the SOAP one: the router case,
// `SpecificationImportApiHandler`, `SpecificationImportService`, `AsyncApiSpecificationParser` with
// `AsyncApiV3Normalizer` and `AsyncApiSchemaResolver`, `SpecificationGroupService`,
// `EnvironmentService` and `SystemService`, with the real `VSCodeFileApi` writing into the fake's
// filesystem. The case asserts the files that end up on disk, byte for byte, against the golden
// tree in `src/web/test/golden/import/asyncapi/`.
//
// This protocol and OpenAPI were the two the import covered with no end-to-end case of their own.
// `detectProtocolFromParsedContent` reads `asyncapi` out of the parsed document and answers the
// protocol `detectAsyncProtocol` derives, which is KAFKA here, so the service document the import
// rewrites carries KAFKA rather than the generic ASYNC type.
//
// The fixture is AsyncAPI 3.0, which `AsyncApiV3Normalizer` rewrites into the 2.x model the rest of
// the path reads. Three facts the golden records were measured here rather than assumed:
//
//   - `method` is the 3.0 action, `send` or `receive`. The parser keeps it, where a 2.x document
//     yields `publish` or `subscribe`, and `path` is the channel's `address` rather than its key
//   - the second operation's schema is resolved: its channel message references
//     `components.messages`, whose payload references `components.schemas`, and
//     `AsyncApiSchemaResolver` follows both and expands the root reference. Both literal and
//     referenced payloads carry `definitions`, an `$id` under the catalog's schema namespace, and
//     the JSON Schema draft URL. The header of `AsyncApiSpecificationResolvers` calls the file a
//     port of the backend's `AbstractAsyncApiSpecificationResolver`, so that is where the shape
//     comes from; nothing here measured the Java side
//   - the environment comes from the server, named after the server's key and addressed
//     `kafka.orders.qip.test:9092`, which is `host` and `pathname` joined. It took #1081 to get
//     there: `extractSpecificationData` used to hand `extractEnvironmentCandidates` the **raw**
//     parsed document, where a 3.0 server carries `host` and no `url`, and both the candidate loop
//     and `extractAddressFromAsyncApiData` read `url` alone — so a 3.0 import left the service with
//     no environment where a 2.x one created it. The document is now normalized first, which is
//     also the order the catalog's own parser works in
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
const SERVICE_ID = "a5ce0000-0000-4000-8000-000000000000";
const ID_PREFIX = "a5ce7fe5";

const GROUP_NAME = "orders";
const GROUP_ID = `${SERVICE_ID}-${GROUP_NAME}`;
const SPECIFICATION_ID = `${GROUP_ID}-1.0.0`;
const SERVICE_DIR = `services/${SERVICE_ID}`;

const GOLDEN_DIR = path.join(__dirname, "../../../src/web/test/golden/import/asyncapi");

const DOCUMENT = "orders-asyncapi.yaml";

describe("the AsyncAPI import", () => {
  let frozen: FrozenIds;

  beforeEach(() => {
    frozen = resetImportWorkspace(ID_PREFIX);
  });

  afterEach(() => {
    frozen.restore();
  });

  test("writes the golden tree for an AsyncAPI document", async () => {
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
      ["publishOrderCreated", "send", "orders.created"],
      ["consumeOrderShipped", "receive", "orders.shipped"],
    ]);

    expect(
      specification.content.operations[0].responseSchemas.payload,
    ).toMatchObject({
      type: "object",
      required: ["orderId"],
      definitions: {},
      $id: "http://system.catalog/schemas/payload",
      $schema: "http://json-schema.org/draft-07/schema#",
    });
    const shipped =
      specification.content.operations[1].responseSchemas.OrderShipped;
    expect(shipped).toMatchObject({
      type: "object",
      required: ["orderId", "shippedAt"],
      properties: {
        orderId: { type: "string" },
        shippedAt: { type: "string", format: "date-time" },
      },
    });
    expect(shipped).not.toHaveProperty("$ref");

    // The protocol the detector derived from the server, which decides what the service document
    // carries and which parser the import dispatched to.
    const service = yaml.parse(tree[`${SERVICE_DIR}/${SERVICE_ID}.service.cip.yaml`]);
    expect(service.content.protocol).toBe("KAFKA");
    // The environment the 3.0 server produces, read here rather than only inside the tree
    // comparison, because it is the repair this case pins. `properties` carries the Kafka defaults
    // `EnvironmentDefaultProperties` fills in for the protocol.
    expect(service.content.environments).toHaveLength(1);
    expect(service.content.environments[0].name).toBe("production");
    expect(service.content.environments[0].address).toBe("kafka.orders.qip.test:9092");
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

    // Three ids: the `importId`, the one `specificationSources[].id` and the environment's.
    expect(frozen.issued).toHaveLength(3);
  });
});
