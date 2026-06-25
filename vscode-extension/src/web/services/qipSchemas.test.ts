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

  it("returns null for an unknown URL", () => {
    expect(getQipSchemaType("http://example.com/unknown")).toBeNull();
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
