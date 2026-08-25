// Six service file kinds now share one folder, so the risk this suite guards is shadowing: a name
// that resolves to a sibling's editor. It has to hold on both sides — the resolver's `endsWith`
// rule and the `filenamePattern` globs of package.json, which are a separate matcher.

import * as fs from "fs";
import * as path from "path";
import { Uri } from "vscode";
import {
  DEFAULT_EDITOR_VIEW_TYPES,
  getEditorViewTypeForUri,
  openDocumentInEditor,
} from "../../src/web/editorViewTypes";

const mockExecuteCommand = jest.fn();

jest.mock("vscode", () => {
  const uriModule = jest.requireActual("../__mocks__/vscode");
  return {
    commands: {
      executeCommand: (...args: unknown[]) => mockExecuteCommand(...args),
    },
    Uri: uriModule.Uri,
  };
});

jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn(() => ({
    chain: ".chain.qip.yaml",
    service: ".service.qip.yaml",
    externalService: ".external-service.qip.yaml",
    internalService: ".internal-service.qip.yaml",
    implementedService: ".implemented-service.qip.yaml",
    contextService: ".context-service.qip.yaml",
    mcpService: ".mcp-service.qip.yaml",
  })),
}));

const SERVICE_FILES: [string, string][] = [
  ["service-1.service.qip.yaml", "qip.serviceFile.editor"],
  ["service-1.external-service.qip.yaml", "qip.externalServiceFile.editor"],
  ["service-1.internal-service.qip.yaml", "qip.internalServiceFile.editor"],
  [
    "service-1.implemented-service.qip.yaml",
    "qip.implementedServiceFile.editor",
  ],
  ["service-1.context-service.qip.yaml", "qip.contextServiceFile.editor"],
  ["service-1.mcp-service.qip.yaml", "qip.mcpServiceFile.editor"],
];

function readManifestPatterns(): { viewType: string; pattern: string }[] {
  const manifest = JSON.parse(
    fs.readFileSync(path.join(__dirname, "../../package.json"), "utf8"),
  );
  return manifest.contributes.customEditors.flatMap(
    (editor: { viewType: string; selector: { filenamePattern: string }[] }) =>
      editor.selector.map((selector) => ({
        viewType: editor.viewType,
        pattern: selector.filenamePattern,
      })),
  );
}

// VS Code matches a `filenamePattern` without a slash against the file name, with `*` standing
// for any run of characters inside one path segment. That is not the resolver's `endsWith` rule.
function globMatches(pattern: string, fileName: string): boolean {
  const source = pattern
    .split("*")
    .map((part) => part.replace(/[.+?^${}()|[\]\\]/g, "\\$&"))
    .join("[^/]*");
  return new RegExp(`^${source}$`).test(fileName);
}

describe("editorViewTypes", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockExecuteCommand.mockResolvedValue(undefined);
  });

  describe("getEditorViewTypeForUri", () => {
    test("returns chain editor for chain files", () => {
      const uri = Uri.file("/workspace/chains/chain-1.chain.qip.yaml");

      expect(getEditorViewTypeForUri(uri)).toBe("qip.chainFile.editor");
    });

    test.each(SERVICE_FILES)(
      "resolves %s to its own editor",
      (name, viewType) => {
        const uri = Uri.file(`/workspace/services/service-1/${name}`);

        expect(getEditorViewTypeForUri(uri)).toBe(viewType);
      },
    );

    test("no service file kind resolves to another kind's editor", () => {
      const resolved = SERVICE_FILES.map(([name]) =>
        getEditorViewTypeForUri(Uri.file(`/workspace/services/svc/${name}`)),
      );

      expect(new Set(resolved).size).toBe(SERVICE_FILES.length);
    });

    test("a typed service file never resolves to the plain service editor", () => {
      const typed = SERVICE_FILES.filter(
        ([name]) => name !== "service-1.service.qip.yaml",
      );

      for (const [name] of typed) {
        expect(
          getEditorViewTypeForUri(Uri.file(`/workspace/services/svc/${name}`)),
        ).not.toBe("qip.serviceFile.editor");
      }
    });

    test("throws when no editor matches the file extension", () => {
      const uri = Uri.file("/workspace/readme.txt");

      expect(() => getEditorViewTypeForUri(uri)).toThrow(
        "Unable to find an editor for document",
      );
    });
  });

  describe("openDocumentInEditor", () => {
    test("opens document with the matching custom editor", async () => {
      const uri = Uri.file("/workspace/chains/chain-1.chain.qip.yaml");

      await openDocumentInEditor(uri);

      expect(mockExecuteCommand).toHaveBeenCalledWith(
        "vscode.openWith",
        uri,
        "qip.chainFile.editor",
      );
    });

    test("opens a typed service file with its own editor", async () => {
      const uri = Uri.file(
        "/workspace/services/svc/svc.internal-service.qip.yaml",
      );

      await openDocumentInEditor(uri);

      expect(mockExecuteCommand).toHaveBeenCalledWith(
        "vscode.openWith",
        uri,
        "qip.internalServiceFile.editor",
      );
    });
  });

  describe("package.json custom editors", () => {
    test("every view type the resolver returns is contributed", () => {
      const contributed = readManifestPatterns().map((entry) => entry.viewType);

      for (const viewType of Object.values(DEFAULT_EDITOR_VIEW_TYPES)) {
        expect(contributed).toContain(viewType);
      }
    });

    test("each service file name matches exactly one filenamePattern", () => {
      const patterns = readManifestPatterns();

      for (const [name] of SERVICE_FILES) {
        const matched = patterns.filter((entry) =>
          globMatches(entry.pattern, name),
        );

        expect(matched.map((entry) => entry.pattern)).toHaveLength(1);
      }
    });

    test("the pattern that claims a file agrees with the resolver", () => {
      const patterns = readManifestPatterns();

      for (const [name, viewType] of SERVICE_FILES) {
        const matched = patterns.find((entry) =>
          globMatches(entry.pattern, name),
        );

        expect(matched?.viewType).toBe(viewType);
      }
    });

    test("the plain service pattern does not claim a typed service file", () => {
      const plain = readManifestPatterns().find(
        (entry) => entry.viewType === "qip.serviceFile.editor",
      );

      expect(plain).toBeDefined();
      expect(globMatches(plain!.pattern, "svc.external-service.qip.yaml")).toBe(
        false,
      );
      expect(globMatches(plain!.pattern, "svc.context-service.qip.yaml")).toBe(
        false,
      );
      expect(globMatches(plain!.pattern, "svc.service.qip.yaml")).toBe(true);
    });
  });
});
