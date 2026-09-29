/**
 * Pins the discriminator extractor against the element schemas.
 *
 * These specs need no stack. They exist because the coverage registry is only as honest as the
 * axis list it is measured against: an extractor that silently drops values reports a complete
 * registry over a short list, and the gap detector goes green over branches nobody tests.
 *
 * Every assertion here is a shape the schemas actually declare, not a hypothetical.
 */
import { test, expect } from "@playwright/test";
import {
  collectElementSchemaFiles,
  extractDiscriminators,
  loadElementSchemas,
  type Discriminator,
} from "../../registry/discriminators.js";

let schemas: Map<string, unknown>;

test.beforeAll(async () => {
  schemas = await loadElementSchemas();
});

function axesOf(element: string): Discriminator[] {
  const schema = schemas.get(element);
  expect(schema, `no schema for ${element}`).toBeTruthy();
  return extractDiscriminators(schema, element);
}

function axis(element: string, axisPath: string): Discriminator {
  const found = axesOf(element).find((a) => a.axisPath === axisPath);
  expect(
    found,
    `${element} declares no axis at ${axisPath}; it has ${axesOf(element)
      .map((a) => a.axisPath)
      .join(", ")}`,
  ).toBeTruthy();
  return found!;
}

test("the source list is the element tree with the abstract base excluded", { tag: ["@infra", "@tier1"] }, () => {
  const files = collectElementSchemaFiles();
  expect(files.length).toBeGreaterThan(60);
  expect(files).not.toContain("element.schema.yaml");
  // `element.schema.yaml` is the base every element `allOf`s, not an element type. Including it
  // would invent a family the library never offers.
  expect(schemas.has("element")).toBe(false);
  expect(schemas.has("service-call")).toBe(true);
});

test("service-call discriminates on all six operation protocols", { tag: ["@infra", "@tier1"] }, () => {
  // The axis lives in element/properties/operation.schema.yaml and is declared at six sites: a
  // bare six-value enum behind #/definitions/Protocol, four oneOf fragments splitting the property
  // by transport, and a `const: http` under an `if`. An extractor that neither resolves $refs nor
  // unions the sites reports a fragment of this.
  const protocol = axis("service-call", "integrationOperationProtocolType");
  expect(protocol.axis).toBe("integrationOperationProtocolType");
  expect([...protocol.values].sort()).toEqual(
    ["amqp", "graphql", "grpc", "http", "kafka", "soap"].sort(),
  );
});

test("http-trigger discriminates on the three access control types", { tag: ["@infra", "@tier1"] }, () => {
  // Declared in element/properties/access-control-properties.schema.yaml: once as a bare enum and
  // three times as an `if` condition. One axis, three values.
  const accessControl = axis("http-trigger", "accessControlType");
  expect([...accessControl.values].sort()).toEqual(["ABAC", "NONE", "RBAC"]);
});

test("a boolean axis declared only as const: true reports both values", { tag: ["@infra", "@tier1"] }, () => {
  // correlation-id.schema.yaml declares receiveCorrelationId as a plain boolean and branches on
  // `const: true` alone. The false side is a real case — it is the default — and it is the side a
  // one-sided reading would never test.
  const receive = axis("http-trigger", "receiveCorrelationId");
  expect([...receive.values].sort()).toEqual([false, true]);

  const enabled = axis("http-trigger", "idempotency/enabled");
  expect([...enabled.values].sort()).toEqual([false, true]);
});

test("a const pinned in a plain properties block stays a one-value axis", { tag: ["@infra", "@tier1"] }, () => {
  // checkpoint fixes externalRoute and httpMethodRestrict rather than branching on them, so the
  // boolean completion above must not invent a second case here.
  expect(axis("checkpoint", "externalRoute").values).toEqual([false]);
  expect(axis("checkpoint", "httpMethodRestrict").values).toEqual(["POST"]);
});

test("a oneOf of annotated consts is an axis keyed by the enclosing property", { tag: ["@infra", "@tier1"] }, () => {
  // Neither of these is a bare const: every branch carries a title. Reading clause 3 literally
  // truncates both value lists while leaving the axis count untouched, because the same axes are
  // also declared at `if` sites — which is exactly how a gap detector goes green over branches it
  // cannot see. This is the pinned value list.
  expect([...axis("context-storage", "operation").values].sort()).toEqual([
    "DELETE",
    "GET",
    "SET",
  ]);
  expect(
    [...axis("http-trigger", "idempotency/actionOnDuplicate").values].sort(),
  ).toEqual(["execute-subchain", "ignore", "throw-exception"]);
});

test("an enum under a nested if is unioned with the oneOf that declares the property", { tag: ["@infra", "@tier1"] }, () => {
  // context-storage declares `target` as a oneOf of BODY/HEADER/PROPERTY and then branches on
  // `enum: [HEADER, PROPERTY]`. Both sites normalize to the same key.
  expect([...axis("context-storage", "target").values].sort()).toEqual([
    "BODY",
    "HEADER",
    "PROPERTY",
  ]);
});

test("a branch set with no const is a value union, not an axis", { tag: ["@infra", "@tier1"] }, () => {
  // retryCount and retryDelay are `{type: integer}` against two string patterns. They are covered
  // once by the placeholder spec, not per element.
  const paths = axesOf("service-call").map((a) => a.axisPath);
  expect(paths).not.toContain("retryCount");
  expect(paths).not.toContain("retryDelay");
});

