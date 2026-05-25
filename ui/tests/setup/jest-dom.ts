/**
 * Jest setup for jsdom: registers @testing-library/jest-dom matchers
 * (toBeInTheDocument, toHaveValue, …) and mocks window.matchMedia
 * (required by Ant Design). Only runs when window exists
 * (e.g. tests with @jest-environment jsdom).
 */
import "@testing-library/jest-dom/jest-globals";
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
