// Both names of one entity on disk, both readable. Every lookup has to answer with the current
// name — `.api.` over `.specification.`, `.api-group.` over `.specification-group.`, the plain
// `.service.` name over a per-type one — because that is the file the next write lands on.
// A lookup that answers with the superseded file shows a superseded document as the current one.
// These cases run the real `VSCodeFileApi` and the real lookups against an in-memory disk.

import {
  QIP_FILE_EXTENSIONS as ext,
  URN_SCHEMA_URLS,
} from "../../helpers/mocks";
import { disk, fileRef } from "../../helpers/serviceDisk";

// The in-memory workspace shared with three sibling suites; see tests/helpers/serviceDisk.ts.
jest.mock("vscode", () => require("../../helpers/serviceDisk").vscodeApi(), {
  virtual: true,
});

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("@netcracker/qip-schemas", () => ({}), { virtual: true });
jest.mock("yaml", () => ({
  stringify: (value: any) => JSON.stringify(value),
  parse: (text: string) => JSON.parse(text),
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

// The lookups route no message, and pulling the router in drags the whole sibling graph along.
jest.mock("../../../src/web/response/apiRouter", () => ({
  SERVICE_ROUTES: [/^\/services\/systems\/[^/]+/],
  CHAIN_ROUTES: [],
  CONTEXT_SERVICE_ROUTES: [],
  MCP_SERVICE_ROUTES: [],
}));
jest.mock("../../../src/web/extension", () => ({
  refreshQipExplorer: jest.fn(),
}));

// The parser the whole read path shares, reading the same in-memory disk.
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

import { ApiGroupService } from "../../../src/web/api-services/ApiGroupService";
import {
  resolveApiFiles,
  resolveGroupFiles,
} from "../../../src/web/response/file/entityFiles";
import { VSCodeFileApi } from "../../../src/web/response/file/fileApiImpl";
import { setFileApi } from "../../../src/web/response/file/fileApiProvider";
import { findServiceFileById } from "../../../src/web/response/file/serviceFileLookup";
import { getOperations } from "../../../src/web/response/serviceApiRead";
import { FileCacheService } from "../../../src/web/services/FileCacheService";

const SERVICE_ID = "11111111-1111-4111-8111-111111111111";
const MODEL_ID = "44444444-4444-4444-8444-444444444444";
const GROUP_ID = "55555555-5555-4555-8555-555555555555";

const uri = fileRef;
const folder = `/root/${SERVICE_ID}`;

const currentServiceUri = uri(`${folder}/${SERVICE_ID}${ext.service}`);
const supersededServiceUri = uri(
  `${folder}/${SERVICE_ID}${ext.internalService}`,
);
const apiUri = uri(`${folder}/${MODEL_ID}${ext.api}`);
const legacyApiUri = uri(`${folder}/${MODEL_ID}${ext.specification}`);
const apiGroupUri = uri(`${folder}/${GROUP_ID}${ext.apiGroup}`);
const legacyGroupUri = uri(`${folder}/${GROUP_ID}${ext.specificationGroup}`);
const chainUri = uri(`/root/chains/c1${ext.chain}`);

let api: VSCodeFileApi;

function modelText(name: string, operationName: string): string {
  return JSON.stringify({
    id: MODEL_ID,
    name,
    content: {
      parentId: GROUP_ID,
      operations: [{ id: "op-1", name: operationName, method: "GET" }],
    },
  });
}

function groupText(name: string): string {
  return JSON.stringify({
    id: GROUP_ID,
    name,
    content: { parentId: SERVICE_ID },
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  disk.clear();
  FileCacheService.getInstance().clearAll();
  api = new VSCodeFileApi({} as any);
  setFileApi(api);

  disk.set(
    currentServiceUri.path,
    JSON.stringify({
      id: SERVICE_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    }),
  );
  disk.set(
    supersededServiceUri.path,
    JSON.stringify({
      id: SERVICE_ID,
      name: "Orders (superseded)",
      content: { protocol: "HTTP", integrationSystemType: "EXTERNAL" },
    }),
  );
  // The chain the model reads are driven from. It is on disk because the extension is only ever
  // handed the uri of an open editor: the fallback to a held uri stands only while it resolves.
  disk.set(
    chainUri.path,
    JSON.stringify({ id: "c1", name: "C1", content: { elements: [] } }),
  );
  disk.set(apiUri.path, modelText("Orders API", "current"));
  disk.set(legacyApiUri.path, modelText("Orders API (superseded)", "old"));
  disk.set(apiGroupUri.path, groupText("Orders group"));
  disk.set(legacyGroupUri.path, groupText("Orders group (superseded)"));
});

describe("a service stored under both the current and a per-type name", () => {
  it("resolves the id to the current file", async () => {
    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe(currentServiceUri.path);
  });

  it("navigates to the current file", async () => {
    const fileUri = await api.findFileByNavigationPath(
      `/services/systems/${SERVICE_ID}/parameters`,
    );

    expect(fileUri.path).toBe(currentServiceUri.path);
  });
});

describe("an api model stored under both `.specification.` and `.api.`", () => {
  // The regression: a lookup handed a uri that is not a plain service file resolves the model by
  // id across both names, and answering with the `.specification.` file serves the operations the
  // conversion superseded.
  it("reads the operations of the `.api.` file", async () => {
    const operations = await getOperations(chainUri, MODEL_ID);

    expect(operations.map((operation) => operation.name)).toEqual(["current"]);
  });

  it("resolves the extension-less lookup to the `.api.` file", async () => {
    const fileUri = await api.findFileById(MODEL_ID);

    expect(fileUri.path).toBe(apiUri.path);
  });

  it("scans the folder to the `.api.` file, the sibling a duplicate", async () => {
    const scanned = await resolveApiFiles(currentServiceUri);

    expect(scanned.byId.get(MODEL_ID)?.fileUri.path).toBe(apiUri.path);
    expect(
      scanned.byId.get(MODEL_ID)?.duplicates.map((file) => file.fileUri.path),
    ).toEqual([legacyApiUri.path]);
  });
});

describe("a group stored under both `.specification-group.` and `.api-group.`", () => {
  it("resolves the id to the `.api-group.` file", async () => {
    const group = await new ApiGroupService(currentServiceUri).getApiGroupById(
      GROUP_ID,
      SERVICE_ID,
    );

    expect(group?.name).toBe("Orders group");
  });

  it("resolves the extension-less lookup to the `.api-group.` file", async () => {
    const fileUri = await api.findFileById(GROUP_ID);

    expect(fileUri.path).toBe(apiGroupUri.path);
  });

  it("scans the folder to the `.api-group.` file, the sibling a duplicate", async () => {
    const scanned = await resolveGroupFiles(currentServiceUri);

    expect(scanned.byId.get(GROUP_ID)?.fileUri.path).toBe(apiGroupUri.path);
    expect(
      scanned.byId.get(GROUP_ID)?.duplicates.map((file) => file.fileUri.path),
    ).toEqual([legacyGroupUri.path]);
  });
});
