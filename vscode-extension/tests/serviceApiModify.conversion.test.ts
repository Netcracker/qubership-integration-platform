// The write half of the per-type file names: what the two create paths emit, and what the first
// write of a legacy `.service.` file turns it into. A conversion that lands on the wrong name, or
// leaves the old file behind, shows up as a service listed twice or missing from an import, never
// as a failure — so every name, every dropped field and every delete is pinned here.

import { QIP_FILE_EXTENSIONS } from "./helpers/mocks";

const DEFAULT_SCHEMA_URLS = {
  service: "http://qubership.org/schemas/product/qip/service.schema.yaml",
  externalService:
    "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
  internalService:
    "http://qubership.org/schemas/product/qip/internal-service.schema.yaml",
  implementedService:
    "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml",
  contextService:
    "http://qubership.org/schemas/product/qip/context-service.schema.yaml",
  mcpService:
    "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml",
};

let extensions: Record<string, string> = { ...QIP_FILE_EXTENSIONS };
let schemaUrls: Record<string, string> = { ...DEFAULT_SCHEMA_URLS };
// The configs the workspace loaded, one per app. A conversion reads the file's own app from here
// rather than taking whichever app the last opened document made current.
let loadedConfigs: { appName: string; schemaUrls: Record<string, string> }[] =
  [];

// Resolves `..` the way vscode.Uri.joinPath does; the writer reaches the service folder through it.
function joinPath(base: any, ...segments: string[]) {
  const parts = [
    ...String(base?.path ?? "").split("/"),
    ...segments.flatMap((segment) => segment.split("/")),
  ];
  const resolved: string[] = [];
  for (const part of parts) {
    if (part === "..") {
      resolved.pop();
    } else if (part !== ".") {
      resolved.push(part);
    }
  }
  const path = resolved.join("/");
  return { path, fsPath: path };
}

jest.mock(
  "vscode",
  () => {
    const api = {
      Uri: { joinPath: jest.fn(joinPath) },
      window: {
        showInformationMessage: jest.fn(),
        showErrorMessage: jest.fn(),
        showWarningMessage: jest.fn(),
        showInputBox: jest.fn(),
        showQuickPick: jest.fn(),
      },
      workspace: { workspaceFolders: [{ uri: { path: "/workspace" } }] },
    };
    return { __esModule: true, default: api, ...api };
  },
  { virtual: true },
);

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("yaml", () => ({ stringify: jest.fn(), parse: jest.fn() }));

const writeMainService = jest.fn();
const writeServiceFile = jest.fn();
const deleteFile = jest.fn();
const getContextServiceFile = jest.fn();
jest.mock("../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    writeMainService: (...args: unknown[]) => writeMainService(...args),
    writeServiceFile: (...args: unknown[]) => writeServiceFile(...args),
    deleteFile: (...args: unknown[]) => deleteFile(...args),
    getContextService: (...args: unknown[]) => getContextServiceFile(...args),
  },
}));

const getMainService = jest.fn();
const getService = jest.fn();
jest.mock("../src/web/response/serviceApiRead", () => ({
  getMainService: (...args: unknown[]) => getMainService(...args),
  // The real one falls back to the file the id resolves to when the uri no longer reads; every case
  // here holds a live uri, so it answers with the file it was handed.
  readServiceFile: async (fileUri: any) => ({
    fileUri,
    service: await getMainService(fileUri),
  }),
  getService: (...args: unknown[]) => getService(...args),
  getContextService: jest.fn(),
  getMcpService: jest.fn(),
}));

jest.mock("../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: () => extensions,
  getExtensionsForUri: () => extensions,
  extractFilename: (fileRef: any) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
    "",
}));

jest.mock("../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getConfig: () => ({ extensions, schemaUrls }),
    getInstance: () => ({
      isConfigLoaded: () => loadedConfigs.length > 0,
      getAllConfigs: () => loadedConfigs,
    }),
  },
}));

jest.mock("../src/web/extension", () => ({ refreshQipExplorer: jest.fn() }));
jest.mock("../src/web/api-services/ApiGroupService", () => ({
  ApiGroupService: {
    regenerateGroupApisSafely: jest.fn(),
    resolveGroupFile: jest.fn(),
  },
}));
jest.mock("../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: jest.fn() },
}));

