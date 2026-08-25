// `apis[]` on a group is derived: `parentId` on each API file is the source of
// truth, and the list is rebuilt from the files on disk after any API write or
// delete. These tests pin that a group's `apis[]` gains an added API, loses a
// deleted one, and is corrected when a hand edit drifts from the actual files.

import * as yaml from "yaml";
import {
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  stubProjectConfigService,
} from "../helpers/mocks";

const mockGetSpecificationGroupFiles = jest.fn();
const mockGetSpecificationFiles = jest.fn();
const mockWriteFile = jest.fn();
const mockParseContentFromFile = jest.fn();

jest.mock("vscode", () => createVscodeMock(), { virtual: true });
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({
    getSpecificationGroupFiles: mockGetSpecificationGroupFiles,
    getSpecificationFiles: mockGetSpecificationFiles,
    writeFile: mockWriteFile,
    parseFile: mockParseContentFromFile,
  }),
);
jest.mock("../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: mockParseContentFromFile },
}));
jest.mock("../../src/web/response/serviceApiUtils", () => ({
  getBaseFolder: jest.fn(),
}));
jest.mock("../../src/web/api-services/YamlFileUtils", () => ({
  YamlFileUtils: { saveYamlFile: jest.fn() },
}));
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService(),
);

import { ApiGroupService } from "../../src/web/api-services/ApiGroupService";

const GROUP_FILE = "grp.specification-group.qip.yaml";
const serviceFileUri = { path: "svc/service.service.qip.yaml" } as any;

// The vscode mock's `Uri.joinPath` keeps only the last segment as `path`, so
// each file is addressed by its bare name here.
function setupFolder(
  groupFiles: string[],
  specFiles: string[],
  byPath: Record<string, any>,
): void {
  mockGetSpecificationGroupFiles.mockResolvedValue(groupFiles);
  mockGetSpecificationFiles.mockResolvedValue(specFiles);
  mockParseContentFromFile.mockImplementation((uri: any) =>
    Promise.resolve(byPath[uri.path]),
  );
}

function writtenGroupApis(): string[] {
  const call = mockWriteFile.mock.calls.find(
    ([uri]) => uri.path === GROUP_FILE,
  );
  if (!call) {
    throw new Error("group file was not written");
  }
  const parsed = yaml.parse(new TextDecoder().decode(call[1]));
  return parsed.content.apis;
}

describe("ApiGroupService.regenerateGroupApis", () => {
  beforeEach(() => jest.clearAllMocks());

  test("adds a new API's id to the group's apis[]", async () => {
    setupFolder([GROUP_FILE], ["api-1.api.qip.yaml"], {
      [GROUP_FILE]: {
        id: "group-1",
        name: "Payments",
        content: { synchronization: false },
      },
      "api-1.api.qip.yaml": {
        id: "api-1",
        content: { parentId: "group-1", specificationType: "openapi" },
      },
    });

    await ApiGroupService.regenerateGroupApis(serviceFileUri, "group-1");

    expect(writtenGroupApis()).toEqual(["api-1"]);
  });

  test("removes a deleted API's id from the group's apis[]", async () => {
    // The group file still lists api-1, but only api-2 remains on disk.
    setupFolder([GROUP_FILE], ["api-2.api.qip.yaml"], {
      [GROUP_FILE]: {
        id: "group-1",
        name: "Payments",
        content: { apis: ["api-1", "api-2"] },
      },
      "api-2.api.qip.yaml": {
        id: "api-2",
        content: { parentId: "group-1" },
      },
    });

    await ApiGroupService.regenerateGroupApis(serviceFileUri, "group-1");

    expect(writtenGroupApis()).toEqual(["api-2"]);
  });

  test("corrects a stale hand-edited apis[] from the files on disk", async () => {
    // The list names ids that no longer exist and omits ones that do; the files'
    // parentId is the truth, so it is rewritten to match.
    setupFolder(
      [GROUP_FILE],
      ["api-1.api.qip.yaml", "api-2.api.qip.yaml", "api-3.api.qip.yaml"],
      {
        [GROUP_FILE]: {
          id: "group-1",
          name: "Payments",
          content: { apis: ["ghost", "wrong"] },
        },
        "api-1.api.qip.yaml": {
          id: "api-1",
          content: { parentId: "group-1" },
        },
        "api-2.api.qip.yaml": {
          id: "api-2",
          content: { parentId: "group-1" },
        },
        // Belongs to another group — must not be listed.
        "api-3.api.qip.yaml": {
          id: "api-3",
          content: { parentId: "group-2" },
        },
      },
    );

    await ApiGroupService.regenerateGroupApis(serviceFileUri, "group-1");

    expect(writtenGroupApis()).toEqual(["api-1", "api-2"]);
  });

  test("does not write when the group file is gone", async () => {
    // Deleting a whole group removes its file, so there is nothing to rewrite.
    setupFolder([], ["api-1.api.qip.yaml"], {
      "api-1.api.qip.yaml": {
        id: "api-1",
        content: { parentId: "group-1" },
      },
    });

    await ApiGroupService.regenerateGroupApis(serviceFileUri, "group-1");

    expect(mockWriteFile).not.toHaveBeenCalled();
  });
});

// The best-effort wrapper both live call sites (import and serviceApiModify)
// share: it no-ops on missing arguments and never propagates a regeneration
// failure, since apis[] self-heals on the next write.
describe("ApiGroupService.regenerateGroupApisSafely", () => {
  beforeEach(() => jest.clearAllMocks());

  test("no-ops when the group id is missing", async () => {
    await ApiGroupService.regenerateGroupApisSafely(serviceFileUri, undefined);

    expect(mockGetSpecificationGroupFiles).not.toHaveBeenCalled();
    expect(mockWriteFile).not.toHaveBeenCalled();
  });

  test("no-ops when the service file uri is missing", async () => {
    await ApiGroupService.regenerateGroupApisSafely(undefined, "group-1");

    expect(mockGetSpecificationGroupFiles).not.toHaveBeenCalled();
  });

  test("delegates to regenerateGroupApis on the happy path", async () => {
    setupFolder([GROUP_FILE], ["api-1.api.qip.yaml"], {
      [GROUP_FILE]: {
        id: "group-1",
        name: "Payments",
        content: { synchronization: false },
      },
      "api-1.api.qip.yaml": {
        id: "api-1",
        content: { parentId: "group-1" },
      },
    });

    await ApiGroupService.regenerateGroupApisSafely(serviceFileUri, "group-1");

    expect(writtenGroupApis()).toEqual(["api-1"]);
  });

  test("swallows a regeneration failure instead of throwing", async () => {
    mockGetSpecificationGroupFiles.mockRejectedValue(new Error("disk error"));

    await expect(
      ApiGroupService.regenerateGroupApisSafely(serviceFileUri, "group-1"),
    ).resolves.toBeUndefined();
  });
});
