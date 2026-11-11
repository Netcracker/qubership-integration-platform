import {
  initExternalMonaco,
  initBundledMonaco,
} from "../src/monaco-init-runtime";
import { configureMonacoLoader } from "../src/monaco-loader-config";
import * as workerConfig from "../src/monaco-worker-config";

jest.mock("../src/monaco-loader-config", () => ({
  configureMonacoLoader: jest.fn(),
}));

jest.mock("../src/monaco-worker-config", () => ({
  getMonacoWorkerBasePath: jest.fn(),
  setMonacoWorkerBasePath: jest.fn(),
}));

jest.mock("monaco-editor", () => ({
  editor: { create: jest.fn(), createDiffEditor: jest.fn() },
}));

const mockedConfigureMonacoLoader = configureMonacoLoader as jest.Mock;

describe("monaco-init-runtime", () => {
  beforeEach(() => {
    mockedConfigureMonacoLoader.mockReset();
    (workerConfig.getMonacoWorkerBasePath as jest.Mock).mockReset();
    (workerConfig.setMonacoWorkerBasePath as jest.Mock).mockReset();
    // Reset globals to prevent leaking between tests
    delete (global as Record<string, unknown>).window;
    delete (global as Record<string, unknown>).document;
  });

  describe("initExternalMonaco", () => {
    it("configures loader with monaco and sets default worker base path", () => {
      (workerConfig.getMonacoWorkerBasePath as jest.Mock).mockReturnValue(null);
      Object.defineProperty(global, "window", {
        value: { location: { origin: "https://example.com" } },
        configurable: true,
      });

      initExternalMonaco();

      expect(mockedConfigureMonacoLoader).toHaveBeenCalledTimes(1);
      expect(workerConfig.setMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://example.com/assets/monaco-work",
      );
    });

    it("normalizes trailing slashes in origin", () => {
      (workerConfig.getMonacoWorkerBasePath as jest.Mock).mockReturnValue(null);
      Object.defineProperty(global, "window", {
        value: { location: { origin: "https://example.com///" } },
        configurable: true,
      });

      initExternalMonaco();

      expect(workerConfig.setMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://example.com/assets/monaco-work",
      );
    });

    it("does not override worker base path if already set", () => {
      (workerConfig.getMonacoWorkerBasePath as jest.Mock).mockReturnValue(
        "https://custom/base",
      );
      Object.defineProperty(global, "window", {
        value: { location: { origin: "https://example.com" } },
        configurable: true,
      });

      initExternalMonaco();

      expect(workerConfig.setMonacoWorkerBasePath).not.toHaveBeenCalled();
    });
  });

  describe("initBundledMonaco", () => {
    it("configures loader and uses global base when provided", () => {
      (workerConfig.getMonacoWorkerBasePath as jest.Mock).mockReturnValue(null);

      Object.defineProperty(global, "window", {
        value: {
          location: {},
          __CIP_MONACO_WORKER_BASE__: "https://host/assets/monaco",
        },
        configurable: true,
      });

      initBundledMonaco("https://host/app/dist-lib/index.bundled.es.js");

      expect(mockedConfigureMonacoLoader).toHaveBeenCalledTimes(1);
      expect(workerConfig.setMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://host/assets/monaco",
      );
    });

    it("derives worker base path from the bundle URL", () => {
      (workerConfig.getMonacoWorkerBasePath as jest.Mock).mockReturnValue(null);

      Object.defineProperty(global, "window", {
        value: { location: {} },
        configurable: true,
      });
      initBundledMonaco("https://host/app/dist-lib/index.bundled.es.js");

      expect(workerConfig.setMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://host/app/dist-lib/assets/monaco-work",
      );
    });
  });
  describe("webview compat patch", () => {
    function loadIsolated() {
      let loaded: {
        runtime: typeof import("../src/monaco-init-runtime");
        monacoApi: {
          editor: { create: jest.Mock; createDiffEditor: jest.Mock };
        };
      } | null = null;
      jest.isolateModules(() => {
        loaded = {
          runtime: require("../src/monaco-init-runtime"),
          monacoApi: require("monaco-editor"),
        };
      });
      return loaded!;
    }

    it("should turn off edit context when acquireVsCodeApi is defined", () => {
      Object.defineProperty(global, "window", {
        value: {
          location: { protocol: "http:", origin: "http://x.localhost:3917" },
          acquireVsCodeApi: jest.fn(),
        },
        configurable: true,
      });
      const { runtime, monacoApi } = loadIsolated();
      const originalCreate = monacoApi.editor.create;

      runtime.initExternalMonaco();
      monacoApi.editor.create({}, { readOnly: true });

      expect(monacoApi.editor.create).not.toBe(originalCreate);
      expect(originalCreate).toHaveBeenCalledWith(
        {},
        { editContext: false, readOnly: true },
        undefined,
      );
    });

    it("should leave the editor factories alone outside a webview", () => {
      Object.defineProperty(global, "window", {
        value: { location: { origin: "https://example.com" } },
        configurable: true,
      });
      const { runtime, monacoApi } = loadIsolated();
      const originalCreate = monacoApi.editor.create;

      runtime.initExternalMonaco();

      expect(monacoApi.editor.create).toBe(originalCreate);
    });
  });
});
