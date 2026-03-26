import { describe, test, expect, beforeEach, afterEach } from "@jest/globals";
import {
  getMaasDefaultNamespace,
  isRabbitMQFormValid,
  isKafkaFormValid,
} from "../../../../src/components/dev_tools/maas/types";

describe("getMaasDefaultNamespace", () => {
  let originalWindow: unknown;

  beforeEach(() => {
    originalWindow = (globalThis as unknown as { window?: unknown }).window;
  });

  afterEach(() => {
    if (originalWindow !== undefined) {
      (globalThis as unknown as { window?: unknown }).window = originalWindow;
    } else {
      delete (globalThis as unknown as { window?: unknown }).window;
    }
  });

  test("should return empty string when window is not defined", () => {
    delete (globalThis as unknown as { window?: unknown }).window;
    expect(getMaasDefaultNamespace()).toBe("");
  });

  test("returns empty string when window has no routes", () => {
    (globalThis as unknown as { window?: unknown }).window = {};
    expect(getMaasDefaultNamespace()).toBe("");
  });

  test("returns empty string when window.routes has no namespace", () => {
    (globalThis as unknown as { window?: unknown }).window = { routes: {} };
    expect(getMaasDefaultNamespace()).toBe("");
  });

  test("returns namespace when window.routes.namespace is set", () => {
    (globalThis as unknown as { window?: unknown }).window = {
      routes: { namespace: "my-namespace" },
    };
    expect(getMaasDefaultNamespace()).toBe("my-namespace");
  });
});

describe("isRabbitMQFormValid", () => {
  test("returns false for undefined", () => {
    expect(isRabbitMQFormValid(undefined)).toBe(false);
  });

  test("returns false for empty object", () => {
    expect(isRabbitMQFormValid({})).toBe(false);
  });

  test("returns false when namespace is missing", () => {
    expect(
      isRabbitMQFormValid({
        vhost: "public",
        exchange: "ex",
        queue: "q",
      }),
    ).toBe(false);
  });

  test("returns false when vhost is missing", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        exchange: "ex",
        queue: "q",
      }),
    ).toBe(false);
  });

  test("returns false when namespace is whitespace-only", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "  ",
        vhost: "public",
        exchange: "ex",
      }),
    ).toBe(false);
  });

  test("returns false when vhost is whitespace-only", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "  ",
        exchange: "ex",
      }),
    ).toBe(false);
  });

  test("returns false when both exchange and queue are empty", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "public",
        exchange: "",
        queue: "",
      }),
    ).toBe(false);
  });

  test("returns true with only exchange", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "public",
        exchange: "ex",
        queue: "",
      }),
    ).toBe(true);
  });

  test("returns true with only queue", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "public",
        exchange: "",
        queue: "q",
      }),
    ).toBe(true);
  });

  test("returns true with both exchange and queue", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "public",
        exchange: "ex",
        queue: "q",
      }),
    ).toBe(true);
  });

  test("returns false when routingKey is set but queue is missing", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "public",
        exchange: "ex",
        queue: "",
        routingKey: "rk",
      }),
    ).toBe(false);
  });

  test("returns false when routingKey is set but exchange is missing", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "public",
        exchange: "",
        queue: "q",
        routingKey: "rk",
      }),
    ).toBe(false);
  });

  test("returns true when routingKey is set with both exchange and queue", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "public",
        exchange: "ex",
        queue: "q",
        routingKey: "rk",
      }),
    ).toBe(true);
  });

  test("trims whitespace from exchange, queue and routingKey", () => {
    expect(
      isRabbitMQFormValid({
        namespace: "ns",
        vhost: "public",
        exchange: "  ",
        queue: "  ",
      }),
    ).toBe(false);
  });
});

describe("isKafkaFormValid", () => {
  test("returns false for undefined", () => {
    expect(isKafkaFormValid(undefined)).toBe(false);
  });

  test("returns false for empty object", () => {
    expect(isKafkaFormValid({})).toBe(false);
  });

  test("returns false when namespace is missing", () => {
    expect(isKafkaFormValid({ topicClassifierName: "topic" })).toBe(false);
  });

  test("returns false when topicClassifierName is missing", () => {
    expect(isKafkaFormValid({ namespace: "ns" })).toBe(false);
  });

  test("returns false when namespace is whitespace-only", () => {
    expect(
      isKafkaFormValid({ namespace: "  ", topicClassifierName: "topic" }),
    ).toBe(false);
  });

  test("returns false when topicClassifierName is whitespace-only", () => {
    expect(
      isKafkaFormValid({ namespace: "ns", topicClassifierName: "  " }),
    ).toBe(false);
  });

  test("returns true with valid namespace and topicClassifierName", () => {
    expect(
      isKafkaFormValid({ namespace: "ns", topicClassifierName: "topic" }),
    ).toBe(true);
  });
});
