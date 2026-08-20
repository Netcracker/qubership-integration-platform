// Real-directory-scan test for VSCodeFileApi.getSpecificationFiles. The
// serviceApiRead suite mocks getSpecificationFiles, so it cannot catch a scan
// that ignores `.api.qip.yaml`. This exercises the actual scan against a mocked
// vscode.workspace.fs.readDirectory and asserts both the `.specification` and
// the renamed `.api` model files are discovered.

import { QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

const stat = jest.fn();
const readDirectory = jest.fn();

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    FileType: { File: 1, Directory: 2 },
    Uri: {
      joinPath: jest.fn((_base: any, ...segments: string[]) => ({
        path: segments.join("/"),
        fsPath: segments.join("/"),
      })),
    },
    workspace: {
      workspaceFolders: [{ uri: { path: "/root" } }],
      fs: { stat, readDirectory },
    },
  }),
  { virtual: true },
);

// Keep the heavy, circular sibling graphs out of module load — none of them
// run inside getSpecificationFiles.
jest.mock("../../../src/web/response/apiRouter", () => ({
  SERVICE_ROUTES: [],
  CHAIN_ROUTES: [],
  CONTEXT_SERVICE_ROUTES: [],
  MCP_SERVICE_ROUTES: [],
}));
jest.mock("../../../src/web/response/serviceApiUtils", () => ({
  QipFileType: {},
}));
jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: jest.fn(() => ext),
  extractFilename: jest.fn((uri: any) =>
    (typeof uri === "string" ? uri : uri.path).split("/").pop(),
  ),
}));
jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("@netcracker/qip-schemas", () => ({}), { virtual: true });

import { VSCodeFileApi } from "../../../src/web/response/file/fileApiImpl";

function makeUri(path: string): any {
  return {
    path,
    scheme: "file",
    with: (patch: { path?: string }) => makeUri(patch.path ?? path),
  };
}

const SERVICE_FILE_URI = makeUri("/root/svc/svc.service.qip.yaml");

beforeEach(() => {
  jest.clearAllMocks();
  // Report the service file as a File so the scan runs against its folder.
  stat.mockResolvedValue({ type: 1 });
});

describe("VSCodeFileApi.getSpecificationFiles - real directory scan", () => {
  const api = new VSCodeFileApi({} as any);

  it("discovers both `.specification` and the renamed `.api` model files", async () => {
    readDirectory.mockResolvedValue([
      ["m1.specification.qip.yaml", 1],
      ["api1.api.qip.yaml", 1],
      ["g1.specification-group.qip.yaml", 1],
      ["svc.service.qip.yaml", 1],
      ["resources", 2],
    ]);

    const files = await api.getSpecificationFiles(SERVICE_FILE_URI);

    expect(files.sort()).toEqual([
      "api1.api.qip.yaml",
      "m1.specification.qip.yaml",
    ]);
  });

  it("discovers `.api` model files in an API-only project", async () => {
    readDirectory.mockResolvedValue([
      ["api1.api.qip.yaml", 1],
      ["api2.api.qip.yaml", 1],
      ["svc.service.qip.yaml", 1],
    ]);

    const files = await api.getSpecificationFiles(SERVICE_FILE_URI);

    expect(files.sort()).toEqual(["api1.api.qip.yaml", "api2.api.qip.yaml"]);
  });
});

describe("VSCodeFileApi.getSpecificationGroupFiles - real directory scan", () => {
  const api = new VSCodeFileApi({} as any);

  it("discovers both `.specification-group` and the renamed `.api-group` group files", async () => {
    readDirectory.mockResolvedValue([
      ["g1.specification-group.qip.yaml", 1],
      ["g2.api-group.qip.yaml", 1],
      ["m1.specification.qip.yaml", 1],
      ["svc.service.qip.yaml", 1],
      ["resources", 2],
    ]);

    const files = await api.getSpecificationGroupFiles(SERVICE_FILE_URI);

    expect(files.sort()).toEqual([
      "g1.specification-group.qip.yaml",
      "g2.api-group.qip.yaml",
    ]);
  });

  it("discovers `.api-group` group files in an api-group-only project", async () => {
    readDirectory.mockResolvedValue([
      ["g1.api-group.qip.yaml", 1],
      ["g2.api-group.qip.yaml", 1],
      ["svc.service.qip.yaml", 1],
    ]);

    const files = await api.getSpecificationGroupFiles(SERVICE_FILE_URI);

    expect(files.sort()).toEqual([
      "g1.api-group.qip.yaml",
      "g2.api-group.qip.yaml",
    ]);
  });
});
