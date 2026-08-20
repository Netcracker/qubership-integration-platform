// The per-type file extensions #553 wrote, still read though no write emits them. The risk this
// suite guards is a regex or config surface that quietly stops matching one: such a file falls back
// to the default app name and its services go missing, with no error.

import * as fs from "fs";
import * as path from "path";
import * as yaml from "yaml";

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

const mockConfigService = {
  isConfigLoaded: jest.fn(),
  getAllConfigs: jest.fn(),
  getConfigByAppName: jest.fn(),
};

jest.mock("../../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getInstance: jest.fn(() => mockConfigService),
  },
}));

import {
  buildDefaultExtensions,
  extractAppNameFromExtension,
  getDefaultAppName,
  getDefaultExtensions,
  getExtensionsForFile,
  setDefaultAppName,
} from "../../../src/web/response/file/fileExtensions";

function loadedConfig(appName: string) {
  return {
    appName,
    extensions: buildDefaultExtensions(appName),
  };
}

/** Keeps the by-name lookup answering from the same configs `getAllConfigs` lists. */
function loadConfigs(configs: { appName: string }[]) {
  mockConfigService.isConfigLoaded.mockReturnValue(configs.length > 0);
  mockConfigService.getAllConfigs.mockReturnValue(configs);
  mockConfigService.getConfigByAppName.mockImplementation((appName: string) =>
    configs.find((config) => config.appName === appName),
  );
}

beforeEach(() => {
  setDefaultAppName("qip");
  mockWorkspaceFolders = [
    { uri: { path: "/workspace", fsPath: "/workspace" } },
  ];
  loadConfigs([]);
});

describe("buildDefaultExtensions - plain service types", () => {
  it("carries a per-type extension alongside the current plain one", () => {
    const extensions = buildDefaultExtensions("qip");

    expect(extensions.service).toBe(".service.qip.yaml");
    expect(extensions.externalService).toBe(".external-service.qip.yaml");
    expect(extensions.internalService).toBe(".internal-service.qip.yaml");
    expect(extensions.implementedService).toBe(".implemented-service.qip.yaml");
  });

  it("substitutes a non-default app name into each of them", () => {
    const extensions = buildDefaultExtensions("acme");

    expect(extensions.externalService).toBe(".external-service.acme.yaml");
    expect(extensions.internalService).toBe(".internal-service.acme.yaml");
    expect(extensions.implementedService).toBe(
      ".implemented-service.acme.yaml",
    );
  });

  it("exposes the new extensions through the memoized default set", () => {
    expect(getDefaultExtensions().externalService).toBe(
      ".external-service.qip.yaml",
    );
    expect(getDefaultExtensions().internalService).toBe(
      ".internal-service.qip.yaml",
    );
    expect(getDefaultExtensions().implementedService).toBe(
      ".implemented-service.qip.yaml",
    );
  });

  // A per-type extension must not end-match the current one, or every such file would read as a
  // plain `.service.` one.
  it("keeps a per-type extension from end-matching the current .service. one", () => {
    const ext = buildDefaultExtensions("qip");

    expect(`svc-1${ext.externalService}`.endsWith(ext.service)).toBe(false);
    expect(`svc-1${ext.internalService}`.endsWith(ext.service)).toBe(false);
    expect(`svc-1${ext.implementedService}`.endsWith(ext.service)).toBe(false);
    expect(`svc-1${ext.service}`.endsWith(ext.service)).toBe(true);
  });
});

describe("extractAppNameFromExtension - equivalence with the pre-#553 pattern", () => {
  // The pattern as it stood before the plain service types were added. Every file name it used to
  // resolve must resolve to the same app name now.
  const legacyPattern =
    /\.((?:context-)?service\d*|chain\d*|(?:specification|api)(?:-group)?\d*)\.([^.]+)\.yaml$/;

  const legacyCases = [
    "svc-1.service.qip.yaml",
    "svc-1.service.acme.yaml",
    "svc-1.service2.acme.yaml",
    "ctx-1.context-service.qip.yaml",
    "ctx-1.context-service.acme.yaml",
    // Never matched the alternation; it resolves through the config path instead.
    "mcp-1.mcp-service.acme.yaml",
    "chain-1.chain.qip.yaml",
    "chain-1.chain.acme.yaml",
    "api-1.api.acme.yaml",
    "api-1.specification.acme.yaml",
    "grp-1.api-group.acme.yaml",
    "grp-1.specification-group.acme.yaml",
    // Plain import files and unrelated names: no match, so the default app name wins.
    "openapi-import.api.yaml",
    "openapi-import.yaml",
    "notes.md",
    "service-orders.txt",
  ];

  it.each(legacyCases)(
    "resolves %s exactly as the pre-#553 pattern did",
    (filename) => {
      const legacyMatch = filename.match(legacyPattern);
      const expected = legacyMatch ? legacyMatch[2] : getDefaultAppName();

      expect(extractAppNameFromExtension(filename)).toBe(expected);
    },
  );
});

