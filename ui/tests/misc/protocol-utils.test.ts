import { describe, it, expect } from "@jest/globals";
import {
  isAmqpProtocol,
  isKafkaProtocol,
  normalizeProtocol,
  protocolForContext,
} from "../../src/misc/protocol-utils";

describe("normalizeProtocol", () => {
  it("should lower-case and trim the value", () => {
    expect(normalizeProtocol("  KAFKA ")).toBe("kafka");
  });

  it("should pass an empty value through unchanged", () => {
    expect(normalizeProtocol("")).toBe("");
  });
});

/**
 * The value published into a chain element's form context. Every reader compares it against a
 * transport name, so a blank matches nothing: `?? "http"` used to let the empty string through
 * because nullish coalescing does not catch it.
 */
describe("protocolForContext", () => {
  it("should normalize a declared protocol", () => {
    expect(protocolForContext(" KAFKA ")).toBe("kafka");
  });

  it("should fall back to http when the service declares no protocol", () => {
    expect(protocolForContext(undefined)).toBe("http");
  });

  it("should fall back to http when the protocol is blank", () => {
    expect(protocolForContext("")).toBe("http");
    expect(protocolForContext("   ")).toBe("http");
  });

  it("should keep kafka and amqp distinguishable", () => {
    // The operation payload cannot supply this: operationKind reads "asyncapi" for both.
    expect(isKafkaProtocol(protocolForContext("kafka"))).toBe(true);
    expect(isAmqpProtocol(protocolForContext("amqp"))).toBe(true);
    expect(isKafkaProtocol(protocolForContext("amqp"))).toBe(false);
  });
});
