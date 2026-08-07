// A group file sits under either `.api-group.<app>.yaml` (what the backend exports today) or the pre-rename
// `.specification-group.<app>.yaml`. These tests pin that the read resolves both, that a re-save reuses the
// file that is already there instead of leaving two files for one group, and that when a project somehow holds
// both files for one group, read and write land on the same one.

import {
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  stubProjectConfigService,
} from "../helpers/mocks";

const mockFindFileById = jest.fn();
const mockGetSpecificationGroupFiles = jest.fn();
const mockParseContentFromFile = jest.fn();
const mockSaveYamlFile = jest.fn();
const mockGetBaseFolder = jest.fn();
const mockStat = jest.fn();

jest.mock("vscode", () => {
  const mock = createVscodeMock();
  mock.workspace.fs.stat = mockStat;
  return mock;
});
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({
    findFileById: mockFindFileById,
    getSpecificationGroupFiles: mockGetSpecificationGroupFiles,
    // `VSCodeFileApi.parseFile` is `ContentParser.parseContentFromFile`, so the folder scan and the
    // single-file read share one double here too.
    parseFile: mockParseContentFromFile,
  }),
);
jest.mock("../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: mockParseContentFromFile },
}));
jest.mock("../../src/web/response/serviceApiUtils", () => ({
  getBaseFolder: (...args: any[]) => mockGetBaseFolder(...args),
}));
jest.mock("../../src/web/api-services/YamlFileUtils", () => ({
  YamlFileUtils: {
    saveYamlFile: (...args: any[]) => mockSaveYamlFile(...args),
  },
}));
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService(),
);

import { ApiGroupService } from "../../src/web/api-services/ApiGroupService";
import { UnreadableFileError } from "../../src/web/response/fileFilteringUtils";

const API_GROUP_EXT = ".api-group.qip.yaml";
const LEGACY_EXT = ".specification-group.qip.yaml";

beforeEach(() => {
  jest.clearAllMocks();
  mockGetBaseFolder.mockResolvedValue({ path: "/svc", fsPath: "/svc" });
});

describe("getApiGroupById", () => {
  const parsed = {
    id: "group-1",
    name: "Group",
    content: { parentId: "system-1", synchronization: true },
  };

  it("finds a group file written under the renamed extension", async () => {
    mockFindFileById.mockImplementation((_id: string, extension: string) =>
      extension === API_GROUP_EXT
        ? Promise.resolve({ path: `group-1${API_GROUP_EXT}` })
        : Promise.reject(new Error("not found")),
    );
    mockParseContentFromFile.mockResolvedValue(parsed);

    const group = await new ApiGroupService().getApiGroupById(
      "group-1",
      "system-1",
    );

    expect(group).toMatchObject({ id: "group-1", parentId: "system-1" });
  });

  it("still finds a group file written under the pre-rename extension", async () => {
    mockFindFileById.mockImplementation((_id: string, extension: string) =>
      extension === LEGACY_EXT
        ? Promise.resolve({ path: `group-1${LEGACY_EXT}` })
        : Promise.reject(new Error("not found")),
    );
    mockParseContentFromFile.mockResolvedValue(parsed);

    const group = await new ApiGroupService().getApiGroupById(
      "group-1",
      "system-1",
    );

    expect(group).toMatchObject({ id: "group-1" });
    expect(mockFindFileById).toHaveBeenCalledWith("group-1", LEGACY_EXT);
  });

  it("returns null when neither extension matches", async () => {
    mockFindFileById.mockRejectedValue(new Error("not found"));

    const group = await new ApiGroupService().getApiGroupById(
      "group-1",
      "system-1",
    );

    expect(group).toBeNull();
  });

  // The two files of one group are the pair a re-save overwrites, so the pre-rename name may not
  // stand in for a renamed one the scan could not read. The refusal is not a "no such group"
  // either: answering null would send the caller on with the file that may hold it unnamed.
  it("reports the file it could not read rather than answering from the sibling", async () => {
    mockFindFileById.mockImplementation((_id: string, extension: string) =>
      extension === API_GROUP_EXT
        ? Promise.reject(
            new UnreadableFileError(extension, [
              { path: `/svc/group-1${API_GROUP_EXT}` } as any,
            ]),
          )
        : Promise.resolve({ path: `/svc/group-1${LEGACY_EXT}` }),
    );
    mockParseContentFromFile.mockResolvedValue(parsed);

    await expect(
      new ApiGroupService().getApiGroupById("group-1", "system-1"),
    ).rejects.toThrow(`/svc/group-1${API_GROUP_EXT}`);
    expect(mockParseContentFromFile).not.toHaveBeenCalled();
  });
});

