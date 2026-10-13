/**
 * The specification a service carries, and the operation info the service-call element reads off it.
 *
 * Covers the half of `system-model-controller` nothing else touches — the listing's two filters,
 * `GET /v1/models/latest`, `GET /v1/models/{modelId}/source` and `PATCH /v1/models/{modelId}` — and
 * `operation-controller` whole. `specs/api/specifications.spec.ts` owns the lifecycle: what an
 * import produces, that a specification is deprecated in place, and that a delete is refused until
 * it is. Re-asserting any of that here would earn the same rows twice, so this file starts where
 * that one stops.
 *
 * The fixture is `orders.openapi.yaml` rather than `widgets.openapi.yaml`, and the reason is the
 * whole point of the operation half: widgets declares no request body and no response content, so
 * every schema derived from it is empty and the derivation cannot be asserted at all. Orders
 * carries a request under **two** content types and responses under **two** codes.
 *
 * Six shapes measured rather than assumed:
 *
 * - **A bare `GET /v1/models` answers `[]`.** `SystemModelController.getModels` starts from an
 *   empty list and fills it only inside an `if`/`else if` over its two filters, so "every
 *   specification" is not a form this endpoint has. A client that forgets the filter reads an empty
 *   catalog, not an error.
 * - **`specificationGroupId` wins over `systemId`** when both are sent — the `if` is checked first.
 * - **`/latest` is by `created_when`, not by version.** `findFirstBySpecificationGroupSystemIdOrderByCreatedWhenDesc`,
 *   so the case below imports the *higher* version first and expects the lower one back. A service
 *   with no specification answers **200 with an empty body**, not 404.
 * - **`/source` is the uploaded document byte for byte**, served as `text/plain`, not a
 *   re-serialization of the parsed model.
 * - **`/info` materializes draft-07 JSON Schema** out of the OpenAPI fragment: `requestSchema` keyed
 *   by content type, `responseSchemas` keyed by response code and then by content type, each value
 *   carrying a `$schema` and an `$id` the source document never had.
 * - **`?mode=light` blanks the values and keeps the keys**, which is what makes the light form
 *   worth having; anything but the literal `light` is the full form.
 *
 * `PATCH /v1/models/{modelId}` selects the row by the body's `id` and ignores the path variable;
 * that is not asserted, because the UI sends the same id in both places (#842, won't fix).
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog, OperationInfo, SpecificationView } from "../../support/catalog.js";
import { readSpecificationFixture, SPECIFICATION_FIXTURE_DIR } from "../../fixtures/templating.js";
import { tokenized } from "../../support/run.js";
import { ABSENT_UUID } from "../../support/absent.js";
import fs from "node:fs";
import path from "node:path";

/** A service carrying one group and one imported specification, cleaned up by its caller. */
interface ImportedFixture {
  systemId: string;
  groupId: string;
  modelId: string;
}

async function serviceWithGroup(
  catalog: Catalog,
  run: string,
  what: string,
  file: string,
  version: string,
): Promise<ImportedFixture> {
  const service = await catalog.createSystem(tokenized(run, `model-${what}`), "EXTERNAL");
  try {
    return { systemId: service.id, ...(await extraGroup(catalog, run, service.id, what, file, version)) };
  } catch (cause) {
    await catalog.deleteSystem(service.id).catch(() => {});
    throw cause;
  }
}

/** A second group under a service that already exists, so `/latest` has two to choose between. */
async function extraGroup(
  catalog: Catalog,
  run: string,
  systemId: string,
  what: string,
  file: string,
  version: string,
): Promise<{ groupId: string; modelId: string }> {
  const groupName = tokenized(run, `group-${what}`);
  const started = await catalog.importSpecificationGroup(
    systemId,
    groupName,
    readSpecificationFixture(file),
    "http",
  );
  await catalog.awaitSpecificationImport(started);
  return { groupId: `${systemId}-${groupName}`, modelId: `${systemId}-${groupName}-${version}` };
}

/** The one operation `orders.openapi.yaml` declares, addressed without reading the listing back. */
function orderOperationId(modelId: string): string {
  return `${modelId}-createOrder`;
}

