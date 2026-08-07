// A typed service file that cannot be parsed, with its legacy sibling still on disk. The scan
// skips what it cannot read, so the lookup used to answer with the legacy file; the read then
// served the superseded body, and the write — which recomputes the name from the type — put that
// body over the unreadable typed file and deleted the legacy one. Everything saved since the
// conversion went with it. These cases run the real `VSCodeFileApi`, the real lookup and the real
// read and write paths against an in-memory disk.

import { joinUriPath, QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

/** The workspace: path → file text. Directories are the prefixes of these paths. */
const disk = new Map<string, string>();

/** What the code under test gets handed instead of a real `vscode.Uri`. */
function fileRef(path: string): any {
  return {
    path,
    fsPath: path,
    with: (change: { path?: string }) => fileRef(change.path ?? path),
  };
}

const stat = jest.fn(async (fileUri: any) => {
  if (disk.has(fileUri.path)) {
    return { type: 1, ctime: 0 };
  }
  for (const filePath of disk.keys()) {
    if (filePath.startsWith(`${fileUri.path}/`)) {
      return { type: 2, ctime: 0 };
    }
  }
  throw new Error(`EntryNotFound: ${fileUri.path}`);
});

const readDirectory = jest.fn(async (folderUri: any) => {
  const prefix = `${folderUri.path}/`;
  const entries = new Map<string, number>();
  for (const filePath of disk.keys()) {
    if (!filePath.startsWith(prefix)) {
      continue;
    }
    const rest = filePath.slice(prefix.length);
    const slash = rest.indexOf("/");
    entries.set(slash < 0 ? rest : rest.slice(0, slash), slash < 0 ? 1 : 2);
  }
  if (entries.size === 0) {
    throw new Error(`EntryNotFound: ${folderUri.path}`);
  }
  return [...entries.entries()];
});

const writeFile = jest.fn(async (fileUri: any, bytes: Uint8Array) => {
  disk.set(fileUri.path, new TextDecoder().decode(bytes));
});

const deleteFile = jest.fn(async (fileUri: any) => {
  disk.delete(fileUri.path);
});

jest.mock(
  "vscode",
  () => {
    const api = {
      FileType: { File: 1, Directory: 2 },
      Uri: {
        joinPath: jest.fn((base: any, ...segments: string[]) =>
          fileRef(joinUriPath(base, ...segments).path),
        ),
      },
      workspace: {
        workspaceFolders: [{ uri: { path: "/root" } }],
        fs: {
          stat: (...args: any[]) => stat(args[0]),
          readDirectory: (...args: any[]) => readDirectory(args[0]),
          readFile: async (fileUri: any) =>
            new TextEncoder().encode(disk.get(fileUri.path) ?? ""),
          writeFile: (...args: any[]) => writeFile(args[0], args[1]),
          delete: (...args: any[]) => deleteFile(args[0]),
          createDirectory: jest.fn(),
        },
      },
      window: {
        showInformationMessage: jest.fn(),
        showWarningMessage: jest.fn(),
        showErrorMessage: jest.fn(),
      },
    };
    return { __esModule: true, default: api, ...api };
  },
  { virtual: true },
);

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("@netcracker/qip-schemas", () => ({}), { virtual: true });
jest.mock("yaml", () => ({
  stringify: (value: any) => JSON.stringify(value),
  parse: (text: string) => JSON.parse(text),
}));

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: jest.fn(() => ext),
  getExtensionsForUri: jest.fn(() => ext),
  extractFilename: (fileRef: any) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
    "",
}));

jest.mock("../../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getConfig: () => ({ extensions: ext, schemaUrls: {} }),
    getInstance: () => undefined,
  },
}));

// The scan never routes a message, and pulling the router in drags the whole sibling graph along.
jest.mock("../../../src/web/response/apiRouter", () => ({
  SERVICE_ROUTES: [],
  CHAIN_ROUTES: [],
  CONTEXT_SERVICE_ROUTES: [],
  MCP_SERVICE_ROUTES: [],
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

// The parser the whole read path shares. It reads the same in-memory disk, so a file holding text
// it cannot parse fails here exactly as a malformed document does.
jest.mock("../../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: {
    parseContentFromFile: async (fileUri: any) => {
      const text = disk.get(fileUri.path);
      if (text === undefined) {
        throw new Error(`EntryNotFound: ${fileUri.path}`);
      }
      return JSON.parse(text);
    },
  },
}));