test("service-call keys its four type axes by path, not by name", { tag: ["@infra", "@tier1"] }, () => {
  // Keyed on the bare property name these collapse into one nine-value blob that describes
  // nothing. Keyed on a raw JSON path they fragment instead. The normalized path is the key.
  const byPath = new Map(axesOf("service-call").map((a) => [a.axisPath, a.values]));

  expect([...(byPath.get("before/type") ?? [])].sort()).toEqual([
    "mapper-2",
    "none",
    "script",
  ]);
  // `items` stays in the path: `after` and `afterValidation` are arrays, and folding `items` out
  // would merge two different axes.
  expect([...(byPath.get("after/items/type") ?? [])].sort()).toEqual([
    "mapper-2",
    "none",
    "script",
  ]);
  expect([...(byPath.get("authorizationConfiguration/type") ?? [])].sort()).toEqual([
    "basic",
    "bearer",
    "inherit",
    "m2m",
    "none",
  ]);
  expect(byPath.get("afterValidation/items/type")).toEqual(["responseValidation"]);

  // The element's own `properties.type.const` is the family tag, one per element and always equal
  // to the element name. It is not a fifth axis.
  expect(byPath.has("type")).toBe(false);
});

test("anyOf with a not: {} branch yields one value, not an axis of two", { tag: ["@infra", "@tier1"] }, () => {
  // service-call's ExchangeTransformation is the only site in the tree. `not: {}` never validates,
  // so it means "the property is `none` if present" and contributes nothing.
  const before = axis("service-call", "before/type");
  expect(before.values.filter((v) => v === "none")).toHaveLength(1);
  expect(before.values).toHaveLength(3);
});

test("a bare enum outside every branch list is still an axis", { tag: ["@infra", "@tier1"] }, () => {
  // systemType sits in a plain `properties` block in operation.schema.yaml. A rule that only looks
  // inside oneOf/anyOf/if drops it.
  expect([...axis("service-call", "systemType").values].sort()).toEqual([
    "EXTERNAL",
    "IMPLEMENTED",
    "INTERNAL",
  ]);
});

test("else branches are read exactly like then branches", { tag: ["@infra", "@tier1"] }, () => {
  // securityProtocol and saslMechanism are declared under the `else: # manual` of
  // kafka-connection-parameters.schema.yaml. sslProtocol sits in that file's top-level properties
  // and is caught by the bare-enum rule instead, so it holds either way.
  for (const element of ["kafka-sender-2", "kafka-trigger-2"]) {
    expect(axis(element, "securityProtocol").values).toHaveLength(4);
    expect(axis(element, "saslMechanism").values).toHaveLength(42);
    expect(axis(element, "sslProtocol").values).toHaveLength(4);
  }
});

test("the mapper data model is stubbed rather than resolved", { tag: ["@infra", "@tier1"] }, () => {
  // resource-reference-mapper.schema.yaml refs mapper-description as `mappingDescription`.
  // Resolving it for real pulls the mapper's own type model — null, string, number, boolean,
  // array, object, reference, allOf, anyOf, oneOf, constant, attribute, given, generated — into
  // every element that references a mapper, and reports them as platform axes.
  for (const element of ["mapper-2", "service-call", "http-trigger"]) {
    const paths = axesOf(element).map((a) => a.axisPath);
    expect(paths.filter((p) => p.split("/").includes("mappingDescription"))).toEqual([]);
  }
});

test("the export serialization directives are not axes", { tag: ["@infra", "@tier1"] }, () => {
  // exportFileExtension and propertiesToExportInSeparateFile are derived from the sibling `type`
  // and say where the exporter writes a property, not how the element behaves. Admitting them
  // adds fourteen axes across four elements, each demanding a registry row and a reason for
  // nothing.
  for (const element of ["mapper-2", "script", "service-call"]) {
    const paths = axesOf(element).map((a) => a.axisPath);
    expect(paths.filter((p) => p.endsWith("exportFileExtension"))).toEqual([]);
    expect(
      paths.filter((p) => p.endsWith("propertiesToExportInSeparateFile")),
    ).toEqual([]);
  }
});

test("a definitions subtree is never counted as a site", { tag: ["@infra", "@tier1"] }, () => {
  // The dereferencer inlines each `$ref`'d file's own `definitions` block as data at a nested
  // path. correlationIdPosition reappears there on four elements, so a walk that reads definitions
  // reports the same axis twice under different keys.
  for (const element of ["service-call", "http-trigger", "http-sender", "graphql-sender"]) {
    const positions = axesOf(element).filter((a) => a.axis === "correlationIdPosition");
    expect(
      positions.map((a) => a.axisPath),
      `${element} reports correlationIdPosition more than once`,
    ).toEqual(["correlationIdPosition"]);
    expect([...positions[0].values].sort()).toEqual(["body", "header"]);
  }
});

test("every axis carries at least one value, a non-empty path, and a path no sibling repeats", { tag: ["@infra", "@tier1"] }, () => {
  // `element` and `axis` are assigned from the arguments and from `axisPath` two lines apart in
  // `extractDiscriminators`, so restating them here would compare the extractor against itself.
  // What the merge actually promises is per-axis: one entry per path, at least one value, and no
  // value twice.
  for (const [element, schema] of schemas) {
    const found = extractDiscriminators(schema, element);
    const paths = found.map((each) => each.axisPath);
    expect(new Set(paths).size, `${element} reports the same axisPath more than once`).toBe(
      paths.length,
    );

    for (const each of found) {
      expect(each.axisPath.length, `${element} has an empty axisPath`).toBeGreaterThan(0);
      expect(each.values.length, `${element}/${each.axisPath} has no values`).toBeGreaterThan(0);
      expect(
        new Set(each.values).size,
        `${element}/${each.axisPath} reports a value twice`,
      ).toBe(each.values.length);
    }
  }
});
