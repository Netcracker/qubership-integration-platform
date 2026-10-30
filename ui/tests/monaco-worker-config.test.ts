type WorkerConfig = typeof import("../src/monaco-worker-config");

function loadIsolated(): WorkerConfig {
  let loaded: WorkerConfig | null = null;
  jest.isolateModules(() => {
    loaded = require("../src/monaco-worker-config");
  });
  return loaded!;
}

function setPage(href: string) {
  const location = new URL(href);
  Object.defineProperty(global, "window", {
    value: { location: { href: location.href, origin: location.origin } },
    configurable: true,
  });
}

describe("monaco-worker-config", () => {
  const workerMock = jest.fn();
  const createObjectURL = jest.spyOn(URL, "createObjectURL");

  beforeEach(() => {
    workerMock.mockReset();
    createObjectURL.mockReset().mockReturnValue("blob:page/1");
    Object.defineProperty(global, "Worker", {
      value: workerMock,
      configurable: true,
    });
  });

  afterEach(() => {
    delete (global as Record<string, unknown>).window;
    delete (global as Record<string, unknown>).Worker;
  });

  it("should leave worker construction to Monaco when the base is on the page's origin", () => {
    setPage("https://example.com/chains");
    loadIsolated().setMonacoWorkerBasePath(
      "https://example.com/assets/monaco-work/",
    );

    const env = window.MonacoEnvironment!;
    expect(env.getWorker).toBeUndefined();
    expect(env.getWorkerUrl!("workerMain.js", "json")).toBe(
      "https://example.com/assets/monaco-work/json.worker..bundle.js",
    );
  });

  it("should load the worker through a blob when the base is on another origin", async () => {
    setPage("http://webview.localhost:3917/index.html");
    loadIsolated().setMonacoWorkerBasePath(
      "http://localhost:3917/qip-ui/assets/monaco-work",
    );

    window.MonacoEnvironment!.getWorker!("workerMain.js", "typescript");

    expect(workerMock).toHaveBeenCalledWith("blob:page/1", {
      name: "typescript",
    });
    const blob = createObjectURL.mock.calls[0][0] as Blob;
    expect(await blob.text()).toBe(
      'importScripts("http://localhost:3917/qip-ui/assets/monaco-work/ts.worker..bundle.js");',
    );
  });
});
