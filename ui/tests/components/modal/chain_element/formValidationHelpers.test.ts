import type { RJSFValidationError } from "@rjsf/utils";
import {
  formatValidationError,
  hasCriticalErrors,
  transformValidationErrors,
  validateKafkaGroupId,
} from "../../../../src/components/modal/chain_element/formValidationHelpers";

describe("validateKafkaGroupId", () => {
  it("returns null when elementType is not async-api-trigger", () => {
    const props = {
      integrationOperationProtocolType: "kafka",
      integrationOperationAsyncProperties: { groupId: "my-group" },
    };
    expect(validateKafkaGroupId(props, "script")).toBeNull();
    expect(validateKafkaGroupId(props, "http-trigger")).toBeNull();
  });

  it("returns null when properties is undefined or empty", () => {
    expect(validateKafkaGroupId(undefined, "async-api-trigger")).toBeNull();
    expect(validateKafkaGroupId({}, "async-api-trigger")).toBeNull();
  });

  it("returns null when protocol is not Kafka", () => {
    const props = {
      integrationOperationProtocolType: "http",
      integrationOperationAsyncProperties: {},
    };
    expect(validateKafkaGroupId(props, "async-api-trigger")).toBeNull();

    const propsAmqp = {
      integrationOperationProtocolType: "amqp",
      integrationOperationAsyncProperties: { groupId: "x" },
    };
    expect(validateKafkaGroupId(propsAmqp, "async-api-trigger")).toBeNull();
  });

  it("returns error when Kafka protocol and groupId is missing or empty", () => {
    const propsNoAsync = {
      integrationOperationProtocolType: "kafka",
    };
    expect(validateKafkaGroupId(propsNoAsync, "async-api-trigger")).toBe(
      "Group ID is required for Kafka operations.",
    );

    const propsEmptyAsync = {
      integrationOperationProtocolType: "kafka",
      integrationOperationAsyncProperties: {},
    };
    expect(validateKafkaGroupId(propsEmptyAsync, "async-api-trigger")).toBe(
      "Group ID is required for Kafka operations.",
    );

    const propsEmptyGroupId = {
      integrationOperationProtocolType: "kafka",
      integrationOperationAsyncProperties: { groupId: "" },
    };
    expect(validateKafkaGroupId(propsEmptyGroupId, "async-api-trigger")).toBe(
      "Group ID is required for Kafka operations.",
    );

    const propsWhitespaceGroupId = {
      integrationOperationProtocolType: "kafka",
      integrationOperationAsyncProperties: { groupId: "   " },
    };
    expect(
      validateKafkaGroupId(propsWhitespaceGroupId, "async-api-trigger"),
    ).toBe("Group ID is required for Kafka operations.");
  });

  it("returns null when Kafka protocol and groupId is valid", () => {
    const props = {
      integrationOperationProtocolType: "kafka",
      integrationOperationAsyncProperties: { groupId: "my-consumer-group" },
    };
    expect(validateKafkaGroupId(props, "async-api-trigger")).toBeNull();

    const propsTrimmed = {
      integrationOperationProtocolType: "KAFKA",
      integrationOperationAsyncProperties: { groupId: "  group  " },
    };
    expect(validateKafkaGroupId(propsTrimmed, "async-api-trigger")).toBeNull();
  });

  it("returns error when groupId is non-string (treated as empty)", () => {
    const props = {
      integrationOperationProtocolType: "kafka",
      integrationOperationAsyncProperties: { groupId: 123 },
    };
    expect(validateKafkaGroupId(props, "async-api-trigger")).toBe(
      "Group ID is required for Kafka operations.",
    );
  });
});

describe("hasCriticalErrors", () => {
  it("returns false for empty array", () => {
    expect(hasCriticalErrors([])).toBe(false);
  });

  it("returns true for required, type, pattern, format, enum, minimum, maximum", () => {
    expect(
      hasCriticalErrors([{ name: "required", property: ".x", message: "x" }]),
    ).toBe(true);
    expect(
      hasCriticalErrors([{ name: "type", property: ".x", message: "x" }]),
    ).toBe(true);
    expect(
      hasCriticalErrors([{ name: "pattern", property: ".x", message: "x" }]),
    ).toBe(true);
    expect(
      hasCriticalErrors([{ name: "format", property: ".x", message: "x" }]),
    ).toBe(true);
    expect(
      hasCriticalErrors([{ name: "enum", property: ".x", message: "x" }]),
    ).toBe(true);
    expect(
      hasCriticalErrors([{ name: "minimum", property: ".x", message: "x" }]),
    ).toBe(true);
    expect(
      hasCriticalErrors([{ name: "maximum", property: ".x", message: "x" }]),
    ).toBe(true);
  });

  it("returns false for oneOf and anyOf", () => {
    expect(
      hasCriticalErrors([{ name: "oneOf", property: ".x", message: "x" }]),
    ).toBe(false);
    expect(
      hasCriticalErrors([{ name: "anyOf", property: ".x", message: "x" }]),
    ).toBe(false);
  });

  it("returns true for unknown error types", () => {
    expect(
      hasCriticalErrors([
        { name: "custom", property: ".x", message: "x" } as RJSFValidationError,
      ]),
    ).toBe(true);
  });

  it("returns true when any error is critical in mixed array", () => {
    expect(
      hasCriticalErrors([
        { name: "anyOf", property: ".x", message: "x" },
        { name: "required", property: ".y", message: "y" },
      ]),
    ).toBe(true);
  });

  it("returns false when all errors are oneOf/anyOf", () => {
    expect(
      hasCriticalErrors([
        { name: "oneOf", property: ".x", message: "x" },
        { name: "anyOf", property: ".y", message: "y" },
      ]),
    ).toBe(false);
  });
});

