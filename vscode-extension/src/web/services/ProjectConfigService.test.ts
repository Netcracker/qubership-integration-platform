import * as fs from "node:fs";
import * as path from "node:path";
import { ExtensionContext, Uri } from "vscode";
import { DEFAULT_APP_NAME } from "../constants/appName";
import { ProjectConfigService } from "./ProjectConfigService";

const mockReadFileContent = jest.fn();

jest.mock("../response/file/fileApiProvider", () => ({
  fileApi: {
    readFileContent: (...args: unknown[]) => mockReadFileContent(...args),
  },
}));

const EMBEDDED_CONFIG = fs.readFileSync(
  path.join(__dirname, "../../../configs/default.config.cip.yaml"),
  "utf8",
);

const extensionContext = {
  extensionUri: Uri.file("/extension"),
} as unknown as ExtensionContext;

describe("ProjectConfigService", () => {
  const service = ProjectConfigService.getInstance();

  beforeEach(() => {
    jest.spyOn(console, "log").mockImplementation(() => {});
    jest.spyOn(console, "warn").mockImplementation(() => {});
    mockReadFileContent.mockRejectedValue(new Error("file not found"));
    service.setContext(undefined as unknown as ExtensionContext);
    service.clearCache();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  describe("loadEmbeddedConfig", () => {
    it("loads the embedded cip config, which matches the built-in default", async () => {
      mockReadFileContent.mockResolvedValue(EMBEDDED_CONFIG);
      service.setContext(extensionContext);

      await service.loadEmbeddedConfig();

      expect(service.getConfigByAppName(DEFAULT_APP_NAME)).toEqual(
        service.buildDefaultConfig(DEFAULT_APP_NAME),
      );
    });

    it("falls back to the built-in cip config when no extension context is set", async () => {
      await service.loadEmbeddedConfig();

      expect(mockReadFileContent).not.toHaveBeenCalled();
      expect(
        service.getConfigByAppName(DEFAULT_APP_NAME)?.extensions.chain,
      ).toBe(".chain.cip.yaml");
    });

    it("falls back to the built-in cip config when the embedded file cannot be read", async () => {
      service.setContext(extensionContext);

      await service.loadEmbeddedConfig();

      expect(mockReadFileContent).toHaveBeenCalled();
      expect(
        service.getConfigByAppName(DEFAULT_APP_NAME)?.extensions.chain,
      ).toBe(".chain.cip.yaml");
    });
  });

  describe("external configs", () => {
    it("switches from the default app to the first registered external app", () => {
      service.registerExternalConfig("acme", {});

      expect(service.getCurrentAppName()).toBe("acme");
      expect(service.getCurrentConfig().extensions.chain).toBe(
        ".chain.acme.yaml",
      );
    });

    it("keeps the external app when the default app is requested afterwards", async () => {
      service.registerExternalConfig("acme", {});

      await service.setCurrentContext(DEFAULT_APP_NAME, Uri.file("/workspace"));

      expect(service.getCurrentAppName()).toBe("acme");
    });

    it("returns to the default app when the current external config is unregistered", () => {
      service.registerExternalConfig("acme", {});

      service.unregisterExternalConfig("acme");

      expect(service.getCurrentAppName()).toBe(DEFAULT_APP_NAME);
      expect(service.getCurrentConfig().extensions.chain).toBe(
        ".chain.cip.yaml",
      );
    });
  });
});
