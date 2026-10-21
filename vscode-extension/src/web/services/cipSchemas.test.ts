import {
  CIP_SCHEMA_URLS,
  getCipSchemaType,
  getSchemaUrl,
  isCipSchema,
} from "./cipSchemas";

describe("cipSchemas helpers", () => {
  it("maps a known schema URL to its type", () => {
    expect(getCipSchemaType(CIP_SCHEMA_URLS.SPECIFICATION)).toBe(
      "SPECIFICATION",
    );
    expect(getCipSchemaType(CIP_SCHEMA_URLS.SERVICE)).toBe("SERVICE");
    expect(getCipSchemaType(CIP_SCHEMA_URLS.CHAIN)).toBe("CHAIN");
  });

  it("uses the CIP ids without the .schema.yaml postfix", () => {
    const base =
      "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model";
    expect(CIP_SCHEMA_URLS).toEqual({
      SPECIFICATION: `${base}/specification`,
      SPECIFICATION_GROUP: `${base}/specification-group`,
      SERVICE: `${base}/service`,
      CHAIN: `${base}/chain`,
    });
  });

  it("returns null for an unknown URL", () => {
    expect(getCipSchemaType("http://example.com/unknown")).toBeNull();
  });

  it("does not recognize the legacy QIP URL", () => {
    expect(
      getCipSchemaType("http://qubership.org/schemas/product/qip/chain"),
    ).toBeNull();
  });

  it("isCipSchema reflects recognition", () => {
    expect(isCipSchema(CIP_SCHEMA_URLS.SPECIFICATION_GROUP)).toBe(true);
    expect(isCipSchema("http://example.com/unknown")).toBe(false);
  });

  it("getSchemaUrl round-trips the type", () => {
    expect(getSchemaUrl("SPECIFICATION")).toBe(CIP_SCHEMA_URLS.SPECIFICATION);
    expect(getSchemaUrl("CHAIN")).toBe(CIP_SCHEMA_URLS.CHAIN);
  });
});
