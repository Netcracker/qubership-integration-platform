let mockWorkspaceFolders:
  | { uri: { path: string; fsPath: string } }[]
  | undefined;

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    workspace: {
      get workspaceFolders() {
        return mockWorkspaceFolders;
      },
    },
  }),
  { virtual: true },
);

// Three distinguishable maps: what the extension ships with, what the app that happens to be
// current has rehosted, and what one other app has. A fallback that picks the wrong one is
// invisible unless the three differ.
const SHIPPED_SCHEMA_URLS = { service: "shipped:service" };
const CURRENT_APP_SCHEMA_URLS = { service: "rehosted:current-app" };
const ACME_SCHEMA_URLS = { service: "rehosted:acme" };

const mockConfigService = {
  isConfigLoaded: jest.fn(),
  getAllConfigs: jest.fn(),
  getConfigByAppName: jest.fn(),
};

// `getConfig` answers for whichever app is current. No schema-url lookup may reach it.
const mockGetConfig = jest.fn(() => ({
  appName: "current-app",
  schemaUrls: CURRENT_APP_SCHEMA_URLS,
}));

jest.mock("../../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getInstance: jest.fn(() => mockConfigService),
    getConfig: () => mockGetConfig(),
  },
  // A getter, not the value: the factory runs while this file's own consts are still uninitialized.
  get DEFAULT_SCHEMA_URLS() {
    return SHIPPED_SCHEMA_URLS;
  },
}));

import {
  buildDefaultExtensions,
  extractAppNameFromExtension,
  getDefaultAppName,
  getDefaultExtensions,
  getExtensionsForFile,
  getSchemaUrlsForApp,
  getSchemaUrlsForFile,
  setDefaultAppName,
} from "../../../src/web/response/file/fileExtensions";

/** Keeps the by-name lookup answering from the same configs `getAllConfigs` lists. */
function loadConfigs(configs: { appName: string }[]) {
  mockConfigService.isConfigLoaded.mockReturnValue(configs.length > 0);
  mockConfigService.getAllConfigs.mockReturnValue(configs);
  mockConfigService.getConfigByAppName.mockImplementation((appName: string) =>
    configs.find((config) => config.appName === appName),
  );
}

function loadedConfig(appName: string) {
  return {
    appName,
    extensions: {
      chain: `.chain.${appName}.yaml`,
      contextService: `.context-service.${appName}.yaml`,
      mcpService: `.mcp-service.${appName}.yaml`,
      service: `.service.${appName}.yaml`,
      specificationGroup: `.specification-group.${appName}.yaml`,
      apiGroup: `.api-group.${appName}.yaml`,
      specification: `.specification.${appName}.yaml`,
      api: `.api.${appName}.yaml`,
    },
  };
}

describe("fileExtensions - api file type", () => {
  beforeEach(() => {
    setDefaultAppName("qip");
    mockWorkspaceFolders = [
      { uri: { path: "/workspace", fsPath: "/workspace" } },
    ];
    loadConfigs([]);
  });

  describe("buildDefaultExtensions", () => {
    it("adds the .api.<appName>.yaml extension alongside the existing types", () => {
      const extensions = buildDefaultExtensions("qip");
      expect(extensions.api).toBe(".api.qip.yaml");
      expect(extensions.specification).toBe(".specification.qip.yaml");
    });

    it("substitutes a non-default app name", () => {
      expect(buildDefaultExtensions("acme").api).toBe(".api.acme.yaml");
    });
  });

  // getSpecApiFiles globs the plain `.api.yaml` suffix via endsWith (collectFiles),
  // so the qip model extension must not collide with it.
  describe("no collision with the generic .api.yaml glob (getSpecApiFiles)", () => {
    it("a qip .api.qip.yaml file does not match the plain .api.yaml suffix", () => {
      const filename = `service-id${buildDefaultExtensions("qip").api}`;
      expect(filename.endsWith(".api.yaml")).toBe(false);
      expect(filename.endsWith(".api.qip.yaml")).toBe(true);
    });

    it("a plain .api.yaml import file does not match the qip .api.qip.yaml suffix", () => {
      const filename = "openapi-import.api.yaml";
      expect(filename.endsWith(".api.yaml")).toBe(true);
      expect(filename.endsWith(buildDefaultExtensions("qip").api)).toBe(false);
    });
  });

  describe("extractAppNameFromExtension", () => {
    it("extracts the app name from a .api.<app>.yaml file via the regex fallback (no config loaded)", () => {
      expect(extractAppNameFromExtension("11111111.api.qip.yaml")).toBe("qip");
    });

    it("extracts a non-default app name from a .api.<app>.yaml file", () => {
      expect(extractAppNameFromExtension("11111111.api.acme.yaml")).toBe(
        "acme",
      );
    });

    it("resolves the app name through a loaded project config, matching by endsWith", () => {
      loadConfigs([loadedConfig("myapp")]);

      expect(extractAppNameFromExtension("11111111.api.myapp.yaml")).toBe(
        "myapp",
      );
    });

    it("falls back to the default app name for a plain .api.yaml file (no collision)", () => {
      expect(extractAppNameFromExtension("openapi-import.api.yaml")).toBe(
        getDefaultAppName(),
      );
    });

    it("does not mistake a plain .api.yaml import for a loaded api config extension", () => {
      loadConfigs([loadedConfig("qip")]);

      expect(extractAppNameFromExtension("openapi-import.api.yaml")).toBe(
        getDefaultAppName(),
      );
    });
  });

  describe("getExtensionsForFile / getDefaultExtensions - discovery", () => {
    it("includes the api extension in the default set", () => {
      expect(getDefaultExtensions().api).toBe(".api.qip.yaml");
    });

    it("propagates the api extension when resolved from a loaded project config", () => {
      loadConfigs([loadedConfig("qip")]);

      const extensions = getExtensionsForFile("11111111.api.qip.yaml");
      expect(extensions.appName).toBe("qip");
      expect(extensions.api).toBe(".api.qip.yaml");
    });
  });
});

