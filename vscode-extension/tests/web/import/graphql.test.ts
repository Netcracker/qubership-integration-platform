// The GraphQL specification import, driven end to end through the router over the Jest fake of
// `vscode`, as `tests/web/import/soap.test.ts` drives the SOAP one: the router case,
// `SpecificationImportApiHandler`, `SpecificationImportService`, `GraphQLSpecificationParser`,
// `SpecificationGroupService` and `SystemService`, with the real `VSCodeFileApi` writing into the
// fake's filesystem. The case asserts the files that end up on disk, byte for byte, against the
// golden tree in `src/web/test/golden/import/graphql/`, which mirrors the service folder the import
// leaves behind and is what the level-3 case posts to the catalog.
//
// `freezeSourceIds` makes the ids deterministic. Only two values are minted here: value 1 is the
// `importId`, which no file carries, and value 2 is the one `specificationSources[].id`. GraphQL is
// the protocol with no third value, because no environment is created.
//
// The service file is therefore where this golden differs from the other two, in two fields.
// `environments` is the empty list `ServiceNormalizer` supplied for the seed's absent key, since
// neither `extractEnvironmentCandidates` nor `buildGrpcFallbackCandidates` produces an address for
// a `.graphql` file. `activeEnvironmentId` stays the empty string the normalizer wrote, because
// `createEnvironment` is the only caller that sets it. The file is also written once rather than
// twice, by `ensureSystemProtocol` alone.
//
// The golden was generated from this fixture and then read against it by hand:
//
//   - three operations, `orders`, `order` and `createOrder`. `collectOperations` fills one array per
//     role, and `createOperationsFromGraphQL` appends every query before every mutation, so the two
//     queries come out in the order `OrderQuery` declares them and the mutation last
//   - `method` is `query` or `mutation`, and `path` is the operation name. A GraphQL operation has
//     neither an HTTP method nor a path, so the parser writes the field's role into `method`
//   - each `specification.operation` is the printed SDL of one field, description string included:
//     `orders` carries the list argument `[OrderStatus!]`, the default `limit: Int = 20` and the
//     non-null list return `[Order!]!`, so a change to how the parser prints a type shows up here
//   - the enum, the two input types and the custom scalar reach `collectObjectTypes` and
//     `collectScalars`, which skip the inputs and keep the scalar, and the `schema` definition
//     reaches `extractSchemaSDL`. None of the three results is written: `createOperationsFromGraphQL`
//     reads only `queries` and `mutations`, so the fixture's remaining declarations are covered
//     through the operations that reference them
//   - `specificationSources` holds the one posted file, `mainSource: true`, its `fileName` under
//     `source-<specificationId>/`, and a `sourceHash` that is a real digest of the source — which
//     is why the case compares the copy under `resources/` with the fixture rather than only with
//     the golden copy of it
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
  postImportGroup,
  resetImportWorkspace,
  serializedFile,
} from "../../helpers/importHarness";
import { seedServiceDocument } from "../../helpers/seedServiceDocument";
import { getApiResponse } from "../../../src/web/response/apiRouter";

// The seeded service and the frozen prefix are this protocol's own: the three goldens are imported
// into one catalog in parallel at level 3, so a shared source id would collide.
const SERVICE_ID = "9f410000-0000-4000-8000-000000000000";
const ID_PREFIX = "9f41a8b0";

const GROUP_NAME = "orders";
const GROUP_ID = `${SERVICE_ID}-${GROUP_NAME}`;
const SPECIFICATION_ID = `${GROUP_ID}-1.0.0`;
const SERVICE_DIR = `services/${SERVICE_ID}`;

const GOLDEN_DIR = path.join(__dirname, "../../../src/web/test/golden/import/graphql");

const SCHEMA = "orders.graphql";

describe("the GraphQL import", () => {
  let frozen: FrozenIds;

  beforeEach(() => {
    frozen = resetImportWorkspace(ID_PREFIX);
  });

  afterEach(() => {
    frozen.restore();
  });

  test("writes the golden tree for a schema file", async () => {
    const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });

    const result = await getApiResponse(
      {
        type: "importSpecificationGroup",
        payload: {
          systemId: SERVICE_ID,
          name: GROUP_NAME,
          files: [fixtureFile(SCHEMA)],
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
      specification.content.operations.map((operation: any) => [operation.name, operation.method]),
    ).toEqual([
      ["orders", "query"],
      ["order", "query"],
      ["createOrder", "mutation"],
    ]);

    // The two fields that set this golden's service file apart from the SOAP and gRPC ones.
    const service = yaml.parse(tree[`${SERVICE_DIR}/${SERVICE_ID}.service.cip.yaml`]);
    expect(service.content.environments).toEqual([]);
    expect(service.content.activeEnvironmentId).toBe("");

    const golden = goldenTree(GOLDEN_DIR);
    expect(tree).toEqual(expectedServiceTree(SERVICE_DIR, golden));

    // The source file is copied through `file.text()` and re-encoded, so the golden copy of it has
    // to equal the fixture: a transformation on the way would otherwise be frozen into the golden
    // and never noticed.
    expect(golden[`resources/source-${SPECIFICATION_ID}/${SCHEMA}`]).toBe(fixtureText(SCHEMA));

    expect(frozen.issued).toHaveLength(2);
  });

  // `.graphqls` is the other SDL extension. The picker has always offered it and the catalog has
  // always accepted it — `ProtocolExtractionService` reads `graphql` and `graphqls` alike — while
  // the extension's two tables, `detectProtocolByExtension` for the protocol and
  // `detectTypeByExtension` for the specification type, knew neither. The file therefore took
  // `Unsupported specification format: unable to detect protocol`, since an SDL document parses as
  // neither JSON nor YAML and nothing else detects it.
  //
  // No second golden: the bytes are the ones above, apart from the file name and the `sourceHash`
  // of a source whose text is identical. The operations are what proves the file reached
  // `GraphQLSpecificationParser` rather than a fallback.
  test("imports a .graphqls schema, the other SDL extension", async () => {
    const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });

    const result = await postImportGroup(serviceFileUri, {
      systemId: SERVICE_ID,
      name: GROUP_NAME,
      files: [serializedFile("orders.graphqls", fixtureText(SCHEMA))],
    });

    expect(result).toEqual({
      id: `${ID_PREFIX}-0000-4000-8000-000000000001`,
      specificationGroupId: GROUP_ID,
      done: true,
    });
    expect(errorMessagesShown()).toEqual([]);

    const tree = workspaceTree();
    const specification = yaml.parse(
      tree[`${SERVICE_DIR}/${SPECIFICATION_ID}.specification.cip.yaml`],
    );
    expect(specification.content.operations.map((operation: any) => operation.name)).toEqual([
      "orders",
      "order",
      "createOrder",
    ]);
    expect(Object.keys(tree)).toContain(
      `${SERVICE_DIR}/resources/source-${SPECIFICATION_ID}/orders.graphqls`,
    );
  });
});
