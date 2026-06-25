jest.mock("vscode", () => ({ Uri: class Uri {} }), { virtual: true });
jest.mock("../response/file/fileApiProvider", () => ({
  fileApi: {},
  setFileApi: jest.fn(),
}));

import { SpecificationProcessorService } from "./SpecificationProcessorService";
import { ApiSpecificationType } from "./importApiTypes";

const OPENAPI_32 = JSON.stringify({
  openapi: "3.2.0",
  info: { title: "Test 3.2", version: "1.0.0" },
  paths: {
    "/things": {
      post: {
        operationId: "createThing",
        responses: { "200": { description: "OK" } },
      },
    },
    "/search": {
      query: {
        operationId: "queryThings",
        responses: { "200": { description: "OK" } },
      },
    },
  },
});

function group(): any {
  return { id: "g1", name: "Group", specifications: [] };
}

function specFile(content: string, name: string): File {
  return new File([content], name, { type: "application/json" });
}

describe("SpecificationProcessorService", () => {
  it("propagates the OpenAPI 3.2 bridge warning and drops the QUERY operation", async () => {
    const service = new SpecificationProcessorService();
    const target = group();

    const warnings = await service.processSpecificationFiles(
      target,
      [specFile(OPENAPI_32, "api.json")],
      "sys1",
    );

    expect(warnings.some((w) => w.includes("3.2"))).toBe(true);
    expect(target.specifications).toHaveLength(1);

    const specification = target.specifications[0];
    expect(specification.format).toBe(ApiSpecificationType.HTTP.toString());
    const names = specification.operations.map((o: any) => o.name);
    expect(names).toContain("createThing");
    expect(names).not.toContain("queryThings");
  });

  it("returns no warnings for a clean OpenAPI 3.0 import", async () => {
    const service = new SpecificationProcessorService();
    const target = group();

    const warnings = await service.processSpecificationFiles(
      target,
      [
        specFile(
          JSON.stringify({
            openapi: "3.0.0",
            info: { title: "ok", version: "1.0.0" },
            paths: {
              "/p": { get: { operationId: "getP", responses: { "200": { description: "OK" } } } },
            },
          }),
          "ok.json",
        ),
      ],
      "sys1",
    );

    expect(warnings).toHaveLength(0);
    expect(target.specifications).toHaveLength(1);
  });
});
