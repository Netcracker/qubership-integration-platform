// Deleting a service must take that service's files and nothing else. A folder can hold several services
// (the flat layout the explorer supports, the workspace root included), so ownership comes from the
// parentId chain: the service file, its group files under either extension, and the APIs under those
// groups. `resources/` carries no owner, so it goes only once no service is left in the folder.

import {
  createVscodeMock,
  stubProjectConfigService,
  buildMockContext,
  QIP_FILE_EXTENSIONS,
} from "./helpers/mocks";

const registeredCommands = new Map<string, (...args: any[]) => unknown>();

const mockShowWarningMessage = jest.fn();
const mockShowInformationMessage = jest.fn();
const mockReadDirectory = jest.fn();
const mockDelete = jest.fn();
const mockInvalidateByUri = jest.fn();
const mockParseFile = jest.fn();
const mockGetSpecificationGroupFiles = jest.fn();
const mockGetSpecificationFiles = jest.fn();

jest.mock(
  "vscode",
  () => {
    const base = createVscodeMock();
    return {
      ...base,
      Uri: {
        ...base.Uri,
        joinPath: jest.fn((baseUri: any, ...segments: string[]) => ({
          path: [baseUri?.path ?? "", ...segments].filter(Boolean).join("/"),
          fsPath: [baseUri?.path ?? "", ...segments].filter(Boolean).join("/"),
          toString: () =>
            [baseUri?.path ?? "", ...segments].filter(Boolean).join("/"),
        })),
      },
      window: {
        ...base.window,
        showWarningMessage: mockShowWarningMessage,
        showInformationMessage: mockShowInformationMessage,
        registerCustomEditorProvider: jest.fn(() => ({ dispose: jest.fn() })),
      },
      commands: {
        registerCommand: jest.fn(
          (id: string, handler: (...args: any[]) => unknown) => {
            registeredCommands.set(id, handler);
            return { dispose: jest.fn() };
          },
        ),
        executeCommand: jest.fn(),
      },
      workspace: {
        ...base.workspace,
        fs: {
          ...base.workspace.fs,
          readDirectory: mockReadDirectory,
          delete: mockDelete,
          stat: jest.fn().mockResolvedValue({ type: 1 }),
        },
      },
    };
  },
  { virtual: true },
);

jest.mock("../src/web/response/apiRouter", () => ({
  setPendingExportImagesRequest: jest.fn(),
  startExportImagesProgress: jest.fn(),
  getApiResponse: jest.fn(),
}));
jest.mock("../src/web/response", () => ({
  getApiResponse: jest.fn(),
  listChainExportTargets: jest.fn(),
  schemaToChain: jest.fn(),
  CHAIN_DIFF_PATH: "/chains/diff",
}));
jest.mock("../src/web/response/file", () => ({
  setFileApi: jest.fn(),
  fileApi: {
    parseFile: mockParseFile,
    getSpecificationGroupFiles: mockGetSpecificationGroupFiles,
    getSpecificationFiles: mockGetSpecificationFiles,
  },
}));
jest.mock("../src/web/response/file/fileApiImpl", () => ({
  RESOURCES_FOLDER: "resources",
  VSCodeFileApi: jest.fn().mockImplementation(() => ({
    createEmptyChain: jest.fn(),
    createEmptyService: jest.fn(),
    createEmptyContextService: jest.fn(),
  })),
}));
jest.mock("../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn().mockReturnValue(QIP_FILE_EXTENSIONS),
  getExtensionsForFile: jest.fn().mockReturnValue(QIP_FILE_EXTENSIONS),
  setCurrentFileContext: jest.fn(),
  extractFilename: jest.fn(),
  initializeContextFromFile: jest.fn(),
}));
jest.mock("../src/web/qipExplorer", () => ({
  QipExplorerProvider: jest.fn().mockImplementation(() => ({
    refresh: jest.fn(),
    getTreeItem: jest.fn(),
    getChildren: jest.fn(),
  })),
}));
jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("../src/web/services/FileCacheService", () => ({
  FileCacheService: {
    getInstance: jest
      .fn()
      .mockReturnValue({ invalidateByUri: mockInvalidateByUri }),
  },
}));
jest.mock("../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService(),
);
jest.mock("../src/web/services/ConfigApiProvider", () => ({
  ConfigApiProvider: { getInstance: jest.fn().mockReturnValue({}) },
}));
jest.mock("../src/web/response/navigationUtils", () => ({
  getAndClearNavigationStateValue: jest.fn(),
  getNavigationStateValue: jest.fn(),
  initNavigationState: jest.fn(),
  updateNavigationStateValue: jest.fn(),
}));

import { activate } from "../src/web/extension";

const FILE = 1;
const DIRECTORY = 2;

beforeEach(async () => {
  jest.clearAllMocks();
  registeredCommands.clear();
  mockShowWarningMessage.mockResolvedValue("Delete");
  await activate(buildMockContext());
});

function fileName(uri: { path: string }): string {
  return String(uri.path).split("/").pop() ?? "";
}

function deletedNames(): string[] {
  return mockDelete.mock.calls.map(([uri]) => fileName(uri));
}

