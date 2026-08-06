// The single resolver every service-file site routes through. What it must never do is let one
// postfix shadow another: a misread name silently drops the service from a list or files it under
// the wrong type, with no error anywhere. The real `fileExtensions` module is used throughout, so
// the app-name resolution behind an unqualified call is under test as well.

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
};

jest.mock("../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getInstance: jest.fn(() => mockConfigService),
  },
}));

import {
  isAnyServiceFile,
  plainServiceExtensions,
  serviceExtensionForType,
  serviceTypeFromUri,
  ServiceExtensions,
} from "../src/web/response/file/serviceFileType";
import { IntegrationSystemType } from "../src/web/api-services/servicesTypes";
import {
  buildDefaultExtensions,
  setDefaultAppName,
} from "../src/web/response/file/fileExtensions";

const qip: ServiceExtensions = buildDefaultExtensions("qip");
const acme: ServiceExtensions = buildDefaultExtensions("acme");

beforeEach(() => {
  setDefaultAppName("qip");
  mockWorkspaceFolders = [
    { uri: { path: "/workspace", fsPath: "/workspace" } },
  ];
  mockConfigService.isConfigLoaded.mockReturnValue(false);
  mockConfigService.getAllConfigs.mockReturnValue([]);
});

describe("serviceTypeFromUri", () => {
  it.each([
    ["svc-1.external-service.qip.yaml", IntegrationSystemType.EXTERNAL],
    ["svc-1.internal-service.qip.yaml", IntegrationSystemType.INTERNAL],
    ["svc-1.implemented-service.qip.yaml", IntegrationSystemType.IMPLEMENTED],
    ["ctx-1.context-service.qip.yaml", IntegrationSystemType.CONTEXT],
    ["mcp-1.mcp-service.qip.yaml", IntegrationSystemType.MCP],
  ])("reads the type %s states", (name, type) => {
    expect(serviceTypeFromUri(name)).toBe(type);
  });

  it("reads the type from a full uri path, not only from a bare name", () => {
    expect(
      serviceTypeFromUri({
        path: "/workspace/svc-1/svc-1.internal-service.qip.yaml",
      }),
    ).toBe(IntegrationSystemType.INTERNAL);
  });

  it("states no type for the legacy type-less name", () => {
    expect(serviceTypeFromUri("svc-1.service.qip.yaml")).toBeUndefined();
  });

  it.each([
    "svc-1.chain.qip.yaml",
    "svc-1.api.qip.yaml",
    "svc-1.api-group.qip.yaml",
    "svc-1.specification.qip.yaml",
    "openapi-import.yaml",
    "notes.md",
  ])("states no type for %s", (name) => {
    expect(serviceTypeFromUri(name)).toBeUndefined();
  });

  it("resolves a non-default app name from the file name", () => {
    expect(serviceTypeFromUri("svc-1.implemented-service.acme.yaml")).toBe(
      IntegrationSystemType.IMPLEMENTED,
    );
  });

  it("resolves the type against a loaded project config", () => {
    mockConfigService.isConfigLoaded.mockReturnValue(true);
    mockConfigService.getAllConfigs.mockReturnValue([
      { appName: "acme", extensions: buildDefaultExtensions("acme") },
    ]);

    expect(serviceTypeFromUri("svc-1.external-service.acme.yaml")).toBe(
      IntegrationSystemType.EXTERNAL,
    );
  });

  it("uses the extensions it is handed instead of resolving them", () => {
    expect(serviceTypeFromUri("svc-1.external-service.acme.yaml", qip)).toBe(
      undefined,
    );
    expect(serviceTypeFromUri("svc-1.external-service.acme.yaml", acme)).toBe(
      IntegrationSystemType.EXTERNAL,
    );
  });
});