import vscode from "vscode";
import {
  createService,
  updateContextService,
  updateMcpService,
  updateService,
} from "../src/web/response/serviceApiModify";
import { onServiceFileMoved } from "../src/web/response/file/serviceFileWrite";
import { IntegrationSystemType } from "../src/web/api-services/servicesTypes";
import { SERVICE_MIGRATIONS } from "../src/web/services/importMigrationVersions";

const SERVICE_ID = "svc-1";

function uri(path: string) {
  return { path, fsPath: path } as any;
}

function legacyService(overrides: Record<string, any> = {}) {
  return {
    $schema: DEFAULT_SCHEMA_URLS.service,
    id: SERVICE_ID,
    name: "Orders",
    content: {
      description: "Order management",
      activeEnvironmentId: "env-1",
      integrationSystemType: "INTERNAL",
      protocol: "HTTP",
      environments: [
        { id: "env-1", name: "Production", address: "https://orders.test" },
      ],
      labels: [{ name: "team", technical: false }],
      migrations: "[100, 101]",
      ...overrides,
    },
  };
}

function written() {
  const [fileUri, service] = writeMainService.mock.calls[0];
  return { path: fileUri.path, service };
}

beforeEach(() => {
  jest.clearAllMocks();
  extensions = { ...QIP_FILE_EXTENSIONS };
  schemaUrls = { ...DEFAULT_SCHEMA_URLS };
  loadedConfigs = [];
  getService.mockResolvedValue({ id: SERVICE_ID });
  deleteFile.mockResolvedValue(undefined);
});

