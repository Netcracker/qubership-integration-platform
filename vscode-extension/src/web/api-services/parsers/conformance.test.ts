jest.mock("vscode", () => ({ Uri: class Uri {} }), { virtual: true });
jest.mock("../../response/file/fileApiProvider", () => ({
  fileApi: {},
  setFileApi: jest.fn(),
}));

import * as fs from "fs";
import * as path from "path";
import { OpenApiSpecificationParser } from "./OpenApiSpecificationParser";
import { AsyncApiSpecificationParser } from "./AsyncApiSpecificationParser";
import { ProtoSpecificationParser } from "./ProtoSpecificationParser";
import { GraphQLSpecificationParser } from "./GraphQLSpecificationParser";
import { SoapSpecificationParser } from "./SoapSpecificationParser";
import {
  TypedOperation,
  deriveMethod,
  derivePath,
} from "./deriveTypedMethodPath";

// The shared parity oracle lives in the schemas module (backend + extension
// both consume it), not published by @netcracker/qip-schemas, so it is read
// straight off disk the same way AsyncApiSpecificationParser.test.ts reads
// its own fixtures off __dirname.
const CORPUS_ROOT = path.resolve(
  __dirname,
  "../../../../../schemas/src/test/resources/conformance",
);

const SPECIFICATION_ID = "conformance";

// The typed operation shape shared with the backend. Both sides read the same
// fixture and must reduce it to the same method/path — the backend through
// TypedOperation.deriveMethod/derivePath (Java), the extension through the
// shared deriveTypedMethodPath mirror, which the production read path
// (serviceApiRead.parseOperations) also uses, so there is one derivation.

interface ExpectedCase {
  path: string;
  method: string;
  operationId: string;
  typed: TypedOperation;
  specification: unknown;
  requestSchema: unknown;
  responseSchemas: unknown;
}

interface GeneratedOperation {
  path?: string;
  method?: string;
  specification?: unknown;
  requestSchema?: unknown;
  responseSchemas?: unknown;
}

function corpusDirs(): string[] {
  return fs
    .readdirSync(CORPUS_ROOT, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
}

function caseFiles(dir: string): string[] {
  return fs
    .readdirSync(path.join(CORPUS_ROOT, dir))
    .filter((f) => f.endsWith(".expected.json"))
    .sort();
}

function readExpected(dir: string, file: string): ExpectedCase {
  return JSON.parse(
    fs.readFileSync(path.join(CORPUS_ROOT, dir, file), "utf8"),
  );
}

function readSource(dir: string): string {
  const sourceFile = fs
    .readdirSync(path.join(CORPUS_ROOT, dir))
    .find((f) => f.startsWith("source.input."));
  if (!sourceFile) {
    throw new Error(`No source.input.* in conformance/${dir}`);
  }
  return fs.readFileSync(path.join(CORPUS_ROOT, dir, sourceFile), "utf8");
}

function findOperation(
  operations: GeneratedOperation[],
  expected: ExpectedCase,
): GeneratedOperation | undefined {
  return operations.find(
    (op) => op.path === expected.path && op.method === expected.method,
  );
}

// The corpus stores "no schema" as JSON null; the extension's convention
// (mirrored from OperationSchemaExtractor's EMPTY_SCHEMAS) is `{}`. Both mean
// the same thing, so normalize before comparing — a real structural
// difference still fails since only null/undefined/{} collapse together.
function normalizeEmpty(value: unknown): unknown {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value === "object" && Object.keys(value as object).length === 0) {
    return null;
  }
  return value;
}

function assertCaseMatches(
  operations: GeneratedOperation[],
  expected: ExpectedCase,
): void {
  const operation = findOperation(operations, expected);
  expect(operation).toBeDefined();

  // Cross-language parity gate: the shared typed shape must derive the golden
  // method/path here exactly as it does in the backend's
  // TypedOperationBackfillParityTest, and the extension's own parsed operation
  // must agree with that derivation. If the two derivations ever diverge, one
  // side's corpus run fails.
  expect(deriveMethod(expected.typed)).toBe(expected.method);
  expect(derivePath(expected.typed)).toBe(expected.path);
  expect(operation!.method).toBe(deriveMethod(expected.typed));
  expect(operation!.path).toBe(derivePath(expected.typed));

  expect(normalizeEmpty(operation!.specification)).toEqual(
    normalizeEmpty(expected.specification),
  );
  expect(normalizeEmpty(operation!.requestSchema)).toEqual(
    normalizeEmpty(expected.requestSchema),
  );
  expect(normalizeEmpty(operation!.responseSchemas)).toEqual(
    normalizeEmpty(expected.responseSchemas),
  );
}

describe("Conformance corpus sanity", () => {
  it("is present on disk with the expected fixture directories", () => {
    const dirs = corpusDirs();
    expect(dirs).toEqual(
      [
        "asyncapi26-shipping",
        "asyncapi30-billing",
        "asyncapi30-kafka-comprehensive",
        "graphql-catalog",
        "grpc-payments",
        "openapi30-orders",
        "openapi31-aperture-dam",
        "openapi32-helix-observe",
        "swagger20-inventory",
        "wsdl-hello-service",
      ].sort(),
    );
  });

  it("every fixture directory has at least one *.expected.json case", () => {
    for (const dir of corpusDirs()) {
      expect(caseFiles(dir).length).toBeGreaterThan(0);
    }
  });
});

