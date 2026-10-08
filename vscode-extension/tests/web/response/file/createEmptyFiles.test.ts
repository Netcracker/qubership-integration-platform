const nodeFs = require("fs") as typeof import("fs");
const nodePath = require("path") as typeof import("path");

const written = new Map<string, string>();
const read: string[] = [];
const mockShowInputBox = jest.fn();
const mockShowQuickPick = jest.fn();

function createMockUri(p: string): any {
  return {
    path: p,
    fsPath: p,
    scheme: "file",
    toString: () => p,
    with(change: { path?: string }) {
      return createMockUri(change.path ?? p);
    },
  };
}

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    Uri: {
      file: (p: string) => createMockUri(p),
      parse: (p: string) => createMockUri(p),
      joinPath: (base: any, ...segments: string[]) =>
        createMockUri(nodePath.posix.join(base.path, ...segments)),
    },
    workspace: {
      workspaceFolders: [{ uri: createMockUri("/workspace") }],
      fs: {
        // The embedded config is read from disk; the workspace has no .config.qip.yaml.
        readFile: async (uri: any) => {
          read.push(uri.path);
          return nodeFs.readFileSync(uri.path);
        },
        stat: async () => {
          throw new Error("not found");
        },
        createDirectory: async () => undefined,
        writeFile: async (uri: any, bytes: Uint8Array) => {
          written.set(uri.path, new TextDecoder().decode(bytes));
        },
      },
    },
    window: {
      showInputBox: mockShowInputBox,
      showQuickPick: mockShowQuickPick,
      showInformationMessage: jest.fn(),
      showErrorMessage: jest.fn(),
    },
    FileType: { File: 1, Directory: 2 },
  }),
  { virtual: true },
);

import * as yaml from "yaml";
import { ExtensionContext } from "vscode";
import { setFileApi } from "../../../../src/web/response/file/fileApiProvider";
import { VSCodeFileApi } from "../../../../src/web/response/file/fileApiImpl";
import { ProjectConfigService } from "../../../../src/web/services/ProjectConfigService";
import {
  CHAIN_MIGRATIONS,
  MCP_SERVICE_MIGRATIONS,
  SERVICE_MIGRATIONS,
} from "../../../../src/web/constants/migrations";

const SCHEMA =
  "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model";
const EXTENSION_ROOT = nodePath.resolve(__dirname, "../../../..");

describe("new files under the embedded default config", () => {
  let api: VSCodeFileApi;
  let configService: ProjectConfigService;

  beforeEach(async () => {
    written.clear();
    read.length = 0;
    const context = {
      extensionUri: createMockUri(EXTENSION_ROOT),
    } as unknown as ExtensionContext;
    api = new VSCodeFileApi(context);
    setFileApi(api);
    configService = ProjectConfigService.getInstance();
    configService.clearCache();
    configService.setContext(context);
    await configService.setCurrentContext(
      configService.getCurrentAppName(),
      createMockUri("/workspace"),
    );
  });

  it("defaults to the cip app the shipped config declares", () => {
    const shippedPath = nodePath.join(
      EXTENSION_ROOT,
      "configs/default.config.cip.yaml",
    );
    const shipped = yaml.parse(nodeFs.readFileSync(shippedPath, "utf8"));

    expect(read).toContain(shippedPath);
    expect(Object.keys(shipped.configs)).toEqual(["cip"]);
    expect(configService.getCurrentAppName()).toBe("cip");
    expect(configService.getAllConfigs().map((c) => c.appName)).toEqual([
      "cip",
    ]);
    expect(configService.getCurrentConfig()).toEqual(
      expect.objectContaining({
        extensions: shipped.configs.cip.extensions,
        schemaUrls: shipped.configs.cip.schemaUrls,
      }),
    );
  });

  function onlyWrittenFile(): { path: string; document: any } {
    expect(written.size).toBe(1);
    const [[path, text]] = [...written];
    return { path, document: yaml.parse(text) };
  }

  it("creates a chain as .chain.cip.yaml with the CIP chain schema", async () => {
    mockShowInputBox.mockResolvedValueOnce("new-chain");

    const result = await api.createEmptyChain();

    const { path, document } = onlyWrittenFile();
    expect(path).toBe(
      `/workspace/${result!.chainId}/${result!.chainId}.chain.cip.yaml`,
    );
    expect(document.$schema).toBe(`${SCHEMA}/chain`);
    expect(document.content.migrations).toBe(CHAIN_MIGRATIONS);
  });

  it.each([
    ["EXTERNAL", ".service.cip.yaml", "service", SERVICE_MIGRATIONS],
    [
      "CONTEXT",
      ".context-service.cip.yaml",
      "context-service",
      SERVICE_MIGRATIONS,
    ],
    ["MCP", ".mcp-service.cip.yaml", "mcp-service", MCP_SERVICE_MIGRATIONS],
  ])(
    "creates a service of type %s as *%s with the CIP %s schema",
    async (type, suffix, schema, migrations) => {
      mockShowInputBox.mockResolvedValue("new-service");
      mockShowQuickPick.mockResolvedValueOnce({ label: type, value: type });

      const result = await api.createEmptyService();

      const { path, document } = onlyWrittenFile();
      expect(path).toBe(
        `/workspace/${result!.serviceId}/${result!.serviceId}${suffix}`,
      );
      expect(document.$schema).toBe(`${SCHEMA}/${schema}`);
      expect(document.content.migrations).toBe(migrations);
      expect(document.content).not.toHaveProperty("protocol");
    },
  );
});
