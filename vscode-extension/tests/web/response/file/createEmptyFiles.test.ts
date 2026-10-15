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
        // Records every read, so a test can show that a file was never read.
        readFile: async (uri: any) => {
          read.push(uri.path);
          if (uri.path === "/workspace/.config.qip.yaml") {
            return new TextEncoder().encode(
              "version: '1.0'\nconfigs:\n  cip:\n    extensions:\n      chain: .chain.custom.yaml\n",
            );
          }
          return nodeFs.readFileSync(uri.path);
        },
        stat: async (uri: any) => {
          if (uri.path === "/workspace/.config.qip.yaml") {
            return { type: 1 };
          }
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

const SCHEMA =
  "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model";
const EXTENSION_ROOT = nodePath.resolve(__dirname, "../../../..");

describe("new files", () => {
  let api: VSCodeFileApi;
  beforeEach(() => {
    written.clear();
    read.length = 0;
    const context = {
      extensionUri: createMockUri(EXTENSION_ROOT),
    } as unknown as ExtensionContext;
    api = new VSCodeFileApi(context);
    setFileApi(api);
  });

  it("ignores a workspace .config.qip.yaml", async () => {
    mockShowInputBox.mockResolvedValueOnce("new-chain");

    const result = await api.createEmptyChain();

    expect(read).not.toContain("/workspace/.config.qip.yaml");
    expect([...written.keys()]).toEqual([
      `/workspace/${result!.chainId}/${result!.chainId}.chain.cip.yaml`,
    ]);
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
  });

  it.each([
    ["EXTERNAL", ".service.cip.yaml", "service"],
    ["CONTEXT", ".context-service.cip.yaml", "context-service"],
    ["MCP", ".mcp-service.cip.yaml", "mcp-service"],
  ])(
    "creates a service of type %s as *%s with the CIP %s schema",
    async (type, suffix, schema) => {
      mockShowInputBox.mockResolvedValue("new-service");
      mockShowQuickPick.mockResolvedValueOnce({ label: type, value: type });

      const result = await api.createEmptyService();

      const { path, document } = onlyWrittenFile();
      expect(path).toBe(
        `/workspace/${result!.serviceId}/${result!.serviceId}${suffix}`,
      );
      expect(document.$schema).toBe(`${SCHEMA}/${schema}`);
    },
  );
});
