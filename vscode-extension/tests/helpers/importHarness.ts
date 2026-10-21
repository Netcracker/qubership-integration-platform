// The pieces a specification-import case needs: the reset its `beforeEach` runs, the
// `importSpecificationGroup` message the router receives, a fixture's bytes in the shape the webview
// posts them, a golden directory read back as a tree, the tree `workspaceTree()` reports for a
// service folder holding that golden, and the prompts the user was shown.
//
// All seven files in `tests/web/import/` share `resetImportWorkspace`, `fixtureFile` and
// `errorMessagesShown`. `fixtureText`, `goldenTree` and `expectedServiceTree` belong to the five
// that compare a golden tree, one per protocol the import detects, which build the router message
// inline. `postImportGroup` and `serializedFile` belong to the two that compare no tree and write
// their documents inline, `failures.test.ts` and `importSession.test.ts`, and to the `.graphqls`
// case in `graphql.test.ts`.
import * as fs from "fs";
import * as path from "path";
import type { ExtensionContext, Uri } from "vscode";
import { resetWorkspace, window } from "../fake-vscode";
import { bindFileApi } from "../fake-vscode/fileApi";
import type { FrozenIds } from "./freezeSourceIds";
import { freezeSourceIds } from "./freezeSourceIds";
import { FileCacheService } from "../../src/web/services/FileCacheService";
import { getApiResponse } from "../../src/web/response/apiRouter";
import type { SerializedFile } from "../../src/web/api-services/importApiTypes";

const FIXTURE_DIR = path.join(__dirname, "../fixtures/specifications");

// The reset contract every import case follows, with the frozen counter it returns. `clearMocks`
// clears recorded calls and not module state, and `FileCacheService` is a singleton over that
// state, so a document the previous case seeded would otherwise be read back here.
export function resetImportWorkspace(idPrefix: string): FrozenIds {
  resetWorkspace();
  FileCacheService.getInstance().clearAll();
  bindFileApi();
  return freezeSourceIds(idPrefix);
}

// The `importSpecificationGroup` message the webview posts, routed the way the extension receives
// it: the service file's uri second, and a context third because `handleImportSpecificationGroup`
// throws `ExtensionContext is required for import operations` without one.
export function postImportGroup(
  serviceFileUri: Uri | undefined,
  payload: { systemId: string; name: string; files: SerializedFile[] },
): Promise<any> {
  return getApiResponse(
    { type: "importSpecificationGroup", payload } as any,
    serviceFileUri,
    {} as ExtensionContext,
  );
}

export function fixtureText(name: string): string {
  return fs.readFileSync(path.join(FIXTURE_DIR, name), "utf8");
}

// What the webview posts: the file's bytes with its metadata, which the import turns back into a
// `File`. `buildSerializedOpenApiFile` in `tests/helpers/mocks.ts` builds the same five fields for
// one fixed OpenAPI document, and `tests/api-services/SpecificationImportService.test.ts` is its
// only caller.
export function serializedFile(
  name: string,
  text: string,
  type = "text/plain",
): SerializedFile {
  const bytes = new TextEncoder().encode(text);
  return {
    name,
    size: bytes.byteLength,
    type,
    lastModified: 0,
    content: bytes.buffer as ArrayBuffer,
  };
}

export function fixtureFile(name: string, type = "text/plain"): SerializedFile {
  return serializedFile(name, fixtureText(name), type);
}

// The golden directory keyed by path relative to it, which is also its path relative to the service
// folder. A walk rather than a list of names, so a golden file nobody asserts cannot be left
// behind.
export function goldenTree(
  directory: string,
  prefix = "",
): Record<string, string> {
  const tree: Record<string, string> = {};
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      Object.assign(
        tree,
        goldenTree(path.join(directory, entry.name), relativePath),
      );
    } else {
      tree[relativePath] = fs.readFileSync(
        path.join(directory, entry.name),
        "utf8",
      );
    }
  }
  return tree;
}

// The golden tree as `workspaceTree()` reports it under `serviceDir`: every golden file, plus the
// `<Directory>` entry the fake lists for each folder on the way to it.
export function expectedServiceTree(
  serviceDir: string,
  golden: Record<string, string>,
): Record<string, string> {
  const tree: Record<string, string> = {};
  for (const [relativePath, text] of Object.entries(golden)) {
    tree[`${serviceDir}/${relativePath}`] = text;
  }
  for (const filePath of Object.keys(tree)) {
    const segments = filePath.split("/");
    for (let depth = 1; depth < segments.length; depth++) {
      tree[segments.slice(0, depth).join("/")] = "<Directory>";
    }
  }
  return tree;
}

export function errorMessagesShown(): string[] {
  return window.showErrorMessage.mock.calls.map((call) => call[0] as string);
}