import { VSCodeFileApi } from "../../../src/web/response/file/fileApiImpl";
import { setFileApi } from "../../../src/web/response/file/fileApiProvider";
import { findServiceFileById } from "../../../src/web/response/file/serviceFileLookup";
import {
  getContextServices,
  getMcpServices,
  getOperations,
  getService,
  getServices,
} from "../../../src/web/response/serviceApiRead";
import { updateService } from "../../../src/web/response/serviceApiModify";
import { FileCacheService } from "../../../src/web/services/FileCacheService";

// Navigation paths carry a uuid, so the ids are uuids here — the same ids both create paths mint.
const SERVICE_ID = "11111111-1111-4111-8111-111111111111";
const OTHER_ID = "22222222-2222-4222-8222-222222222222";
const MODEL_ID = "model-1";

let api: VSCodeFileApi;

const uri = fileRef;

const typedUri = uri(`/root/${SERVICE_ID}/${SERVICE_ID}${ext.externalService}`);
const legacyUri = uri(`/root/${SERVICE_ID}/${SERVICE_ID}${ext.service}`);
const otherTypedUri = uri(
  `/root/${OTHER_ID}/${OTHER_ID}${ext.externalService}`,
);
const otherLegacyUri = uri(`/root/${OTHER_ID}/${OTHER_ID}${ext.service}`);

const UNREADABLE_TEXT = "id: broken\n  name: [broken";