describe("extractAppNameFromExtension - plain service types", () => {
  it.each([
    ["svc-1.external-service.acme.yaml", "acme"],
    ["svc-1.internal-service.acme.yaml", "acme"],
    ["svc-1.implemented-service.acme.yaml", "acme"],
    ["svc-1.external-service.qip.yaml", "qip"],
    ["svc-1.internal-service.qip.yaml", "qip"],
    ["svc-1.implemented-service.qip.yaml", "qip"],
  ])("extracts the app name from %s", (filename, appName) => {
    expect(extractAppNameFromExtension(filename)).toBe(appName);
  });

  // Autodiscovery mints ids from Kubernetes service names, so `service-orders` is a real id.
  it("extracts the app name from a per-type file whose id starts with service-", () => {
    expect(
      extractAppNameFromExtension("service-orders.external-service.acme.yaml"),
    ).toBe("acme");
  });

  it("resolves an mcp service through a loaded config, as it always has", () => {
    loadConfigs([loadedConfig("acme")]);

    expect(extractAppNameFromExtension("mcp-1.mcp-service.acme.yaml")).toBe(
      "acme",
    );
  });

  it("resolves a per-type service name through a loaded config", () => {
    loadConfigs([loadedConfig("acme")]);

    expect(
      extractAppNameFromExtension("svc-1.implemented-service.acme.yaml"),
    ).toBe("acme");
  });
});

describe("getExtensionsForFile - plain service types", () => {
  it("propagates the new extensions from a loaded project config", () => {
    loadConfigs([loadedConfig("acme")]);

    const extensions = getExtensionsForFile("svc-1.external-service.acme.yaml");

    expect(extensions.appName).toBe("acme");
    expect(extensions.externalService).toBe(".external-service.acme.yaml");
    expect(extensions.internalService).toBe(".internal-service.acme.yaml");
    expect(extensions.implementedService).toBe(
      ".implemented-service.acme.yaml",
    );
  });

  it("falls back to the defaults for the resolved app name with no config loaded", () => {
    const extensions = getExtensionsForFile("svc-1.internal-service.acme.yaml");

    expect(extensions.appName).toBe("acme");
    expect(extensions.internalService).toBe(".internal-service.acme.yaml");
  });
});

// The embedded config wins over the hardcoded defaults, so a key missing from the YAML is a
// silent fallback to the default app name for every project that ships its own config.
describe("shipped config files", () => {
  const newKeys = ["externalService", "internalService", "implementedService"];

  function readConfigFile(relativePath: string) {
    return yaml.parse(
      fs.readFileSync(
        path.join(__dirname, "..", "..", "..", relativePath),
        "utf8",
      ),
    );
  }

  it("the embedded default config declares the new extensions and schema URLs", () => {
    const config = readConfigFile("configs/default.config.qip.yaml").configs
      .qip;

    expect(config.extensions.externalService).toBe(
      ".external-service.qip.yaml",
    );
    expect(config.extensions.internalService).toBe(
      ".internal-service.qip.yaml",
    );
    expect(config.extensions.implementedService).toBe(
      ".implemented-service.qip.yaml",
    );
    expect(config.schemaUrls.externalService).toBe(
      "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
    );
    expect(config.schemaUrls.internalService).toBe(
      "http://qubership.org/schemas/product/qip/internal-service.schema.yaml",
    );
    expect(config.schemaUrls.implementedService).toBe(
      "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml",
    );
  });

  it("every block of the example config declares the new extensions and schema URLs", () => {
    const configs = readConfigFile(".config.qip.yaml.example").configs;

    expect(Object.keys(configs).length).toBeGreaterThan(1);

    for (const [appName, config] of Object.entries<any>(configs)) {
      for (const key of newKeys) {
        expect([appName, key, config.extensions[key]]).toEqual([
          appName,
          key,
          `.${key.replace("Service", "-service")}.\${appName}.yaml`,
        ]);
        expect([appName, key, config.schemaUrls[key]]).toEqual([
          appName,
          key,
          `http://qubership.org/schemas/product/\${appName}/${key.replace("Service", "-service")}`,
        ]);
      }
    }
  });
});
