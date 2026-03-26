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

jest.mock("monaco-editor", () => ({}));

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
        value: { __QIP_MONACO_WORKER_BASE__: "https://host/assets/monaco" },
        configurable: true,
      });
      Object.defineProperty(global, "document", {
        value: {},
        configurable: true,
      });

      initBundledMonaco();

      expect(mockedConfigureMonacoLoader).toHaveBeenCalledTimes(1);
      expect(workerConfig.setMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://host/assets/monaco",
      );
    });

    it("derives worker base path from current script URL", () => {
      (workerConfig.getMonacoWorkerBasePath as jest.Mock).mockReturnValue(null);

      Object.defineProperty(global, "window", {
        value: {},
        configurable: true,
      });
      Object.defineProperty(global, "document", {
        value: {
          currentScript: {
            src: "https://host/app/dist-lib/index.bundled.es.js",
          },
        },
        configurable: true,
      });

      initBundledMonaco();

      expect(workerConfig.setMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://host/app/dist-lib/assets/monaco-work",
      );
    });
  });
});
