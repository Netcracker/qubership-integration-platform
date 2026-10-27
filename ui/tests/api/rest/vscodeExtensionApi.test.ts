/**
 * @jest-environment jsdom
 */
import { VSCodeExtensionApi } from "../../../src/api/rest/vscodeExtensionApi";

describe("VSCodeExtensionApi", () => {
  let api: VSCodeExtensionApi;

  beforeEach(() => {
    (globalThis as Record<string, unknown>).acquireVsCodeApi = () => ({
      postMessage: jest.fn(),
    });
    api = new VSCodeExtensionApi();
  });

  // The extension has no handler for these, and no screen it renders calls them.
  test("should throw when importSystems is called", () => {
    expect(() => api.importSystems()).toThrow(
      "Method importSystems not implemented.",
    );
  });

  test("should throw when createContextService is called", () => {
    expect(() => api.createContextService()).toThrow(
      "Method createContextService not implemented.",
    );
  });
});