test("the listing answers one filter or the other, and nothing at all without one", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const first = await serviceWithGroup(catalog, run, "listing", "widgets.openapi.yaml", "1.0.0");
  try {
    const second = await extraGroup(
      catalog,
      run,
      first.systemId,
      "listing-2",
      "orders.openapi.yaml",
      "1.0.0",
    );

    // By service: both groups' specifications, and only this service's.
    const bySystem = await catalog.listModelsOfSystem(first.systemId);
    expect(bySystem.map((each) => each.id).sort()).toEqual([first.modelId, second.modelId].sort());
    for (const model of bySystem) expect(model.systemId).toBe(first.systemId);

    // By group: one of the two, which is what makes the assertion above a filter and not a listing.
    expect((await catalog.listModels(first.groupId)).map((each) => each.id)).toEqual([
      first.modelId,
    ]);
    expect((await catalog.listModels(second.groupId)).map((each) => each.id)).toEqual([
      second.modelId,
    ]);

    // Both filters at once: the group wins. The `if`/`else if` checks `specificationGroupId` first
    // and never reaches `systemId`, so the service-wide answer is unreachable while a group is sent.
    const both = await catalog.call<SpecificationView[]>(
      "get",
      `/v1/models?specificationGroupId=${first.groupId}&systemId=${first.systemId}`,
    );
    expect(both.map((each) => each.id)).toEqual([first.modelId]);

    // Neither filter: an empty list rather than every specification and rather than a 400. This is
    // the shape a client trips over, because it reads as "the catalog holds nothing".
    const bare = await catalog.raw("get", "/v1/models");
    expect(bare.status()).toBe(200);
    expect(await bare.json()).toEqual([]);

    // A filter naming nothing is the same empty list, so the two are indistinguishable from here.
    expect(await catalog.listModelsOfSystem(ABSENT_UUID)).toEqual([]);
    expect(await catalog.listModels(`${ABSENT_UUID}-nothing`)).toEqual([]);
  } finally {
    await catalog.deleteSystem(first.systemId).catch(() => {});
  }
});

test("the latest specification of a service is the most recently created, not the highest version", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  // 2.0.0 first and 1.0.0 second, deliberately. `findFirstBySpecificationGroupSystemIdOrderByCreatedWhenDesc`
  // orders by `created_when`, so a reader that picked the highest `info.version` would answer the
  // first import here and this case would go red.
  const older = await serviceWithGroup(catalog, run, "latest", "widgets-v2.openapi.yaml", "2.0.0");
  try {
    expect((await catalog.latestModel(older.systemId))?.id).toBe(older.modelId);

    const newer = await extraGroup(
      catalog,
      run,
      older.systemId,
      "latest-2",
      "orders.openapi.yaml",
      "1.0.0",
    );
    const latest = await catalog.latestModel(older.systemId);
    expect(latest?.id, "the later import, though its version is lower").toBe(newer.modelId);
    expect(latest?.version).toBe("1.0.0");
    // It crosses groups: the two specifications are in different groups of one service.
    expect(latest?.specificationGroupId).toBe(newer.groupId);
    // And it carries the operations, which is what makes it usable without a second call.
    expect(latest?.operations?.map((each) => each.name)).toEqual(["createOrder"]);

    // A service with no specification is a 200 with an empty body, not a 404 and not `{}`.
    const empty = await catalog.createSystem(tokenized(run, "model-latest-empty"), "EXTERNAL");
    try {
      const response = await catalog.raw("get", `/v1/models/latest?systemId=${empty.id}`);
      expect(response.status()).toBe(200);
      expect(await response.text()).toBe("");
    } finally {
      await catalog.deleteSystem(empty.id).catch(() => {});
    }

    // `systemId` is required rather than defaulted, so there is no whole-catalog form.
    expect((await catalog.raw("get", "/v1/models/latest")).status()).toBe(400);
  } finally {
    await catalog.deleteSystem(older.systemId).catch(() => {});
  }
});

test("the source is the uploaded document byte for byte", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithGroup(catalog, run, "source", "orders.openapi.yaml", "1.0.0");
  try {
    const onDisk = fs.readFileSync(path.join(SPECIFICATION_FIXTURE_DIR, "orders.openapi.yaml"), "utf-8");
    const response = await catalog.raw("get", `/v1/models/${fixture.modelId}/source`);
    expect(response.status()).toBe(200);
    expect(response.headers()["content-type"]).toContain("text/plain");
    // Byte for byte, comment lines included. The catalog stores the upload and serves it back; a
    // re-serialization of the parsed model would keep the paths and lose every line above them.
    expect(await response.text()).toBe(onDisk);

    const missing = await catalog.raw("get", `/v1/models/${ABSENT_UUID}/source`);
    expect(missing.status()).toBe(404);
    expect(await missing.json()).toMatchObject({
      serviceName: "Catalog",
      errorMessage: `Can't find system model with id: ${ABSENT_UUID}`,
    });
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});