/** Serves parsed content by file name and keeps readDirectory in step with what the sweep deleted. */
function setUpFolder(
  entries: [string, number][],
  parsedByFileName: Record<string, any>,
) {
  mockParseFile.mockImplementation(async (uri: { path: string }) => {
    const parsed = parsedByFileName[fileName(uri)];
    if (!parsed) {
      throw new Error(`Unexpected file ${uri.path}`);
    }
    return parsed;
  });
  mockGetSpecificationGroupFiles.mockResolvedValue(
    entries
      .filter(
        ([name]) =>
          name.endsWith(QIP_FILE_EXTENSIONS.specificationGroup) ||
          name.endsWith(QIP_FILE_EXTENSIONS.apiGroup),
      )
      .map(([name]) => name),
  );
  mockGetSpecificationFiles.mockResolvedValue(
    entries
      .filter(
        ([name]) =>
          name.endsWith(QIP_FILE_EXTENSIONS.specification) ||
          name.endsWith(QIP_FILE_EXTENSIONS.api),
      )
      .map(([name]) => name),
  );
  // The folder read is the resources/empty check, so it must reflect what the sweep already deleted.
  mockReadDirectory.mockImplementation(() =>
    Promise.resolve(entries.filter(([name]) => !deletedNames().includes(name))),
  );
}

function group(id: string, serviceId: string) {
  return { id, name: id, content: { parentId: serviceId } };
}

function api(id: string, groupId: string) {
  return { id, name: id, content: { parentId: groupId } };
}

it("deletes group files under both extensions and removes the emptied folder", async () => {
  setUpFolder(
    [
      ["svc.service.qip.yaml", FILE],
      ["group-1.specification-group.qip.yaml", FILE],
      ["group-1.api-group.qip.yaml", FILE],
      ["model-1.api.qip.yaml", FILE],
      ["resources", DIRECTORY],
    ],
    {
      "svc.service.qip.yaml": { id: "svc-1", name: "svc" },
      "group-1.specification-group.qip.yaml": group("group-1", "svc-1"),
      "group-1.api-group.qip.yaml": group("group-1", "svc-1"),
      "model-1.api.qip.yaml": api("model-1", "group-1"),
    },
  );

  const command = registeredCommands.get("qip.deleteService")!;
  await command({
    fileUri: { path: "/workspace/svc/svc.service.qip.yaml" },
    label: "svc",
  });

  // Both files of the duplicated group go, or the group resurrects from the one left behind.
  expect(deletedNames()).toEqual(
    expect.arrayContaining([
      "svc.service.qip.yaml",
      "group-1.specification-group.qip.yaml",
      "group-1.api-group.qip.yaml",
      "model-1.api.qip.yaml",
      "resources",
    ]),
  );
  // The folder is only removed when the sweep left nothing behind, so this also proves no file was orphaned.
  // The vscode mock renders `Uri.joinPath(serviceFile, "..")` as a trailing ".." segment.
  const deleted = deletedNames();
  expect(deleted[deleted.length - 1]).toBe("..");
  expect(mockShowInformationMessage).toHaveBeenCalled();
});

// Several services in one folder is a layout the explorer supports (the workspace root is one such folder).
// Sweeping the folder by extension there wipes every sibling, and the user is still told it went fine.
it("leaves a sibling service's files alone when both live in one folder", async () => {
  setUpFolder(
    [
      ["svc-a.service.qip.yaml", FILE],
      ["group-a.api-group.qip.yaml", FILE],
      ["model-a.api.qip.yaml", FILE],
      ["svc-b.service.qip.yaml", FILE],
      ["group-b.api-group.qip.yaml", FILE],
      ["model-b.api.qip.yaml", FILE],
      ["resources", DIRECTORY],
    ],
    {
      "svc-a.service.qip.yaml": { id: "svc-a", name: "svc-a" },
      "group-a.api-group.qip.yaml": group("group-a", "svc-a"),
      "model-a.api.qip.yaml": api("model-a", "group-a"),
      "svc-b.service.qip.yaml": { id: "svc-b", name: "svc-b" },
      "group-b.api-group.qip.yaml": group("group-b", "svc-b"),
      "model-b.api.qip.yaml": api("model-b", "group-b"),
    },
  );

  const command = registeredCommands.get("qip.deleteService")!;
  await command({
    fileUri: { path: "/workspace/flat/svc-a.service.qip.yaml" },
    label: "svc-a",
  });

  expect([...deletedNames()].sort()).toEqual([
    "group-a.api-group.qip.yaml",
    "model-a.api.qip.yaml",
    "svc-a.service.qip.yaml",
  ]);
  // `resources/` stays while svc-b can still read from it, and so does the folder itself.
  expect(deletedNames()).not.toContain("resources");
  expect(deletedNames()).not.toContain("..");
});

// The sibling left behind carries a typed name, so the folder sweep only sees it as a service if
// service detection covers the three new extensions. Miss that and `resources/` goes with the delete.
it("keeps resources when the sibling left behind carries a typed service name", async () => {
  setUpFolder(
    [
      ["svc-a.service.qip.yaml", FILE],
      ["svc-b.external-service.qip.yaml", FILE],
      ["resources", DIRECTORY],
    ],
    {
      "svc-a.service.qip.yaml": { id: "svc-a", name: "svc-a" },
      "svc-b.external-service.qip.yaml": { id: "svc-b", name: "svc-b" },
    },
  );

  const command = registeredCommands.get("qip.deleteService")!;
  await command({
    fileUri: { path: "/workspace/flat/svc-a.service.qip.yaml" },
    label: "svc-a",
  });

  expect(deletedNames()).toEqual(["svc-a.service.qip.yaml"]);
  expect(deletedNames()).not.toContain("resources");
  expect(deletedNames()).not.toContain("..");
});