describe("createService writes the name that states the type", () => {
  it.each([
    [IntegrationSystemType.EXTERNAL, ".external-service.qip.yaml"],
    [IntegrationSystemType.INTERNAL, ".internal-service.qip.yaml"],
    [IntegrationSystemType.IMPLEMENTED, ".implemented-service.qip.yaml"],
  ])("writes a %s service as <id>%s", async (type, extension) => {
    const service = await createService({} as any, uri("/workspace"), {
      name: "Orders",
      description: "Order management",
      type,
      protocol: "http",
      labels: [],
    });

    const [fileUri, document] = writeServiceFile.mock.calls[0];
    expect(fileUri.path).toBe(
      `/workspace/${service.id}/${service.id}${extension}`,
    );
    expect(document.content).not.toHaveProperty("integrationSystemType");
    expect(service.integrationSystemType).toBe(type);
  });

  it.each([
    [IntegrationSystemType.EXTERNAL, DEFAULT_SCHEMA_URLS.externalService],
    [IntegrationSystemType.INTERNAL, DEFAULT_SCHEMA_URLS.internalService],
    [IntegrationSystemType.IMPLEMENTED, DEFAULT_SCHEMA_URLS.implementedService],
  ])("stamps the %s schema url from the project config", async (type, url) => {
    await createService({} as any, uri("/workspace"), {
      name: "Orders",
      type,
      labels: [],
    });

    expect(writeServiceFile.mock.calls[0][1].$schema).toBe(url);
  });

  // The services list only ever posts one of the three plain tabs, but the request type is
  // optional, and the default decides both the name and the schema url of the file.
  it("defaults to an external service when the request states no type", async () => {
    const service = await createService({} as any, uri("/workspace"), {
      name: "Orders",
      labels: [],
    } as any);

    const [fileUri, document] = writeServiceFile.mock.calls[0];
    expect(fileUri.path).toBe(
      `/workspace/${service.id}/${service.id}.external-service.qip.yaml`,
    );
    expect(document.$schema).toBe(DEFAULT_SCHEMA_URLS.externalService);
    expect(service.integrationSystemType).toBe(IntegrationSystemType.EXTERNAL);
  });

  // The backend reads a service id up to the first dot and refuses to write a current-format name
  // for anything else (`ExportImportUtils.requireCurrentFormatId`). A dotted id would produce a file
  // name whose leading segment reads back as a different service.
  it("mints a dot-free id", async () => {
    const service = await createService({} as any, uri("/workspace"), {
      name: "Orders",
      type: IntegrationSystemType.EXTERNAL,
      labels: [],
    });

    expect(service.id).not.toContain(".");
    expect(service.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
  });

  // A file that understates the claim has the missing migrations re-run over it, and one that
  // overstates it is refused outright, so a new file states the whole registered list.
  it("claims every registered service migration", async () => {
    await createService({} as any, uri("/workspace"), {
      name: "Orders",
      type: IntegrationSystemType.EXTERNAL,
      labels: [],
    });

    expect(writeServiceFile.mock.calls[0][1].content.migrations).toBe(
      SERVICE_MIGRATIONS,
    );
  });

  it("keeps the type in the name under a non-default appName and schemaUrls", async () => {
    extensions = {
      ...extensions,
      service: ".service.acme.yaml",
      externalService: ".external-service.acme.yaml",
    };
    schemaUrls = { ...schemaUrls, externalService: "http://acme.test/service" };

    const service = await createService({} as any, uri("/workspace"), {
      name: "Orders",
      type: IntegrationSystemType.EXTERNAL,
      labels: [],
    });

    const [fileUri, document] = writeServiceFile.mock.calls[0];
    expect(fileUri.path).toBe(
      `/workspace/${service.id}/${service.id}.external-service.acme.yaml`,
    );
    expect(document.$schema).toBe("http://acme.test/service");
  });
});

describe("updateService converts a legacy file on its first write", () => {
  it("writes the typed file, removes the legacy one and keeps every field", async () => {
    getMainService.mockResolvedValue(legacyService());

    await updateService(
      uri(`/svc/${SERVICE_ID}.service.qip.yaml`),
      SERVICE_ID,
      {
        name: "Renamed",
      },
    );

    const { path, service } = written();
    expect(path).toBe(`/svc/${SERVICE_ID}.internal-service.qip.yaml`);
    expect(service.name).toBe("Renamed");
    expect(service.$schema).toBe(DEFAULT_SCHEMA_URLS.internalService);
    expect(service.content).not.toHaveProperty("integrationSystemType");
    expect(service.content).toMatchObject({
      description: "Order management",
      activeEnvironmentId: "env-1",
      protocol: "HTTP",
      migrations: "[100, 101]",
      environments: [
        { id: "env-1", name: "Production", address: "https://orders.test" },
      ],
    });
    expect(deleteFile).toHaveBeenCalledWith(
      expect.objectContaining({
        path: `/svc/${SERVICE_ID}.service.qip.yaml`,
      }),
    );
  });

  it("re-reads the service from the file the conversion produced", async () => {
    getMainService.mockResolvedValue(legacyService());

    await updateService(
      uri(`/svc/${SERVICE_ID}.service.qip.yaml`),
      SERVICE_ID,
      {
        name: "Renamed",
      },
    );

    expect(getService).toHaveBeenCalledWith(
      expect.objectContaining({
        path: `/svc/${SERVICE_ID}.internal-service.qip.yaml`,
      }),
      SERVICE_ID,
    );
  });

  // A dotted id predates #553, and no current-format name can state it: the backend reads the id up
  // to the first dot, so `a.b.internal-service.qip.yaml` states the id `a` and resolves no type
  // (`ExportImportUtils.fitsCurrentFormatFileName`). Converting such a service turns a file that
  // imported fine into one the backend refuses, in the preview and on commit alike.
  it("leaves a dotted-id service in the legacy format, type and all", async () => {
    getMainService.mockResolvedValue({
      ...legacyService(),
      id: "a.b",
    });

    await updateService(uri("/services/a.b/a.b.service.qip.yaml"), "a.b", {
      name: "Renamed",
    });

    expect(written().path).toBe("/services/a.b/a.b.service.qip.yaml");
    expect(written().service.content.integrationSystemType).toBe("INTERNAL");
    expect(deleteFile).not.toHaveBeenCalled();
  });

  it("writes in place when the name already states the type", async () => {
    getMainService.mockResolvedValue({
      $schema: DEFAULT_SCHEMA_URLS.externalService,
      id: SERVICE_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    await updateService(
      uri(`/svc/${SERVICE_ID}.external-service.qip.yaml`),
      SERVICE_ID,
      { name: "Renamed" },
    );

    expect(written().path).toBe(`/svc/${SERVICE_ID}.external-service.qip.yaml`);
    expect(deleteFile).not.toHaveBeenCalled();
  });

  // A write that does not rename leaves `$schema` alone. Stamping the current config's URL on every
  // typed write would hand a file of one app the URL of whichever app was opened last.
  it("leaves $schema untouched when it writes in place", async () => {
    getMainService.mockResolvedValue({
      $schema: "http://another.example/external-service",
      id: SERVICE_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    await updateService(
      uri(`/svc/${SERVICE_ID}.external-service.qip.yaml`),
      SERVICE_ID,
      { name: "Renamed" },
    );

    expect(written().service.$schema).toBe(
      "http://another.example/external-service",
    );
  });

  // The typed schemas refuse a document that restates its type, and the backend refuses a name and
  // a field that disagree. No write may put the field back into a typed file.
  it("drops a type the caller put into the content of a typed file", async () => {
    getMainService.mockResolvedValue({
      $schema: DEFAULT_SCHEMA_URLS.externalService,
      id: SERVICE_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    await updateService(
      uri(`/svc/${SERVICE_ID}.external-service.qip.yaml`),
      SERVICE_ID,
      { integrationSystemType: IntegrationSystemType.EXTERNAL },
    );

    expect(written().service.content).not.toHaveProperty(
      "integrationSystemType",
    );
  });

  // Tolerant editor, strict backend: a legacy file stating no type keeps its legacy name here
  // rather than being renamed on a guess, and the backend refuses it on import.
  it("keeps the legacy name when neither the name nor the content states a type", async () => {
    getMainService.mockResolvedValue({
      id: SERVICE_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    await updateService(
      uri(`/svc/${SERVICE_ID}.service.qip.yaml`),
      SERVICE_ID,
      {
        name: "Renamed",
      },
    );

    expect(written().path).toBe(`/svc/${SERVICE_ID}.service.qip.yaml`);
    expect(deleteFile).not.toHaveBeenCalled();
  });

  // The services list posts back the whole record it loaded, type included. The backend refuses a
  // type switch, so an extension that applied one would write a file the import then rejects.
  it("ignores a type in the request and converts to the type the file states", async () => {
    getMainService.mockResolvedValue(legacyService());

    await updateService(
      uri(`/svc/${SERVICE_ID}.service.qip.yaml`),
      SERVICE_ID,
      {
        type: IntegrationSystemType.EXTERNAL,
        integrationSystemType: IntegrationSystemType.EXTERNAL,
        name: "Renamed",
      },
    );

    const { path, service } = written();
    expect(path).toBe(`/svc/${SERVICE_ID}.internal-service.qip.yaml`);
    expect(service.$schema).toBe(DEFAULT_SCHEMA_URLS.internalService);
    expect(service.content).not.toHaveProperty("integrationSystemType");
  });

  it("ignores a type in the request and leaves a typed file where it is", async () => {
    getMainService.mockResolvedValue({
      $schema: DEFAULT_SCHEMA_URLS.externalService,
      id: SERVICE_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    await updateService(
      uri(`/svc/${SERVICE_ID}.external-service.qip.yaml`),
      SERVICE_ID,
      { type: IntegrationSystemType.INTERNAL },
    );

    expect(written().path).toBe(`/svc/${SERVICE_ID}.external-service.qip.yaml`);
    expect(deleteFile).not.toHaveBeenCalled();
  });

  it("converts under a non-default appName and stamps the configured schema url", async () => {
    extensions = {
      ...extensions,
      service: ".service.acme.yaml",
      internalService: ".internal-service.acme.yaml",
    };
    schemaUrls = {
      ...schemaUrls,
      internalService: "http://acme.test/internal",
    };
    getMainService.mockResolvedValue(legacyService());

    await updateService(
      uri(`/svc/${SERVICE_ID}.service.acme.yaml`),
      SERVICE_ID,
      {
        name: "Renamed",
      },
    );

    const { path, service } = written();
    expect(path).toBe(`/svc/${SERVICE_ID}.internal-service.acme.yaml`);
    expect(service.$schema).toBe("http://acme.test/internal");
  });

  // A multi-app workspace has one current config at a time, set by the last opened document. The
  // file being converted decides which app's schema url it gets, not that.
  it("stamps the schema url of the app the file belongs to", async () => {
    extensions = {
      ...extensions,
      appName: "acme",
      service: ".service.acme.yaml",
      internalService: ".internal-service.acme.yaml",
    };
    loadedConfigs = [
      {
        appName: "acme",
        schemaUrls: {
          ...schemaUrls,
          internalService: "http://acme.test/internal",
        },
      },
    ];
    getMainService.mockResolvedValue(legacyService());

    await updateService(
      uri(`/svc/${SERVICE_ID}.service.acme.yaml`),
      SERVICE_ID,
      { name: "Renamed" },
    );

    expect(written().service.$schema).toBe("http://acme.test/internal");
  });

  // `.context-service.` and `.mcp-service.` are other kinds of document, told apart by name and
  // `$schema` together. A plain service whose body claims one of them must not be renamed into that
  // family, or the next import reads it as a context service.
  it.each([IntegrationSystemType.CONTEXT, IntegrationSystemType.MCP])(
    "keeps a plain service legacy when its body claims %s",
    async (type) => {
      getMainService.mockResolvedValue(
        legacyService({ integrationSystemType: type }),
      );

      await updateService(
        uri(`/svc/${SERVICE_ID}.service.qip.yaml`),
        SERVICE_ID,
        { name: "Renamed" },
      );

      const { path, service } = written();
      expect(path).toBe(`/svc/${SERVICE_ID}.service.qip.yaml`);
      expect(service.content.integrationSystemType).toBe(type);
      expect(deleteFile).not.toHaveBeenCalled();
    },
  );

  // The save already reported success, and the leftover file is a duplicate id on the next import.
  it("warns when the legacy file survives the conversion", async () => {
    deleteFile.mockRejectedValue(new Error("EPERM"));
    getMainService.mockResolvedValue(legacyService());

    await updateService(
      uri(`/svc/${SERVICE_ID}.service.qip.yaml`),
      SERVICE_ID,
      { name: "Renamed" },
    );

    expect(vscode.window.showWarningMessage).toHaveBeenCalledWith(
      expect.stringContaining(`${SERVICE_ID}.service.qip.yaml`),
    );
  });

  // The open editor was handed its uri once. Without this event every later message from that tab
  // reads the file the conversion deleted.
  it("announces the file the service moved to", async () => {
    const moves: { from: string; to: string }[] = [];
    const subscription = onServiceFileMoved((from, to) =>
      moves.push({ from: from.path, to: to.path }),
    );
    getMainService.mockResolvedValue(legacyService());

    try {
      await updateService(
        uri(`/svc/${SERVICE_ID}.service.qip.yaml`),
        SERVICE_ID,
        { name: "Renamed" },
      );
    } finally {
      subscription.dispose();
    }

    expect(moves).toEqual([
      {
        from: `/svc/${SERVICE_ID}.service.qip.yaml`,
        to: `/svc/${SERVICE_ID}.internal-service.qip.yaml`,
      },
    ]);
  });

  it("stays quiet when the write lands on the file it came from", async () => {
    const moved = jest.fn();
    const subscription = onServiceFileMoved(moved);
    getMainService.mockResolvedValue({
      $schema: DEFAULT_SCHEMA_URLS.externalService,
      id: SERVICE_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    try {
      await updateService(
        uri(`/svc/${SERVICE_ID}.external-service.qip.yaml`),
        SERVICE_ID,
        { name: "Renamed" },
      );
    } finally {
      subscription.dispose();
    }

    expect(moved).not.toHaveBeenCalled();
  });
});

// A context or an MCP document is its own kind of file, not a plain service carrying a type. The
// write path has to leave it under its own name: renaming it to `.service.` and deleting the
// original destroys the only file the backend reads as a context or an MCP service, and the editor
// that issued the save is the one that triggers it.
describe("a context or an MCP file is written where it is", () => {
  beforeEach(() => {
    getContextServiceFile.mockResolvedValue({
      id: SERVICE_ID,
      name: "Customer context",
      content: { description: "Customer data" },
    });
  });

  it("keeps a context service under its own name", async () => {
    const path = `/svc/${SERVICE_ID}${QIP_FILE_EXTENSIONS.contextService}`;

    await updateContextService(uri(path), SERVICE_ID, { name: "Renamed" });

    expect(written().path).toBe(path);
    expect(deleteFile).not.toHaveBeenCalled();
  });

  it("keeps an MCP service under its own name", async () => {
    const path = `/svc/${SERVICE_ID}${QIP_FILE_EXTENSIONS.mcpService}`;

    await updateMcpService(uri(path), SERVICE_ID, { name: "Renamed" });

    expect(written().path).toBe(path);
    expect(deleteFile).not.toHaveBeenCalled();
  });
});
