import {
  clearCorrelationIdWhenSwitchedOff,
  getStaleProtocolProperties,
  getTabForPath,
  turnOnCorrelationIdSwitchWhenSet,
} from "../../../../src/components/modal/chain_element/ChainElementModificationConstants";

describe("getTabForPath", () => {
  it("returns tab from pathToTabMap for known paths without elementType", () => {
    expect(getTabForPath("properties.contextPath")).toBe("Endpoint");
    expect(getTabForPath("properties.contextServiceId")).toBe("Operation");
    expect(getTabForPath("properties.script")).toBe("Script");
    expect(getTabForPath("properties.before")).toBe("Prepare Request");
  });

  it("returns undefined for unknown path", () => {
    expect(getTabForPath("properties.unknownField")).toBeUndefined();
    expect(getTabForPath("")).toBeUndefined();
  });

  it("uses exception: properties.after + async-api-trigger returns Validate Request", () => {
    expect(getTabForPath("properties.after", "async-api-trigger")).toBe(
      "Validate Request",
    );
  });

  it("uses exception: properties.key + kafka-sender-2 returns Parameters", () => {
    expect(getTabForPath("properties.key", "kafka-sender-2")).toBe(
      "Parameters",
    );
  });

  it("uses exception: properties.contextPath + checkpoint returns Parameters", () => {
    expect(getTabForPath("properties.contextPath", "checkpoint")).toBe(
      "Parameters",
    );
  });

  it("uses exception: properties.httpMethodRestrict + checkpoint returns Parameters", () => {
    expect(getTabForPath("properties.httpMethodRestrict", "checkpoint")).toBe(
      "Parameters",
    );
  });

  it("falls back to pathToTabMap when elementType has no exception", () => {
    expect(getTabForPath("properties.contextPath", "script")).toBe("Endpoint");
    expect(getTabForPath("properties.after", "script")).toBe("Handle Response");
  });

  it("falls back to pathToTabMap when elementType not in exception mapping", () => {
    expect(getTabForPath("properties.after", "http-trigger")).toBe(
      "Handle Response",
    );
  });
});

describe("getStaleProtocolProperties", () => {
  it("returns protocol-specific properties of other protocols when switching to http", () => {
    const stale = getStaleProtocolProperties("http", {});
    expect(stale).toContain("integrationOperationAsyncProperties");
    expect(stale).toContain("synchronousGrpcCall");
    expect(stale).toContain("integrationGqlQuery");
    expect(stale).not.toContain("integrationOperationPathParameters");
    expect(stale).not.toContain("bodyMimeType");
    expect(stale).not.toContain("afterValidation");
  });

  it("excludes properties already in updates", () => {
    const stale = getStaleProtocolProperties("http", {
      integrationOperationAsyncProperties: {},
    });
    expect(stale).not.toContain("integrationOperationAsyncProperties");
  });

  it("returns all protocol-specific properties for unknown newProtocol", () => {
    const stale = getStaleProtocolProperties("unknown", {});
    const allProps = [
      "integrationOperationPathParameters",
      "integrationOperationQueryParameters",
      "integrationAdditionalParameters",
      "bodyMimeType",
      "bodyFormData",
      "integrationOperationSkipEmptyQueryParameters",
      "afterValidation",
      "integrationOperationAsyncProperties",
      "synchronousGrpcCall",
      "integrationGqlQuery",
      "integrationGqlOperationName",
      "integrationGqlVariablesJSON",
      "integrationGqlQueryHeader",
      "integrationGqlVariablesHeader",
    ];
    for (const prop of allProps) {
      expect(stale).toContain(prop);
    }
  });

  it("returns empty when switching to kafka with all other props in updates", () => {
    const updates: Record<string, unknown> = {};
    const otherProps = [
      "integrationOperationPathParameters",
      "integrationOperationQueryParameters",
      "integrationAdditionalParameters",
      "bodyMimeType",
      "bodyFormData",
      "integrationOperationSkipEmptyQueryParameters",
      "afterValidation",
      "synchronousGrpcCall",
      "integrationGqlQuery",
      "integrationGqlOperationName",
      "integrationGqlVariablesJSON",
      "integrationGqlQueryHeader",
      "integrationGqlVariablesHeader",
    ];
    for (const p of otherProps) {
      updates[p] = null;
    }
    const stale = getStaleProtocolProperties("kafka", updates);
    expect(stale).toHaveLength(0);
  });

  it("returns unique properties (no duplicates)", () => {
    const stale = getStaleProtocolProperties("http", {});
    const seen = new Set(stale);
    expect(stale.length).toBe(seen.size);
  });
});

describe("turnOnCorrelationIdSwitchWhenSet", () => {
  it("should turn the switch on when a position and a name are set", () => {
    for (const receiveCorrelationId of [undefined, null, false]) {
      expect(
        turnOnCorrelationIdSwitchWhenSet({
          receiveCorrelationId,
          correlationIdPosition: "header",
          correlationIdName: "zz-corr",
        }),
      ).toEqual({
        receiveCorrelationId: true,
        correlationIdPosition: "header",
        correlationIdName: "zz-corr",
      });
    }
  });

  it("should keep the properties when the position or the name is missing", () => {
    const positionOnly = { correlationIdPosition: "header" };
    const nameOnly = { correlationIdName: "zz-corr" };
    expect(turnOnCorrelationIdSwitchWhenSet(positionOnly)).toBe(positionOnly);
    expect(turnOnCorrelationIdSwitchWhenSet(nameOnly)).toBe(nameOnly);
    expect(turnOnCorrelationIdSwitchWhenSet(undefined)).toBeUndefined();
  });
});

describe("clearCorrelationIdWhenSwitchedOff", () => {
  it("should clear the position and the name when the switch is off", () => {
    expect(
      clearCorrelationIdWhenSwitchedOff({
        receiveCorrelationId: false,
        correlationIdPosition: "header",
        correlationIdName: "zz-corr",
      }),
    ).toEqual({
      receiveCorrelationId: false,
      correlationIdPosition: undefined,
      correlationIdName: undefined,
    });
  });

  it("should keep the properties when the switch is on or already cleared", () => {
    const on = {
      receiveCorrelationId: true,
      correlationIdPosition: "header",
      correlationIdName: "zz-corr",
    };
    const cleared = { receiveCorrelationId: false };
    expect(clearCorrelationIdWhenSwitchedOff(on)).toBe(on);
    expect(clearCorrelationIdWhenSwitchedOff(cleared)).toBe(cleared);
  });
});
