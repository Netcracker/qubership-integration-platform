import { describe, test, expect, beforeEach, afterEach } from "@jest/globals";
import { getMaasDefaultNamespace } from "../../../../src/components/dev_tools/maas/types";

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