test("a specification is patched by the id in its body", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithGroup(catalog, run, "patch", "orders.openapi.yaml", "1.0.0");
  try {
    expect((await catalog.getModel(fixture.modelId)).labels ?? []).toEqual([]);

    const patched = await catalog.patchModel({
      id: fixture.modelId,
      labels: [{ name: "e2e-model-label" }],
    });
    expect(patched.id).toBe(fixture.modelId);
    // Re-read rather than taken off the response: an endpoint echoing its request body passes the
    // shallower assertion having written nothing.
    const reread = await catalog.getModel(fixture.modelId);
    expect(reread.labels).toEqual([{ name: "e2e-model-label", technical: false }]);
    // The patch is partial: what it did not name is untouched.
    expect(reread).toMatchObject({ version: "1.0.0", deprecated: false, source: "MANUAL" });

    // A body without an `id` is a 500 rather than a 400, because the path variable is not a
    // fallback: the service is handed the mapped entity and asks the repository for `null`.
    const noId = await catalog.raw("patch", `/v1/models/${fixture.modelId}`, {
      labels: [{ name: "ignored" }],
    });
    expect(noId.status()).toBe(500);
    expect((await noId.json()).errorMessage).toBe("The given id must not be null");
    expect((await catalog.getModel(fixture.modelId)).labels).toEqual([
      { name: "e2e-model-label", technical: false },
    ]);
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});

test("an operation carries the fragment it was parsed out of", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithGroup(catalog, run, "operation", "orders.openapi.yaml", "1.0.0");
  const operationId = orderOperationId(fixture.modelId);
  try {
    const operation = await catalog.getOperation(operationId);
    expect(operation).toMatchObject({
      id: operationId,
      name: "createOrder",
      method: "POST",
      path: "/orders",
      modelId: fixture.modelId,
    });

    // The fragment is the document's own operation object, with the request body and both
    // responses. `operationId` survives the round trip and is what the id is derived from.
    const specification = operation.specification as {
      operationId: string;
      requestBody: { required: boolean; content: Record<string, unknown> };
      responses: Record<string, { description: string }>;
    };
    expect(specification.operationId).toBe("createOrder");
    expect(Object.keys(specification.requestBody.content).sort()).toEqual([
      "application/json",
      "application/xml",
    ]);
    expect(specification.requestBody.required).toBe(true);
    expect(Object.keys(specification.responses).sort()).toEqual(["201", "422"]);
    expect(specification.responses["422"].description).toBe("the order was rejected");

    // `/specification` is that same object served on its own, which is the cheap call the form
    // makes when it already knows the method and the path.
    expect(await catalog.operationSpecification(operationId)).toEqual(operation.specification);

    const missing = await catalog.raw("get", `/v1/operations/${ABSENT_UUID}`);
    expect(missing.status()).toBe(404);
    expect(await missing.json()).toMatchObject({
      errorMessage: `Can't find operation with id ${ABSENT_UUID}`,
    });
    expect((await catalog.raw("get", `/v1/operations/${ABSENT_UUID}/info`)).status()).toBe(404);
    expect((await catalog.raw("get", `/v1/operations/${ABSENT_UUID}/specification`)).status()).toBe(404);
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});

