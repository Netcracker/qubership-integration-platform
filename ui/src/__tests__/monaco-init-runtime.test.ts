import { initExternalMonaco, initBundledMonaco } from "../monaco-init-runtime";
import { configureMonacoLoader } from "../monaco-loader-config";
import {
  getMonacoWorkerBasePath,
  setMonacoWorkerBasePath,
} from "../monaco-worker-config";

jest.mock("../monaco-loader-config", () => ({
  configureMonacoLoader: jest.fn(),
}));

jest.mock("../monaco-worker-config", () => ({
  getMonacoWorkerBasePath: jest.fn(),
  setMonacoWorkerBasePath: jest.fn(),
}));

jest.mock("monaco-editor", () => ({}));

const mockedConfigureMonacoLoader = configureMonacoLoader as jest.Mock;
const mockedGetMonacoWorkerBasePath = getMonacoWorkerBasePath as jest.Mock;
const mockedSetMonacoWorkerBasePath = setMonacoWorkerBasePath as jest.Mock;

describe("monaco-init-runtime", () => {
  beforeEach(() => {
    mockedConfigureMonacoLoader.mockClear();
    mockedGetMonacoWorkerBasePath.mockClear();
    mockedSetMonacoWorkerBasePath.mockClear();
  });

  describe("initExternalMonaco", () => {
    it("configures loader with monaco and sets default worker base path", () => {
      mockedGetMonacoWorkerBasePath.mockReturnValueOnce(null);
      Object.defineProperty(global, "window", {
        value: { location: { origin: "https://example.com" } },
        configurable: true,
      });

      initExternalMonaco();

      expect(mockedConfigureMonacoLoader).toHaveBeenCalledTimes(1);
      expect(mockedSetMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://example.com/assets/monaco-work",
      );
    });

    it("normalizes trailing slashes in origin", () => {
      mockedGetMonacoWorkerBasePath.mockReturnValueOnce(null);
      Object.defineProperty(global, "window", {
        value: { location: { origin: "https://example.com///" } },
        configurable: true,
      });

      initExternalMonaco();

      expect(mockedSetMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://example.com/assets/monaco-work",
      );
    });

    it("does not override worker base path if already set", () => {
      mockedGetMonacoWorkerBasePath.mockReturnValueOnce("https://custom/base");
      Object.defineProperty(global, "window", {
        value: { location: { origin: "https://example.com" } },
        configurable: true,
      });

      initExternalMonaco();

      expect(mockedSetMonacoWorkerBasePath).not.toHaveBeenCalled();
    });
  });

  describe("initBundledMonaco", () => {
    it("configures loader and uses global base when provided", () => {
      mockedGetMonacoWorkerBasePath.mockReturnValueOnce(null);

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
      expect(mockedSetMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://host/assets/monaco",
      );
    });

    it("derives worker base path from current script URL", () => {
      mockedGetMonacoWorkerBasePath.mockReturnValueOnce(null);

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

      expect(mockedSetMonacoWorkerBasePath).toHaveBeenCalledWith(
        "https://host/app/dist-lib/assets/monaco-work",
      );
    });
  });
});
