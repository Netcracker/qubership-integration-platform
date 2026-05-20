import {
  buildFormContextFromProperties,
  enrichProperties,
  METADATA_ONLY_CONTEXT_KEYS,
} from "../../../../src/components/modal/chain_element/ChainElementModificationContext";

describe("ChainElementModificationContext", () => {
  describe("METADATA_ONLY_CONTEXT_KEYS", () => {
    it("contains the operation-info metadata keys", () => {
      expect(METADATA_ONLY_CONTEXT_KEYS).toEqual(
        expect.arrayContaining([
          "operationSpecification",
          "operationRequestSchema",
          "operationResponseSchemas",
        ]),
      );
    });

    it("also contains the legacy metadata keys", () => {
      expect(METADATA_ONLY_CONTEXT_KEYS).toEqual(
        expect.arrayContaining([
          "elementType",
          "chainId",
          "updateContext",
          "reportMissingRequiredParams",
        ]),
      );
    });
  });

  describe("enrichProperties", () => {
    it("merges simple properties into target", () => {
      const result = enrichProperties({ a: "1" }, { b: "2", c: "3" });
      expect(result).toEqual({ a: "1", b: "2", c: "3" });
    });

    it("overrides existing keys in target", () => {
      const result = enrichProperties({ a: "1" }, { a: "override" });
      expect(result).toEqual({ a: "override" });
    });

    it("removes target keys when source provides undefined/null", () => {
      const result = enrichProperties(
        { a: "1", b: "2" },
        { a: undefined, b: null },
      );
      expect(result).toEqual({});
    });

    it("normalizes integrationOperationProtocolType", () => {
      const result = enrichProperties(
        {},
        { integrationOperationProtocolType: " HTTP " },
      );
      expect(result).toEqual({ integrationOperationProtocolType: "http" });
    });

    it("does not write operationSpecification into target even if given in source", () => {
      const bigSpec = { parameters: [{ name: "x" }] };
      const result = enrichProperties(
        { existing: "keep" },
        {
          operationSpecification: bigSpec,
          operationRequestSchema: { "application/json": { type: "object" } },
          operationResponseSchemas: { "200": {} },
        },
      );
      expect(result).toEqual({ existing: "keep" });
      expect(result).not.toHaveProperty("operationSpecification");
      expect(result).not.toHaveProperty("operationRequestSchema");
      expect(result).not.toHaveProperty("operationResponseSchemas");
    });

    it("does not write elementType / chainId / updateContext / reportMissingRequiredParams", () => {
      const result = enrichProperties(
        {},
        {
          elementType: "service-call",
          chainId: "chain-1",
          updateContext: () => {},
          reportMissingRequiredParams: () => {},
          keepThis: "yes",
        },
      );
      expect(result).toEqual({ keepThis: "yes" });
    });

    it("keeps non-metadata operation fields (integrationOperation*) in target", () => {
      const result = enrichProperties(
        {},
        {
          integrationOperationId: "op-1",
          integrationOperationPath: "/foo",
          operationSpecification: { secret: "bad" },
        },
      );
      expect(result).toEqual({
        integrationOperationId: "op-1",
        integrationOperationPath: "/foo",
      });
    });
  });

  describe("buildFormContextFromProperties", () => {
    const updateContext = jest.fn();

    it("does not include operationSpecification / schemas from element properties", () => {
      // Even if someone accidentally writes these into element.properties
      // (e.g. via a legacy migration), they must not rehydrate into context
      // through this builder — schemas are re-loaded fresh by SystemOperationField.
      const props = {
        integrationOperationId: "op-1",
        operationSpecification: { parameters: [] },
        operationRequestSchema: { "application/json": {} },
        operationResponseSchemas: {},
      };
      const context = buildFormContextFromProperties(
        props,
        "service-call",
        "chain-1",
        updateContext,
      );
      expect(context.integrationOperationId).toBe("op-1");
      expect(context.operationSpecification).toBeUndefined();
      expect(context.operationRequestSchema).toBeUndefined();
      expect(context.operationResponseSchemas).toBeUndefined();
    });

    it("populates declarative fields from element properties", () => {
      const context = buildFormContextFromProperties(
        {
          integrationSystemId: "sys-1",
          integrationOperationProtocolType: "HTTP",
          synchronousGrpcCall: false,
        },
        "service-call",
        "chain-1",
        updateContext,
      );
      expect(context.integrationSystemId).toBe("sys-1");
      expect(context.integrationOperationProtocolType).toBe("http");
      expect(context.synchronousGrpcCall).toBe(false);
    });
  });
});
