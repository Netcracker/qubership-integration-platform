import {
  getHttpFieldNameError,
  getHttpFieldValueError,
  isHttpFieldName,
  isHttpFieldValue,
} from "../../src/misc/http-field-utils";

// The cases mirror testing-service/internal/httpfield/httpfield_test.go, which is
// what decides whether a saved header comes back as a 400.
describe("isHttpFieldName", () => {
  it.each([
    "Accept",
    "content-type",
    "X-Mocked",
    "x_mocked!",
    "X-Trace.1",
    "0",
  ])("should accept %s as a field name", (name) => {
    expect(isHttpFieldName(name)).toBe(true);
  });

  it.each(["", " ", "X Mocked", "X\tMocked", "X:Mocked", "Accept(json)"])(
    "should refuse %j as a field name",
    (name) => {
      expect(isHttpFieldName(name)).toBe(false);
    },
  );

  it("should refuse a name carrying a line break", () => {
    expect(isHttpFieldName("X-Mocked\n")).toBe(false);
  });

  it("should refuse a name outside the ASCII token set", () => {
    expect(isHttpFieldName("заголовок")).toBe(false);
  });
});

describe("isHttpFieldValue", () => {
  it.each(["", "text/plain", "a b", "a\tb", "ключ"])(
    "should accept %j as a field value",
    (value) => {
      expect(isHttpFieldValue(value)).toBe(true);
    },
  );

  it.each(["a\nb", "a\rb", "a\0b", "a\x7fb"])(
    "should refuse a value carrying a control character",
    (value) => {
      expect(isHttpFieldValue(value)).toBe(false);
    },
  );
});

describe("header field messages", () => {
  it("should ask for a name when the field is blank", () => {
    expect(getHttpFieldNameError("  ")).toBe("Enter a header name.");
  });

  it("should name the accepted characters when the name is not a token", () => {
    expect(getHttpFieldNameError("X Mocked")).toContain("letters, digits");
  });

  it("should report no error for a name and a value the service takes", () => {
    expect(getHttpFieldNameError("X-Mocked")).toBeUndefined();
    expect(getHttpFieldValueError("text/plain")).toBeUndefined();
  });

  it("should report a control character in a value", () => {
    expect(getHttpFieldValueError("a\nb")).toContain("control characters");
  });
});
