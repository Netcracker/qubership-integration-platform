// Binds the extension's file API over the fake filesystem, as `useExtensionModules` in
// `src/web/test/suite/harness.ts` does for level 2. The context is dereferenced only by
// `getLibrary`, which no level-1 path reaches.
//
// This file is not part of the fake module: `tests/__mocks__/vscode.ts` requires `index.ts` alone,
// and the imports below reach the layer, which reads `vscode` back.
import type { ExtensionContext } from "vscode";
import { VSCodeFileApi } from "../../src/web/response/file/fileApiImpl";
import { setFileApi } from "../../src/web/response/file/fileApiProvider";

export function bindFileApi(): VSCodeFileApi {
  const api = new VSCodeFileApi({} as ExtensionContext);
  setFileApi(api);
  return api;
}