function serviceText(id: string, content: Record<string, unknown>): string {
  return JSON.stringify({
    id,
    name: "Orders",
    content: { protocol: "HTTP", ...content },
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  disk.clear();
  FileCacheService.getInstance().clearAll();
  api = new VSCodeFileApi({} as any);
  setFileApi(api);
});

describe("a service whose typed file cannot be read, with the legacy sibling still there", () => {
  beforeEach(() => {
    disk.set(typedUri.path, UNREADABLE_TEXT);
    disk.set(
      legacyUri.path,
      serviceText(SERVICE_ID, {
        description: "superseded",
        integrationSystemType: "EXTERNAL",
      }),
    );
  });

  it("refuses to resolve the id rather than answering with the sibling", async () => {
    await expect(findServiceFileById(SERVICE_ID, ext)).rejects.toThrow(
      typedUri.path,
    );
  });

  it("fails the read rather than serving the superseded body", async () => {
    await expect(getService(legacyUri, SERVICE_ID)).rejects.toThrow(
      typedUri.path,
    );
  });

  it("fails the write and leaves both files exactly as they were", async () => {
    await expect(
      updateService(legacyUri, SERVICE_ID, { name: "Renamed" }),
    ).rejects.toThrow(typedUri.path);

    expect(disk.get(typedUri.path)).toBe(UNREADABLE_TEXT);
    expect(disk.get(legacyUri.path)).toBe(
      serviceText(SERVICE_ID, {
        description: "superseded",
        integrationSystemType: "EXTERNAL",
      }),
    );
    expect(writeFile).not.toHaveBeenCalled();
    expect(deleteFile).not.toHaveBeenCalled();
  });

  // Navigation opens an editor on whatever it resolves, so it obeys the same rule: falling through
  // to the legacy name puts the superseded document in front of the user as the current one.
  it("refuses to navigate to the sibling", async () => {
    await expect(
      api.findFileByNavigationPath(
        `/services/systems/${SERVICE_ID}/parameters`,
      ),
    ).rejects.toThrow(typedUri.path);
  });

  it("refuses the extension-less lookup for the same reason", async () => {
    await expect(api.findFileById(SERVICE_ID)).rejects.toThrow(typedUri.path);
  });

  // A listing that drops the file it cannot read shows the sibling it outranks in its place, and
  // every id the list hands out then points at the superseded document.
  it("fails the service listing rather than listing the sibling in its place", async () => {
    await expect(getServices(uri("/root"))).rejects.toThrow(typedUri.path);
  });

  it("names the file in a filtered listing rather than dropping it", async () => {
    await expect(
      api.findFiles(
        ext.externalService,
        (content: any) => content?.id === SERVICE_ID,
      ),
    ).rejects.toThrow(typedUri.path);
  });
});

// The same three outcomes one level down, where a model file may sit under `.specification.` and
// `.api.` at once — the pair the conversion of an API leaves behind.
describe("an API file that cannot be read, with its other-format sibling still there", () => {
  const specUri = uri(`/root/${MODEL_ID}/${MODEL_ID}${ext.specification}`);
  const apiUri = uri(`/root/${MODEL_ID}/${MODEL_ID}${ext.api}`);

  beforeEach(() => {
    disk.set(specUri.path, UNREADABLE_TEXT);
    disk.set(
      apiUri.path,
      JSON.stringify({ id: MODEL_ID, name: "Orders", content: {} }),
    );
  });

  it("refuses to resolve the model through the sibling", async () => {
    await expect(
      getOperations(uri(`/root/x/x${ext.chain}`), MODEL_ID),
    ).rejects.toThrow(specUri.path);
  });
});

describe("a context or MCP file that cannot be read", () => {
  it("names it rather than reporting the parser's own failure", async () => {
    disk.set(uri(`/root/ctx/ctx${ext.contextService}`).path, UNREADABLE_TEXT);

    await expect(getContextServices(uri("/root"))).rejects.toThrow(
      "/root/ctx/ctx.context-service.qip.yaml",
    );
  });

  it("names an MCP file the same way", async () => {
    disk.set(uri(`/root/mcp/mcp${ext.mcpService}`).path, UNREADABLE_TEXT);

    await expect(getMcpServices(uri("/root"))).rejects.toThrow(
      "/root/mcp/mcp.mcp-service.qip.yaml",
    );
  });
});

// The refusal is scoped to the folder the service lives in: a file the scan cannot read elsewhere
// says nothing about this service, and a write lands beside the file the lookup resolved, so it
// cannot reach that file either.
describe("a file the scan cannot read in another service's folder", () => {
  beforeEach(() => {
    disk.set(otherTypedUri.path, UNREADABLE_TEXT);
  });

  it("still resolves a service stored under the typed name", async () => {
    disk.set(typedUri.path, serviceText(SERVICE_ID, { description: "typed" }));

    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe(typedUri.path);
  });

  it("still resolves a service stored under the legacy name alone", async () => {
    disk.set(
      legacyUri.path,
      serviceText(SERVICE_ID, { integrationSystemType: "EXTERNAL" }),
    );

    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe(legacyUri.path);
  });

  it("still converts that service on its next write", async () => {
    disk.set(
      legacyUri.path,
      serviceText(SERVICE_ID, {
        description: "only",
        integrationSystemType: "EXTERNAL",
      }),
    );

    const updated = await updateService(legacyUri, SERVICE_ID, {
      name: "Renamed",
    });

    expect(updated.name).toBe("Renamed");
    expect(disk.has(legacyUri.path)).toBe(false);
    expect(JSON.parse(disk.get(typedUri.path) ?? "{}")).toMatchObject({
      name: "Renamed",
      content: { description: "only" },
    });
    expect(disk.get(otherTypedUri.path)).toBe(UNREADABLE_TEXT);
  });

  it("still refuses the id whose own sibling that broken file may be", async () => {
    disk.set(
      otherLegacyUri.path,
      serviceText(OTHER_ID, { integrationSystemType: "EXTERNAL" }),
    );

    await expect(findServiceFileById(OTHER_ID, ext)).rejects.toThrow(
      otherTypedUri.path,
    );
  });

  it("still navigates to a service stored under the legacy name alone", async () => {
    disk.set(
      legacyUri.path,
      serviceText(SERVICE_ID, { integrationSystemType: "EXTERNAL" }),
    );

    const fileUri = await api.findFileByNavigationPath(
      `/services/systems/${SERVICE_ID}/parameters`,
    );

    expect(fileUri.path).toBe(legacyUri.path);
  });

  it("still answers the extension-less lookup for such a service", async () => {
    disk.set(
      legacyUri.path,
      serviceText(SERVICE_ID, { integrationSystemType: "EXTERNAL" }),
    );

    expect((await api.findFileById(SERVICE_ID)).path).toBe(legacyUri.path);
  });
});

// The convention path `<root>/<id>/<id><ext>` is read before the scan, and a document it cannot
// parse is why the scan runs at all. A file of the *same* extension that answers instead can never
// be that file's sibling — a sibling shares the folder and the name, so under one extension it is
// the same file — which is what makes answering from elsewhere safe here.
describe("an unreadable file at the convention path", () => {
  const elsewhereUri = uri(`/root/aaa/aaa${ext.externalService}`);

  beforeEach(() => {
    disk.set(elsewhereUri.path, serviceText(SERVICE_ID, {}));
    disk.set(typedUri.path, UNREADABLE_TEXT);
  });

  it("answers from the file of the same extension that does carry the id", async () => {
    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe(elsewhereUri.path);
  });
});
