import {
  QIP_SCHEMA_URLS,
  getQipSchemaType,
  getSchemaUrl,
  isQipSchema,
} from "./qipSchemas";

describe("qipSchemas helpers", () => {
  it("maps a known schema URL to its type", () => {
    expect(getQipSchemaType(QIP_SCHEMA_URLS.SPECIFICATION)).toBe(
      "SPECIFICATION",
    );
    expect(getQipSchemaType(QIP_SCHEMA_URLS.SERVICE)).toBe("SERVICE");
    expect(getQipSchemaType(QIP_SCHEMA_URLS.CHAIN)).toBe("CHAIN");
  });

  it("uses the CIP ids without the .schema.yaml postfix", () => {
    const base =
      "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model";
    expect(QIP_SCHEMA_URLS).toEqual({
      SPECIFICATION: `${base}/specification`,
      SPECIFICATION_GROUP: `${base}/specification-group`,
      SERVICE: `${base}/service`,
      CHAIN: `${base}/chain`,
    });
  });

  it("returns null for an unknown URL", () => {
    expect(getQipSchemaType("http://example.com/unknown")).toBeNull();
  });

  it("does not recognize the legacy QIP URL", () => {
    expect(
      getQipSchemaType("http://qubership.org/schemas/product/qip/chain"),
    ).toBeNull();
  });

  it("isQipSchema reflects recognition", () => {
    expect(isQipSchema(QIP_SCHEMA_URLS.SPECIFICATION_GROUP)).toBe(true);
    expect(isQipSchema("http://example.com/unknown")).toBe(false);
  });

  it("getSchemaUrl round-trips the type", () => {
    expect(getSchemaUrl("SPECIFICATION")).toBe(QIP_SCHEMA_URLS.SPECIFICATION);
    expect(getSchemaUrl("CHAIN")).toBe(QIP_SCHEMA_URLS.CHAIN);
  });
});
