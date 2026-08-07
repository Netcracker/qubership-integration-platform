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

  // A listing that merely skipped the file it cannot read would show the sibling it outranks in its
  // place, and every id the list hands out would point at the superseded document. So the sibling
  // goes too — and the service disappears from the list rather than appearing as the wrong body.
  it("lists neither the unreadable file nor the sibling it outranks", async () => {
    disk.set(otherTypedUri.path, serviceText(OTHER_ID, {}));

    const services = await getServices(uri("/root"));

    expect(services.map((service) => service.id)).toEqual([OTHER_ID]);
  });

  // Dropping it silently is what would send the user off to recreate a service that is already
  // there, so the file is named where the listing is shown.
  it("names the file it could not read", async () => {
    await getServices(uri("/root"));

    expect(
      jest.requireMock("vscode").window.showWarningMessage,
    ).toHaveBeenCalledWith(
      expect.stringContaining(`${SERVICE_ID}${ext.externalService}`),
    );
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

// The other way round: the converted file is fine and the legacy one it superseded is the broken
// one — the state a failed `deleteLegacySibling` leaves, and the state whose warning tells the user
// to delete that file by hand. Nothing reads it and no write lands on it, so the service stays on
// every screen it is reachable from; hiding it would take a healthy service off the list while the
// lookup still resolves it and every write still lands on it.
describe("a converted service whose legacy sibling cannot be read", () => {
  beforeEach(() => {
    disk.set(
      typedUri.path,
      serviceText(SERVICE_ID, { description: "current" }),
    );
    disk.set(legacyUri.path, UNREADABLE_TEXT);
  });

  it("resolves the id to the typed file", async () => {
    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe(typedUri.path);
  });

  it("lists the service", async () => {
    const services = await getServices(uri("/root"));

    expect(services.map((service) => service.id)).toEqual([SERVICE_ID]);
  });

  it("reads it through the stale legacy uri", async () => {
    const service = await getService(legacyUri, SERVICE_ID);

    expect(service.description).toBe("current");
  });

  it("writes an edit to the typed file and leaves the broken one alone", async () => {
    await updateService(typedUri, SERVICE_ID, { name: "Renamed" });

    expect(JSON.parse(disk.get(typedUri.path) ?? "{}").name).toBe("Renamed");
    expect(disk.get(legacyUri.path)).toBe(UNREADABLE_TEXT);
  });

  it("still names the file it could not read", async () => {
    await getServices(uri("/root"));

    expect(
      jest.requireMock("vscode").window.showWarningMessage,
    ).toHaveBeenCalledWith(
      expect.stringContaining(`${SERVICE_ID}${ext.service}`),
    );
  });
});

// The uri a caller holds is a hint, and it stands in for a lookup that found nothing only while it
// still points at something. `getFileType` answers UNKNOWN for a path that is gone rather than
// failing, so existence is asked of `stat`; reading it back through `getFileType` made this guard
// answer "still there" for every path and turned the lookup failure into a read error on a deleted
// file further down.
describe("a held uri the conversion deleted, with nothing carrying the id", () => {
  it("reports the lookup failure rather than reading on from the deleted path", async () => {
    // The aggregate names every extension it tried; the raw `EntryNotFound` the fallback produces
    // instead names one deleted path and nothing about the lookup.
    await expect(getService(legacyUri, SERVICE_ID)).rejects.toThrow(
      `No service file carries id ${SERVICE_ID}`,
    );
  });

  it("still reads on from a uri that is there", async () => {
    disk.set(legacyUri.path, serviceText(SERVICE_ID, { description: "held" }));

    // Nothing carries the *other* id, so the lookup misses and the held uri is what is left.
    await expect(getService(legacyUri, OTHER_ID)).rejects.toThrow(
      "ServiceId mismatch",
    );
  });
});

// The same three outcomes one level down, where a model file may sit under `.specification.` and
// `.api.` at once — the pair the conversion of an API leaves behind. The refusal is directional,
// as it is for a service: only a name of *higher* precedence that the scan could not read blocks
// the answer, because only that file is the one a write would land on.
//
// These cases drive `getOperations` from a *chain* uri, so they run the `findModelFileById` branch:
// the lookup by id, not the folder scan. The folder scan is the other runner of the same rule, and
// `unreadableApiFiles.test.ts` pins it in both directions — for a while these cases were read as
// covering it, and it was non-directional the whole time.
describe("an API model stored under both names, one of them unreadable", () => {
  const specUri = uri(`/root/${MODEL_ID}/${MODEL_ID}${ext.specification}`);
  const apiUri = uri(`/root/${MODEL_ID}/${MODEL_ID}${ext.api}`);
  const modelText = JSON.stringify({
    id: MODEL_ID,
    name: "Orders",
    content: { operations: [{ id: "op-1", name: "Op One" }] },
  });

  it("refuses to answer from the legacy sibling when the `.api.` file is unreadable", async () => {
    disk.set(apiUri.path, UNREADABLE_TEXT);
    disk.set(specUri.path, modelText);

    await expect(
      getOperations(uri(`/root/x/x${ext.chain}`), MODEL_ID),
    ).rejects.toThrow(apiUri.path);
  });

  // A resolved uri can come from the cache, where only `stat` vouched for it, so the file behind an
  // id resolves without the lookup ever parsing it. "No operations" is a content answer, and
  // nobody could read the content.
  it("refuses when the file it resolved went unreadable after the lookup cached it", async () => {
    disk.set(apiUri.path, modelText);
    const chainUri = uri(`/root/x/x${ext.chain}`);

    expect(await getOperations(chainUri, MODEL_ID)).toHaveLength(1);
    disk.set(apiUri.path, UNREADABLE_TEXT);

    await expect(getOperations(chainUri, MODEL_ID)).rejects.toThrow(
      apiUri.path,
    );
  });

  // The other way round nothing is at risk: the `.api.` file is the one every read answers from and
  // every write lands on, so an unreadable legacy sibling blocks nothing.
  it("answers from the `.api.` file when the legacy sibling is unreadable", async () => {
    disk.set(specUri.path, UNREADABLE_TEXT);
    disk.set(apiUri.path, modelText);

    const operations = await getOperations(
      uri(`/root/x/x${ext.chain}`),
      MODEL_ID,
    );

    expect(operations.map((operation) => operation.name)).toEqual(["Op One"]);
  });
});

// A context or MCP document is stored under one name, so nothing can stand in for it. It still
// leaves the listing rather than reaching the parser as a nameless failure, and it is still named.
describe("a context or MCP file that cannot be read", () => {
  it("keeps the readable context services and names the broken file", async () => {
    disk.set(uri(`/root/ctx/ctx${ext.contextService}`).path, UNREADABLE_TEXT);
    disk.set(
      uri(`/root/ok/ok${ext.contextService}`).path,
      serviceText("ctx-ok", {}),
    );

    const services = await getContextServices(uri("/root"));

    expect(services.map((service) => service.id)).toEqual(["ctx-ok"]);
    expect(
      jest.requireMock("vscode").window.showWarningMessage,
    ).toHaveBeenCalledWith(
      expect.stringContaining("ctx.context-service.qip.yaml"),
    );
  });

  it("treats an MCP file the same way", async () => {
    disk.set(uri(`/root/mcp/mcp${ext.mcpService}`).path, UNREADABLE_TEXT);
    disk.set(
      uri(`/root/ok/ok${ext.mcpService}`).path,
      serviceText("mcp-ok", {}),
    );

    const services = await getMcpServices(uri("/root"));

    expect(services.map((service) => service.id)).toEqual(["mcp-ok"]);
    expect(
      jest.requireMock("vscode").window.showWarningMessage,
    ).toHaveBeenCalledWith(expect.stringContaining("mcp.mcp-service.qip.yaml"));
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
