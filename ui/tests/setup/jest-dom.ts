/**
 * Jest setup for jsdom: mocks window.matchMedia (required by Ant Design).
 * Only runs when window exists (e.g. tests with @jest-environment jsdom).
 */
import { jest } from "@jest/globals";

if (typeof window !== "undefined") {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: jest.fn().mockImplementation((query: unknown) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: jest.fn(),
      removeListener: jest.fn(),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
      dispatchEvent: jest.fn(),
    })),
  });
}
