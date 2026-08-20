// What a malformed file does to a workspace scan. It used to abort the scan it turned up in, so a
// broken `.service.` file anywhere in the workspace made that pass fail and handed the lookup to
// the per-type sibling — the precedence every service read depends on, decided by an unrelated
// document.
// The real `VSCodeFileApi` runs here against a mocked directory tree.

import { joinUriPath, QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

const stat = jest.fn();
const readDirectory = jest.fn();

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    FileType: { File: 1, Directory: 2 },
    Uri: { joinPath: jest.fn(joinUriPath) },
    workspace: {
      workspaceFolders: [{ uri: { path: "/root" } }],
      fs: { stat, readDirectory },
    },
    window: { showWarningMessage: jest.fn(), showErrorMessage: jest.fn() },
  }),
  { virtual: true },
);

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
    getInstance: jest.fn(),
  },
}));

// Keep the circular sibling graph out of module load — none of it runs inside a scan.
jest.mock("../../../src/web/response/apiRouter", () => ({
  SERVICE_ROUTES: [],
  CHAIN_ROUTES: [],
  CONTEXT_SERVICE_ROUTES: [],
  MCP_SERVICE_ROUTES: [],
}));
jest.mock("../../../src/web/response/serviceApiUtils", () => ({
  QipFileType: {},
}));

const parseContentFromFile = jest.fn();
jest.mock("../../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: {
    parseContentFromFile: (...args: any[]) => parseContentFromFile(args[0]),
  },
}));
jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("@netcracker/qip-schemas", () => ({}), { virtual: true });

import { VSCodeFileApi } from "../../../src/web/response/file/fileApiImpl";
import { setFileApi } from "../../../src/web/response/file/fileApiProvider";
import { findServiceFileById } from "../../../src/web/response/file/serviceFileLookup";

const BROKEN_FILE = "/root/broken/broken.service.qip.yaml";
const SERVICE_ID = "svc-1";
const LEGACY_ONLY_ID = "svc-2";

/** The tree the scan walks. `broken` comes first, so it is reached before the file looked for. */
const tree: Record<string, [string, number][]> = {
  "/root": [
    ["broken", 2],
    ["orders", 2],
    ["notes", 2],
  ],
  "/root/broken": [["broken.service.qip.yaml", 1]],
  // Both files of one service, the state a conversion whose delete failed leaves behind.
  "/root/orders": [
    ["orders.external-service.qip.yaml", 1],
    ["orders.service.qip.yaml", 1],
  ],
  "/root/notes": [["notes.internal-service.qip.yaml", 1]],
};

const idByFile: Record<string, string> = {
  "/root/orders/orders.external-service.qip.yaml": SERVICE_ID,
  "/root/orders/orders.service.qip.yaml": SERVICE_ID,
  "/root/notes/notes.internal-service.qip.yaml": LEGACY_ONLY_ID,
};

beforeEach(() => {
  jest.clearAllMocks();
  setFileApi(new VSCodeFileApi({} as any));
  // Nothing sits at the convention path `<root>/<id>/<id><ext>`, so every lookup runs the scan.
  stat.mockRejectedValue(new Error("EntryNotFound"));
  readDirectory.mockImplementation(async (folderUri: any) => {
    const entries = tree[folderUri.path];
    if (!entries) {
      throw new Error(`EntryNotFound: ${folderUri.path}`);
    }
    return entries;
  });
  parseContentFromFile.mockImplementation(async (fileUri: any) => {
    if (fileUri.path === BROKEN_FILE) {
      throw new Error(`Unable to parse file: ${fileUri.path}`);
    }
    return { id: idByFile[fileUri.path], name: "Orders" };
  });
});

describe("a malformed file in the scanned tree", () => {
  it("does not hand the current name's turn to the per-type sibling", async () => {
    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe("/root/orders/orders.service.qip.yaml");
  });

  it("still resolves a service that has only the per-type file", async () => {
    const fileUri = await findServiceFileById(LEGACY_ONLY_ID, ext);

    expect(fileUri.path).toBe("/root/notes/notes.internal-service.qip.yaml");
  });
});