describe("formatValidationError", () => {
  it("formats error with property", () => {
    expect(
      formatValidationError({
        name: "required",
        property: ".properties.name",
        message: "is required",
      }),
    ).toBe('Field ".properties.name": is required');
  });

  it("formats error without property", () => {
    expect(
      formatValidationError({
        name: "type",
        message: "must be string",
      }),
    ).toBe("Form: must be string");
  });
});

describe("transformValidationErrors", () => {
  const mkError = (
    name: string,
    property?: string,
    message?: string,
  ): RJSFValidationError =>
    ({ name, property, message }) as RJSFValidationError;

  it("passes through errors not matching filter rules", () => {
    const transformer = transformValidationErrors();
    const errors = [
      mkError("required", ".x", "required"),
      mkError("type", ".y", "must be string"),
    ];
    expect(transformer(errors)).toEqual(errors);
  });

  it("filters oneOf at .properties when valid protocol in formContext", () => {
    const transformer = transformValidationErrors({
      integrationOperationProtocolType: "http",
    });
    const errors = [
      mkError("oneOf", ".properties", "one of"),
      mkError("required", ".x", "required"),
    ];
    const result = transformer(errors);
    expect(result).toHaveLength(1);
    expect(result[0].name).toBe("required");
  });

  it("filters oneOf for http, kafka, amqp, graphql, grpc, soap", () => {
    for (const proto of ["http", "kafka", "amqp", "graphql", "grpc", "soap"]) {
      const transformer = transformValidationErrors({
        integrationOperationProtocolType: proto,
      });
      const errors = [mkError("oneOf", ".properties", "one of")];
      expect(transformer(errors)).toHaveLength(0);
    }
  });

  it("keeps oneOf at .properties when protocol is empty or invalid", () => {
    const transformer = transformValidationErrors({
      integrationOperationProtocolType: "",
    });
    const errors = [mkError("oneOf", ".properties", "one of")];
    expect(transformer(errors)).toHaveLength(1);
  });

  it("filters oneOf for grpc at any property", () => {
    const transformer = transformValidationErrors({
      integrationOperationProtocolType: "grpc",
    });
    const errors = [mkError("oneOf", ".other", "grpc branch")];
    expect(transformer(errors)).toHaveLength(0);
  });

  it("filters oneOf with 'must match exactly one schema'", () => {
    const transformer = transformValidationErrors();
    const errors = [
      mkError("oneOf", ".x", "must match exactly one schema in oneOf"),
    ];
    expect(transformer(errors)).toHaveLength(0);
  });

  it("filters if errors with then/else schema message", () => {
    const transformer = transformValidationErrors();
    const errors = [
      mkError("if", ".x", 'must match "then" schema'),
      mkError("if", ".y", 'must match "else" schema'),
    ];
    expect(transformer(errors)).toHaveLength(0);
  });

  it("filters then errors with then schema message", () => {
    const transformer = transformValidationErrors();
    const errors = [mkError("then", ".x", 'must match "then" schema')];
    expect(transformer(errors)).toHaveLength(0);
  });

  it("filters const errors with constant message", () => {
    const transformer = transformValidationErrors();
    const errors = [mkError("const", ".x", "must be equal to constant")];
    expect(transformer(errors)).toHaveLength(0);
  });

  it("handles empty formContext", () => {
    const transformer = transformValidationErrors(undefined);
    const errors = [mkError("required", ".x", "required")];
    expect(transformer(errors)).toEqual(errors);
  });

  it("normalizes protocol (case-insensitive)", () => {
    const transformer = transformValidationErrors({
      integrationOperationProtocolType: " HTTP ",
    });
    const errors = [mkError("oneOf", ".properties", "one of")];
    expect(transformer(errors)).toHaveLength(0);
  });
});