describe("saveApiGroupFile", () => {
  const group = { id: "group-1", name: "Group", specifications: [] } as any;

  // `stat` resolves only for the file names listed as present on disk.
  function filesOnDisk(...names: string[]) {
    mockStat.mockImplementation((uri: any) =>
      names.some((name) => uri.path.endsWith(name))
        ? Promise.resolve({ type: 1 })
        : Promise.reject(new Error("no such file")),
    );
  }

  it("writes a new group under the renamed extension and schema", async () => {
    filesOnDisk();

    await new ApiGroupService().saveApiGroupFile("system-1", group);

    const [uri, data] = mockSaveYamlFile.mock.calls[0];
    expect(uri.path).toContain(`group-1${API_GROUP_EXT}`);
    expect(data.$schema).toBe(
      "http://qubership.org/schemas/product/qip/api-group.schema.yaml",
    );
  });

  it("reuses an existing pre-rename file instead of writing a second one", async () => {
    filesOnDisk(`group-1${LEGACY_EXT}`);

    await new ApiGroupService().saveApiGroupFile("system-1", group);

    expect(mockSaveYamlFile).toHaveBeenCalledTimes(1);
    const [uri, data] = mockSaveYamlFile.mock.calls[0];
    expect(uri.path).toContain(`group-1${LEGACY_EXT}`);
    expect(data.$schema).toBe(
      "http://qubership.org/schemas/product/qip/specification-group.schema.yaml",
    );
  });

  it("writes to the renamed file when both files exist for one group", async () => {
    filesOnDisk(`group-1${LEGACY_EXT}`, `group-1${API_GROUP_EXT}`);

    await new ApiGroupService().saveApiGroupFile("system-1", group);

    expect(mockSaveYamlFile).toHaveBeenCalledTimes(1);
    const [uri, data] = mockSaveYamlFile.mock.calls[0];
    expect(uri.path).toContain(`group-1${API_GROUP_EXT}`);
    expect(data.$schema).toBe(
      "http://qubership.org/schemas/product/qip/api-group.schema.yaml",
    );
  });
});

describe("resolveGroupFile", () => {
  const serviceFileUri = { path: "/svc/svc-1.service.qip.yaml" } as any;

  beforeEach(() => {
    mockParseContentFromFile.mockImplementation((uri: any) =>
      Promise.resolve({ id: uri.path.split(".")[0], name: "Group" }),
    );
  });

  it("returns the only file when a group is stored once", async () => {
    mockGetSpecificationGroupFiles.mockResolvedValue([
      `group-1${LEGACY_EXT}`,
      `group-2${API_GROUP_EXT}`,
    ]);

    const resolved = await ApiGroupService.resolveGroupFile(
      serviceFileUri,
      "group-1",
    );

    expect(resolved).toMatchObject({
      fileName: `group-1${LEGACY_EXT}`,
      duplicates: [],
    });
  });

  it("prefers the renamed file and reports the pre-rename sibling as a duplicate", async () => {
    mockGetSpecificationGroupFiles.mockResolvedValue([
      `group-1${LEGACY_EXT}`,
      `group-1${API_GROUP_EXT}`,
    ]);

    const resolved = await ApiGroupService.resolveGroupFile(
      serviceFileUri,
      "group-1",
    );

    expect(resolved).toMatchObject({
      fileName: `group-1${API_GROUP_EXT}`,
      duplicates: [`group-1${LEGACY_EXT}`],
    });
  });

  it("returns null when no file carries the group id", async () => {
    mockGetSpecificationGroupFiles.mockResolvedValue([
      `group-2${API_GROUP_EXT}`,
    ]);

    expect(
      await ApiGroupService.resolveGroupFile(serviceFileUri, "group-1"),
    ).toBeNull();
  });
});
