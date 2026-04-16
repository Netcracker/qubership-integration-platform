import { describe, it, expect } from "@jest/globals";
import {
  BEFORE_OPTIONS,
  findBeforeIndexByType,
  hasNoProtocolConstraint,
  inferBeforeType,
  protocolMatchesOption,
  type OneOfOption,
} from "../../../src/components/modal/chain_element/field/oneof/oneof-utils";

jest.mock("../../../src/misc/protocol-utils", () => ({
  normalizeProtocol: (val: string | undefined) => {
    if (!val) return undefined;
    const lower = val.toLowerCase();
    if (lower === "http/1.1" || lower === "http/2") return "http";
    return lower;
  },
}));

describe("inferBeforeType", () => {
  it("returns undefined for null/undefined/non-object", () => {
    expect(inferBeforeType(undefined)).toBeUndefined();
    expect(inferBeforeType(null)).toBeUndefined();
    expect(inferBeforeType("x")).toBeUndefined();
    expect(inferBeforeType(42)).toBeUndefined();
  });

  it("returns existing type when set", () => {
    expect(inferBeforeType({ type: "mapper-2" })).toBe("mapper-2");
    expect(inferBeforeType({ type: "script" })).toBe("script");
  });

  it("infers mapper-2 when mappingDescription present and type missing", () => {
    expect(inferBeforeType({ mappingDescription: { source: {} } })).toBe(
      "mapper-2",
    );
  });

  it("infers script when script field present and type missing", () => {
    expect(inferBeforeType({ script: "return null;" })).toBe("script");
  });

  it("prefers explicit type over inference", () => {
    expect(
      inferBeforeType({ type: "script", mappingDescription: { source: {} } }),
    ).toBe("script");
  });

  it("returns undefined when nothing is known", () => {
    expect(inferBeforeType({})).toBeUndefined();
    expect(inferBeforeType({ unrelated: 1 })).toBeUndefined();
  });

  it("ignores empty-string type", () => {
    expect(inferBeforeType({ type: "" })).toBeUndefined();
    expect(inferBeforeType({ type: "", mappingDescription: {} })).toBe(
      "mapper-2",
    );
  });
});

describe("findBeforeIndexByType", () => {
  it("returns 0 for undefined / unknown", () => {
    expect(findBeforeIndexByType(undefined)).toBe(0);
    expect(findBeforeIndexByType("other")).toBe(0);
  });

  it("returns 1 for mapper-2", () => {
    expect(findBeforeIndexByType("mapper-2")).toBe(1);
  });

  it("returns 2 for script", () => {
    expect(findBeforeIndexByType("script")).toBe(2);
  });
});

describe("BEFORE_OPTIONS table", () => {
  it("defines None at index 0 with no fields and no type", () => {
    expect(BEFORE_OPTIONS[0]).toEqual({ type: undefined, fields: [] });
  });

  it("defines Mapper at index 1 with mappingDescription cleanup", () => {
    expect(BEFORE_OPTIONS[1]).toEqual({
      type: "mapper-2",
      fields: ["mappingDescription"],
    });
  });

  it("defines Scripting at index 2 with script-related cleanup", () => {
    expect(BEFORE_OPTIONS[2]).toEqual({
      type: "script",
      fields: [
        "script",
        "exportFileExtension",
        "propertiesToExportInSeparateFile",
        "propertiesFilename",
      ],
    });
  });
});

describe("protocolMatchesOption", () => {
  const constOption: OneOfOption = {
    properties: { integrationOperationProtocolType: { const: "amqp" } },
  };
  const enumOption: OneOfOption = {
    properties: {
      integrationOperationProtocolType: { enum: ["kafka", "amqp"] },
    },
  };
  const normalizedConstOption: OneOfOption = {
    properties: { integrationOperationProtocolType: { const: "http/1.1" } },
  };

  it("returns false when option has no protocol constraint", () => {
    expect(protocolMatchesOption({ properties: {} }, "http")).toBe(false);
    expect(protocolMatchesOption({}, "http")).toBe(false);
  });

  it("matches by const", () => {
    expect(protocolMatchesOption(constOption, "amqp")).toBe(true);
    expect(protocolMatchesOption(constOption, "kafka")).toBe(false);
  });

  it("matches by enum", () => {
    expect(protocolMatchesOption(enumOption, "kafka")).toBe(true);
    expect(protocolMatchesOption(enumOption, "amqp")).toBe(true);
    expect(protocolMatchesOption(enumOption, "http")).toBe(false);
  });

  it("normalizes protocol when matching const", () => {
    expect(protocolMatchesOption(normalizedConstOption, "http")).toBe(true);
  });
});

describe("hasNoProtocolConstraint", () => {
  it("returns true when option has no protocol property", () => {
    expect(hasNoProtocolConstraint({ properties: {} })).toBe(true);
    expect(hasNoProtocolConstraint({})).toBe(true);
  });

  it("returns false when option has protocol const", () => {
    expect(
      hasNoProtocolConstraint({
        properties: { integrationOperationProtocolType: { const: "http" } },
      }),
    ).toBe(false);
  });

  it("returns false when option has protocol enum", () => {
    expect(
      hasNoProtocolConstraint({
        properties: { integrationOperationProtocolType: { enum: ["kafka"] } },
      }),
    ).toBe(false);
  });
});
