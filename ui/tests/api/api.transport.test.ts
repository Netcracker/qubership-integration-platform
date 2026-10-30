/** @jest-environment jsdom */
type WebviewWindow = Window & { acquireVsCodeApi?: unknown };

function loadApiModules() {
  let modules: {
    api: unknown;
    VSCodeExtensionApi: new () => unknown;
    RestApi: new () => unknown;
  } | null = null;
  jest.isolateModules(() => {
    modules = {
      api: (require("../../src/api/api") as { api: unknown }).api,
      VSCodeExtensionApi: (
        require("../../src/api/rest/vscodeExtensionApi") as {
          VSCodeExtensionApi: new () => unknown;
        }
      ).VSCodeExtensionApi,
      RestApi: (
        require("../../src/api/rest/restApi") as {
          RestApi: new () => unknown;
        }
      ).RestApi,
    };
  });
  return modules!;
}

describe("api transport", () => {
  afterEach(() => {
    delete (window as WebviewWindow).acquireVsCodeApi;
  });

  it("should use VSCodeExtensionApi when the page is a VS Code webview", () => {
    (window as WebviewWindow).acquireVsCodeApi = jest.fn(() => ({
      postMessage: jest.fn(),
      getState: jest.fn(),
      setState: jest.fn(),
    }));

    const { api, VSCodeExtensionApi } = loadApiModules();

    expect(api).toBeInstanceOf(VSCodeExtensionApi);
  });

  it("should use RestApi when acquireVsCodeApi is not defined", () => {
    const { api, RestApi } = loadApiModules();

    expect(api).toBeInstanceOf(RestApi);
  });
});