test("the operation info derives a JSON Schema per content type and per response code", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithGroup(catalog, run, "info", "orders.openapi.yaml", "1.0.0");
  const operationId = orderOperationId(fixture.modelId);
  try {
    const info: OperationInfo = await catalog.operationInfo(operationId);
    expect(info.id).toBe(operationId);
    expect(info.specification).toEqual(await catalog.operationSpecification(operationId));

    // Keyed by content type, both of them, and **not** by the OpenAPI wrapper: there is no
    // `content` or `schema` level left, the value is the schema itself.
    expect(Object.keys(info.requestSchema).sort()).toEqual([
      "application/json",
      "application/xml",
    ]);
    expect(info.requestSchema["application/json"]).toEqual({
      $id: "http://system.catalog/schemas/#/components/schemas/Schema",
      $schema: "http://json-schema.org/draft-07/schema#",
      type: "object",
      required: ["sku"],
      properties: { sku: { type: "string" }, quantity: { type: "integer" } },
    });
    // The XML entry is the other document, not a copy of the JSON one — which is what a map keyed
    // by content type has to prove.
    expect(info.requestSchema["application/xml"]).toEqual({
      $id: "http://system.catalog/schemas/#/components/schemas/Schema",
      $schema: "http://json-schema.org/draft-07/schema#",
      type: "object",
      properties: { sku: { type: "string" } },
    });

    // Responses are two levels: the code, then the content type under it.
    expect(Object.keys(info.responseSchemas).sort()).toEqual(["201", "422"]);
    expect(Object.keys(info.responseSchemas["201"])).toEqual(["application/json"]);
    expect(info.responseSchemas["201"]["application/json"]).toMatchObject({
      $schema: "http://json-schema.org/draft-07/schema#",
      properties: { id: { type: "string" } },
    });
    expect(info.responseSchemas["422"]["application/json"]).toMatchObject({
      properties: { reason: { type: "string" } },
    });

    // The two single-entry endpoints answer exactly what the map holds, so a form fetching one
    // content type gets the same document the whole-info call carries.
    expect(await catalog.operationRequestSchema(operationId)).toEqual(
      info.requestSchema["application/json"],
    );
    expect(await catalog.operationRequestSchema(operationId, "application/xml")).toEqual(
      info.requestSchema["application/xml"],
    );
    expect(
      await catalog.operationResponseSchema(operationId, { responseCode: "422" }),
      "the default content type, with the code named",
    ).toEqual(info.responseSchemas["422"]["application/json"]);

    // A content type the operation does not declare is not refused: the map misses, the null is
    // serialized as an empty body, and the status is 200.
    const unknownType = await catalog.raw(
      "get",
      `/v1/operations/${operationId}/schemas/request?contentType=text/csv`,
    );
    expect(unknownType.status()).toBe(200);
    expect(await unknownType.text()).toBe("");
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});

test("a response code or content type the operation does not declare is answered empty", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithGroup(catalog, run, "response-miss", "orders.openapi.yaml", "1.0.0");
  const operationId = orderOperationId(fixture.modelId);
  try {
    // Both misses answer the way the request form answers one: the null is serialized as an empty
    // body, and the status is 200.
    for (const query of ["responseCode=418", "responseCode=422&contentType=application/xml"]) {
      const response = await catalog.raw("get", `/v1/operations/${operationId}/schemas/response?${query}`);
      expect(response.status(), query).toBe(200);
      expect(await response.text(), query).toBe("");
    }
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});

test("the light schema view keeps the keys and drops the bodies", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = await serviceWithGroup(catalog, run, "schemas", "orders.openapi.yaml", "1.0.0");
  const operationId = orderOperationId(fixture.modelId);
  try {
    const light = await catalog.operationSchemas(operationId);
    expect(light).toMatchObject({ id: operationId, name: "createOrder", method: "POST", path: "/orders" });
    // Both content types are still listed, and every schema under them is gone. That is the whole
    // contract of the light form: the UI needs the keys to draw its selectors and not the bodies.
    expect(light.requestSchema).toEqual({ "application/json": {}, "application/xml": {} });
    expect(light.responseSchemas).toEqual({
      "201": { "application/json": {} },
      "422": { "application/json": {} },
    });

    // The full form is the same object with the schemas in place, and it agrees with `/info`.
    const full = await catalog.operationSchemas(operationId, "full");
    const info = await catalog.operationInfo(operationId);
    expect(full.requestSchema).toEqual(info.requestSchema);
    expect(full.responseSchemas).toEqual(info.responseSchemas);

    // `light` is compared against literally, so any other value is the full form rather than an
    // error — a client sending `mode=brief` gets the bodies it was trying to avoid.
    const misspelled = await catalog.operationSchemas(operationId, "brief" as "full");
    expect(misspelled.requestSchema).toEqual(info.requestSchema);
  } finally {
    await catalog.deleteSystem(fixture.systemId).catch(() => {});
  }
});