// Comparing the whole extension is what keeps these apart. A prefix scan over the bare postfixes
// would let the shorter one win, which is the shadowing plan 1 spent five review rounds closing.
describe("serviceTypeFromUri - one postfix must not shadow another", () => {
  it("never reads a context service as a plain service", () => {
    const name = "ctx-1.context-service.qip.yaml";

    expect(serviceTypeFromUri(name)).toBe(IntegrationSystemType.CONTEXT);
    expect(isAnyServiceFile(name)).toBe(false);
  });

  it("never reads a typed service as the legacy type-less one", () => {
    for (const name of [
      "svc-1.external-service.qip.yaml",
      "svc-1.internal-service.qip.yaml",
      "svc-1.implemented-service.qip.yaml",
      "mcp-1.mcp-service.qip.yaml",
      "ctx-1.context-service.qip.yaml",
    ]) {
      expect(name.endsWith(qip.service)).toBe(false);
      expect(serviceTypeFromUri(name)).toBeDefined();
    }
  });

  // Autodiscovery mints service ids from Kubernetes service names, so `service-orders` is real.
  it("reads the type of a service whose id carries a postfix of its own", () => {
    expect(serviceTypeFromUri("service-orders.external-service.qip.yaml")).toBe(
      IntegrationSystemType.EXTERNAL,
    );
    expect(
      serviceTypeFromUri("context-service-orders.internal-service.qip.yaml"),
    ).toBe(IntegrationSystemType.INTERNAL);
    expect(serviceTypeFromUri("mcp-service.mcp-service.qip.yaml")).toBe(
      IntegrationSystemType.MCP,
    );
    expect(
      serviceTypeFromUri("external-service.service.qip.yaml"),
    ).toBeUndefined();
  });

  it("reads the type when the app name itself carries a postfix", () => {
    const extensions = buildDefaultExtensions("mcp-service");

    expect(
      serviceTypeFromUri("svc-1.external-service.mcp-service.yaml", extensions),
    ).toBe(IntegrationSystemType.EXTERNAL);
    expect(
      serviceTypeFromUri("svc-1.mcp-service.mcp-service.yaml", extensions),
    ).toBe(IntegrationSystemType.MCP);
  });

  it("does not read a service of another app as one of this app", () => {
    expect(
      serviceTypeFromUri("svc-1.external-service.acme.yaml", qip),
    ).toBeUndefined();
    expect(isAnyServiceFile("svc-1.external-service.acme.yaml", qip)).toBe(
      false,
    );
  });

  // The match is anchored at the end of the name, so an editor backup or a merge artefact
  // beside a real file is not a second copy of that service.
  it.each([
    "svc-1.external-service.qip.yaml.orig",
    "svc-1.service.qip.yaml.bak",
    "svc-1.external-service.qip.yaml.rej",
  ])("ignores %s, which only carries an extension in the middle", (name) => {
    expect(serviceTypeFromUri(name, qip)).toBeUndefined();
    expect(isAnyServiceFile(name, qip)).toBe(false);
  });
});

describe("isAnyServiceFile", () => {
  it.each([
    "svc-1.service.qip.yaml",
    "svc-1.external-service.qip.yaml",
    "svc-1.internal-service.qip.yaml",
    "svc-1.implemented-service.qip.yaml",
  ])("accepts the plain service file %s", (name) => {
    expect(isAnyServiceFile(name)).toBe(true);
  });

  it.each([
    "ctx-1.context-service.qip.yaml",
    "mcp-1.mcp-service.qip.yaml",
    "chain-1.chain.qip.yaml",
    "api-1.api.qip.yaml",
    "grp-1.api-group.qip.yaml",
    "notes.md",
  ])("rejects %s", (name) => {
    expect(isAnyServiceFile(name)).toBe(false);
  });

  it("accepts a full uri path", () => {
    expect(
      isAnyServiceFile({
        path: "/workspace/svc-1/svc-1.implemented-service.qip.yaml",
      }),
    ).toBe(true);
  });
});

describe("serviceExtensionForType", () => {
  it.each([
    [IntegrationSystemType.EXTERNAL, ".external-service.qip.yaml"],
    [IntegrationSystemType.INTERNAL, ".internal-service.qip.yaml"],
    [IntegrationSystemType.IMPLEMENTED, ".implemented-service.qip.yaml"],
    [IntegrationSystemType.CONTEXT, ".context-service.qip.yaml"],
    [IntegrationSystemType.MCP, ".mcp-service.qip.yaml"],
  ])("writes a %s service under %s", (type, extension) => {
    expect(serviceExtensionForType(type, qip)).toBe(extension);
  });

  it("substitutes the app name of the extensions it is handed", () => {
    expect(serviceExtensionForType(IntegrationSystemType.INTERNAL, acme)).toBe(
      ".internal-service.acme.yaml",
    );
  });

  it.each([undefined, "", "SOMETHING_ELSE"])(
    "falls back to the legacy extension for the type %p",
    (type) => {
      expect(serviceExtensionForType(type, qip)).toBe(".service.qip.yaml");
    },
  );

  it("round-trips every type through the name it writes", () => {
    for (const type of Object.values(IntegrationSystemType)) {
      const name = `svc-1${serviceExtensionForType(type, qip)}`;

      expect(serviceTypeFromUri(name, qip)).toBe(type);
    }
  });
});

describe("plainServiceExtensions", () => {
  it("lists the typed extensions ahead of the legacy one", () => {
    expect(plainServiceExtensions(qip)).toEqual([
      ".external-service.qip.yaml",
      ".internal-service.qip.yaml",
      ".implemented-service.qip.yaml",
      ".service.qip.yaml",
    ]);
  });

  it("covers exactly what isAnyServiceFile accepts", () => {
    for (const extension of plainServiceExtensions(qip)) {
      expect(isAnyServiceFile(`svc-1${extension}`)).toBe(true);
    }
    expect(plainServiceExtensions(qip)).not.toContain(qip.contextService);
    expect(plainServiceExtensions(qip)).not.toContain(qip.mcpService);
  });
});
