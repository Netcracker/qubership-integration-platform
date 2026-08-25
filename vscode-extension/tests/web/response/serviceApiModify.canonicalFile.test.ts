// A write handed the uri of a superseded per-type sibling. Reads resolve a service by id, so the
// document the editor shows comes from the current file; a write that trusted the uri instead read
// the stale body, applied the edit to it and wrote that over the current file, dropping everything
// saved since the conversion. These cases run the real `serviceApiRead` and `serviceFileWrite`
// against an in-memory disk — the sibling suites stub `readServiceFile`, so they cannot see it.

import {
  joinUriPath,
  QIP_FILE_EXTENSIONS as ext,
  URN_SCHEMA_URLS,
} from "../../helpers/mocks";

jest.mock(
  "vscode",
  () => {
    const api = {
      Uri: { joinPath: jest.fn(joinUriPath) },
      window: {
        showInformationMessage: jest.fn(),
        showErrorMessage: jest.fn(),
        showWarningMessage: jest.fn(),
      },
      workspace: { workspaceFolders: [{ uri: { path: "/root" } }] },
    };
    return { __esModule: true, default: api, ...api };
  },
  { virtual: true },
);

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("yaml", () => ({ stringify: jest.fn(), parse: jest.fn() }));

/** The workspace, keyed by path. Every read and write below goes through it. */
const disk = new Map<string, any>();

const writeMainService = jest.fn(async (fileUri: any, service: any) => {
  disk.set(fileUri.path, structuredClone(service));
});
const getMainService = jest.fn(async (fileUri: any) => {
  const document = disk.get(fileUri.path);
  if (!document) {
    throw new Error(`EntryNotFound: ${fileUri.path}`);
  }
  return structuredClone(document);
});
const findFileById = jest.fn(async (id: string, extension: string) => {
  for (const [path, document] of disk) {
    if (path.endsWith(extension) && document?.id === id) {
      return uri(path);
    }
  }
  throw new Error(`Unable to find file with extension: ${extension}`);
});
const deleteFile = jest.fn(async (fileUri: any) => {
  disk.delete(fileUri.path);
});
// `getFileType` answers UNKNOWN for a path that is gone rather than failing, so the fallback to a
// held uri asks `fileExists`, which is `stat`.
const fileExists = jest.fn(async (fileUri: any) => disk.has(fileUri.path));

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    getMainService: (...args: any[]) => getMainService(args[0]),
    findFileById: (...args: any[]) => findFileById(args[0], args[1]),
    writeMainService: (...args: any[]) => writeMainService(args[0], args[1]),
    deleteFile: (...args: any[]) => deleteFile(args[0]),
    fileExists: (...args: any[]) => fileExists(args[0]),
    findFiles: jest.fn().mockResolvedValue([]),
  },
}));

jest.mock("../../../src/web/response/file/fileExtensions", () =>
  jest.requireActual("../../helpers/mocks").fileExtensionsMock(
    () => ext,
    () => undefined,
  ),
);

jest.mock("../../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getConfig: () => ({
      extensions: ext,
      schemaUrls: URN_SCHEMA_URLS,
    }),
    getInstance: () => undefined,
  },
}));

jest.mock("../../../src/web/extension", () => ({
  refreshQipExplorer: jest.fn(),
}));
jest.mock("../../../src/web/api-services/ApiGroupService", () => ({
  ApiGroupService: {
    regenerateGroupApisSafely: jest.fn(),
    resolveGroupFile: jest.fn(),
  },
}));
jest.mock("../../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: jest.fn() },
}));

import { updateService } from "../../../src/web/response/serviceApiModify";

const SERVICE_ID = "svc-1";

function uri(path: string): any {
  return { path, fsPath: path };
}

function serviceFile(extension: string): any {
  return uri(`/root/${SERVICE_ID}/${SERVICE_ID}${extension}`);
}

const supersededUri = serviceFile(ext.internalService);
const currentUri = serviceFile(ext.service);

function serviceDocument(content: Record<string, unknown>): any {
  return {
    id: SERVICE_ID,
    name: "Orders",
    content: { protocol: "HTTP", ...content },
  };
}

beforeEach(() => {
  jest.clearAllMocks();
  disk.clear();
});

describe("updating a service whose per-type sibling is still on disk", () => {
  beforeEach(() => {
    disk.set(currentUri.path, {
      ...serviceDocument({ description: "current" }),
      $schema: "urn:external",
    });
    disk.set(
      supersededUri.path,
      serviceDocument({
        description: "superseded",
        integrationSystemType: "EXTERNAL",
      }),
    );
  });

  it("writes the current file when handed the superseded uri", async () => {
    await updateService(supersededUri, SERVICE_ID, { name: "Renamed" });

    expect(writeMainService).toHaveBeenCalledTimes(1);
    expect(writeMainService.mock.calls[0][0].path).toBe(currentUri.path);
  });

  it("keeps what the current file holds rather than the superseded body", async () => {
    await updateService(supersededUri, SERVICE_ID, { name: "Renamed" });

    expect(disk.get(currentUri.path)).toMatchObject({
      name: "Renamed",
      content: { description: "current" },
    });
  });

  it("answers with the document it just wrote", async () => {
    const updated = await updateService(supersededUri, SERVICE_ID, {
      name: "Renamed",
    });

    expect(updated.type).toBe("EXTERNAL");
    expect(updated.name).toBe("Renamed");
    expect(updated.description).toBe("current");
  });

  it("leaves the superseded file alone rather than writing through it", async () => {
    await updateService(supersededUri, SERVICE_ID, { name: "Renamed" });

    expect(disk.get(supersededUri.path)).toMatchObject({
      name: "Orders",
      content: { description: "superseded" },
    });
  });
});

// The conversion itself has to keep working: a service that has only the per-type file is written
// to the current name, and the old file goes.
describe("updating a service that has only the per-type file", () => {
  beforeEach(() => {
    disk.set(
      supersededUri.path,
      serviceDocument({
        description: "only",
        integrationSystemType: "EXTERNAL",
      }),
    );
  });

  it("converts it and deletes the per-type file", async () => {
    const updated = await updateService(supersededUri, SERVICE_ID, {
      name: "Renamed",
    });

    expect(disk.has(supersededUri.path)).toBe(false);
    expect(disk.get(currentUri.path)).toMatchObject({
      name: "Renamed",
      content: { description: "only" },
    });
    expect(updated.type).toBe("EXTERNAL");
  });
});