describe("OpenAPI / Swagger parity", () => {
  const dirs = ["openapi30-orders", "openapi31-aperture-dam", "swagger20-inventory"];

  for (const dir of dirs) {
    describe(dir, () => {
      let operations: GeneratedOperation[];

      beforeAll(async () => {
        const data = await OpenApiSpecificationParser.parseOpenApiContent(
          readSource(dir),
        );
        operations = OpenApiSpecificationParser.createOperationsFromOpenApi(
          data,
          SPECIFICATION_ID,
        );
      });

      it.each(caseFiles(dir))("%s", (file) => {
        assertCaseMatches(operations, readExpected(dir, file));
      });
    });
  }
});

describe("OpenAPI 3.2 parity (openapi32-helix-observe)", () => {
  // Permanently skipped: this extension is deliberately *more* correct than the
  // oracle for OpenAPI 3.2, so parity here is not a goal. The corpus is generated by
  // the backend, which bridges `openapi: 3.2.0` down to 3.1.0 before parsing, and the
  // bridge is lossy. Matching it would mean degrading correct output. Decision and
  // rationale: docs/plans/completed/20260709-ui-and-vscode-extension-api-model.md
  // ("Known follow-ups").
  //
  // The four fixture-verified divergences, all attributable to the bridge:
  //   - `itemSchema` (JSON Lines) and `prefixEncoding` (tuple multipart) are 3.2-only
  //     media-type keywords with no slot in the 3.1 model, so the oracle drops them
  //     and this parser keeps them.
  //   - A parameter with `in: "querystring"` (3.2-only) fails 3.1 validation and is
  //     dropped by the oracle; this parser keeps it.
  //   - The bridge injects a default `xml.nodeType`, which this parser never derives.
  //   - `{ type: "array", items: { $ref } }` inlines under 3.0/3.1 but stays an
  //     unresolved `$ref` in the bridged 3.2 output — the reverse of the general rule.
  //
  // The expansion algorithm itself is proven by the 3.0, 3.1, and Swagger 2.0 cases
  // above, including two other array-of-`$ref` inlining cases.
  it.skip("reaches full parity with the 3.2-bridged oracle", () => {
    // Permanently skipped by design — see the comment above.
  });
});

describe("AsyncAPI parity (Kafka + AMQP, 2.6 and 3.0)", () => {
  const dirs = [
    "asyncapi26-shipping",
    "asyncapi30-billing",
    "asyncapi30-kafka-comprehensive",
  ];

  for (const dir of dirs) {
    describe(dir, () => {
      let operations: GeneratedOperation[];

      beforeAll(async () => {
        const data = await AsyncApiSpecificationParser.parseAsyncApiContent(
          readSource(dir),
        );
        operations = AsyncApiSpecificationParser.createOperationsFromAsyncApi(
          data,
          SPECIFICATION_ID,
        );
      });

      it.each(caseFiles(dir))("%s", (file) => {
        assertCaseMatches(operations, readExpected(dir, file));
      });
    });
  }
});

describe("gRPC (protobuf) parity", () => {
  const dir = "grpc-payments";
  let operations: GeneratedOperation[];

  beforeAll(async () => {
    const data = await ProtoSpecificationParser.parseProtoContent(
      readSource(dir),
    );
    operations = ProtoSpecificationParser.createOperationsFromProto(
      data,
      SPECIFICATION_ID,
    );
  });

  it.each(caseFiles(dir))("%s", (file) => {
    assertCaseMatches(operations, readExpected(dir, file));
  });
});

describe("GraphQL parity (specification only, schemas null by design)", () => {
  const dir = "graphql-catalog";
  let operations: GeneratedOperation[];

  beforeAll(async () => {
    const data = await GraphQLSpecificationParser.parseGraphQLContent(
      readSource(dir),
    );
    operations = GraphQLSpecificationParser.createOperationsFromGraphQL(
      data,
      SPECIFICATION_ID,
    );
  });

  it.each(caseFiles(dir))("%s", (file) => {
    assertCaseMatches(operations, readExpected(dir, file));
  });
});

describe("WSDL/SOAP parity (null by design, full passthrough)", () => {
  const dir = "wsdl-hello-service";
  let operations: GeneratedOperation[];

  beforeAll(async () => {
    const data = await SoapSpecificationParser.parseWsdlContent(
      readSource(dir),
      { fileName: "source.input.wsdl" },
    );
    operations = SoapSpecificationParser.createOperationsFromWsdl(
      data,
      SPECIFICATION_ID,
    );
  });

  it.each(caseFiles(dir))("%s", (file) => {
    assertCaseMatches(operations, readExpected(dir, file));
  });
});

describe("Multi-file OpenAPI (extension-specific: cross-file $ref resolution)", () => {
  // Skipped: multi-file OpenAPI is a known unimplemented capability, not a
  // regression. The shared corpus has no case for it either, because the backend
  // parser does not resolve cross-file $refs at all.
  //
  // The target behavior is that a $ref into a sibling file (for example
  // "common.yaml#/components/schemas/Address") resolves down to a concrete schema
  // under `definitions`, exactly as a same-file $ref already does. What blocks it:
  // QipSpecificationGenerator's expansion engine (resolveRef, expandSchemaInternal,
  // convertRefToDefinition) is hard-wired to a single `openApiSpec` document, unlike
  // the WSDL path, which already threads a resolver (WsdlLoader/WsdlResolver) through
  // XSD imports. Threading the same capability through touches every recursive helper
  // in QipSpecificationGenerator.ts.
  //
  // Tracked in docs/plans/completed/20260709-ui-and-vscode-extension-api-model.md.
  // Turn this into a real assertion when the engine learns to resolve across files.
  it.skip("resolves a $ref into a sibling file down to a concrete schema", () => {
    // Skipped pending multi-file $ref support — see the comment above.
  });
});