describe("fileExtensions - apiGroup file type", () => {
  beforeEach(() => {
    setDefaultAppName("qip");
    mockWorkspaceFolders = [
      { uri: { path: "/workspace", fsPath: "/workspace" } },
    ];
    loadConfigs([]);
  });

  describe("buildDefaultExtensions", () => {
    it("adds the .api-group.<appName>.yaml extension alongside the legacy specificationGroup one", () => {
      const extensions = buildDefaultExtensions("qip");
      expect(extensions.apiGroup).toBe(".api-group.qip.yaml");
      expect(extensions.specificationGroup).toBe(
        ".specification-group.qip.yaml",
      );
    });

    it("substitutes a non-default app name", () => {
      expect(buildDefaultExtensions("acme").apiGroup).toBe(
        ".api-group.acme.yaml",
      );
    });
  });

  describe("extractAppNameFromExtension", () => {
    it("extracts the app name from a .api-group.<app>.yaml file via the regex fallback", () => {
      expect(extractAppNameFromExtension("group-1.api-group.qip.yaml")).toBe(
        "qip",
      );
    });

    it("extracts a non-default app name from a .api-group.<app>.yaml file", () => {
      expect(extractAppNameFromExtension("group-1.api-group.acme.yaml")).toBe(
        "acme",
      );
    });
  });

  describe("getExtensionsForFile / getDefaultExtensions - discovery", () => {
    it("includes the apiGroup extension in the default set", () => {
      expect(getDefaultExtensions().apiGroup).toBe(".api-group.qip.yaml");
    });

    it("propagates the apiGroup extension when resolved from a loaded project config", () => {
      loadConfigs([loadedConfig("qip")]);

      const extensions = getExtensionsForFile("group-1.api-group.qip.yaml");
      expect(extensions.appName).toBe("qip");
      expect(extensions.apiGroup).toBe(".api-group.qip.yaml");
    });
  });
});

// A schema url is stamped on the files this extension writes, so the map has to come from the
// app the *file* belongs to. Falling back to the current app's config hands a file of one
// installation the rehosted urls of another.
describe("fileExtensions - schemaUrls", () => {
  const acme = { ...loadedConfig("acme"), schemaUrls: ACME_SCHEMA_URLS };

  beforeEach(() => {
    setDefaultAppName("qip");
    mockWorkspaceFolders = [
      { uri: { path: "/workspace", fsPath: "/workspace" } },
    ];
    loadConfigs([acme]);
  });

  it("answers with the urls of the app the file belongs to", () => {
    expect(getSchemaUrlsForFile("svc-1.service.acme.yaml")).toBe(
      ACME_SCHEMA_URLS,
    );
    expect(getSchemaUrlsForApp("acme")).toBe(ACME_SCHEMA_URLS);
  });

  it("falls back to the shipped defaults for an app no config answers for", () => {
    expect(getSchemaUrlsForFile("svc-1.service.other.yaml")).toBe(
      SHIPPED_SCHEMA_URLS,
    );
    expect(getSchemaUrlsForApp("other")).toBe(SHIPPED_SCHEMA_URLS);
  });

  it("falls back to the shipped defaults with no config loaded", () => {
    loadConfigs([]);

    expect(getSchemaUrlsForApp("acme")).toBe(SHIPPED_SCHEMA_URLS);
  });

  it("falls back to the shipped defaults with no file in context", () => {
    expect(getSchemaUrlsForFile()).toBe(SHIPPED_SCHEMA_URLS);
  });

  it("never reads the config of whichever app is current", () => {
    getSchemaUrlsForFile("svc-1.service.other.yaml");
    getSchemaUrlsForApp("other");
    getSchemaUrlsForFile();

    expect(mockGetConfig).not.toHaveBeenCalled();
  });
});
