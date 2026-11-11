// The `FileType` comparisons in `fileApiImpl.ts` against a symbolic link. `FileStat.type` is a
// bitmask, so a link to a file stats as `File | SymbolicLink` (65) and a strict comparison with
// `FileType.File` took such a chain file for a directory.
//
// These cases run at level 1 and not level 2 because `vscode.workspace.fs` has no member that
// creates a link, as `src/web/test/suite/file-system.test.ts` measured: only a seed in the Jest
// fake can plant one.
//
// The last two cases pin the other half of the fix. The `FileType.Directory` comparisons stay
// strict, so `deleteFile` deletes a link to a directory instead of reading through it, and
// `getFileType` answers `UNKNOWN` for a path it cannot stat rather than rejecting.
import { FileType } from "vscode";
import {
  resetWorkspace,
  seedWorkspace,
  workspaceTree,
  workspaceUri,
} from "../../../fake-vscode";
import { bindFileApi } from "../../../fake-vscode/fileApi";
import { VSCodeFileApi } from "../../../../src/web/response/file/fileApiImpl";
import { CipFileType } from "../../../../src/web/response/serviceApiUtils";

const SYMLINKED_FILE = FileType.File | FileType.SymbolicLink;
const SYMLINKED_DIRECTORY = FileType.Directory | FileType.SymbolicLink;

const CHAIN_ID = "c2e0d4f4-1f24-4a4e-9a07-3f0f2a9c58b1";
const CHAIN_DIRECTORY = `chains/${CHAIN_ID}`;
const CHAIN_FILE = `${CHAIN_DIRECTORY}/${CHAIN_ID}.chain.cip.yaml`;
const CHAIN_YAML = `id: ${CHAIN_ID}\nname: linked chain\ncontent:\n  elements: []\n`;

describe("the file API reads through a symbolic link to a file", () => {
  let api: VSCodeFileApi;

  beforeEach(() => {
    resetWorkspace();
    seedWorkspace({ [CHAIN_FILE]: { content: CHAIN_YAML, type: SYMLINKED_FILE } });
    api = bindFileApi();
  });

  it("loads a linked chain file addressed directly", async () => {
    await expect(api.getMainChain(workspaceUri(CHAIN_FILE))).resolves.toMatchObject({
      id: CHAIN_ID,
    });
  });

  it("loads a linked chain file addressed by its directory", async () => {
    await expect(api.getMainChain(workspaceUri(CHAIN_DIRECTORY))).resolves.toMatchObject({
      id: CHAIN_ID,
    });
  });

  it("builds a linked chain file while walking a folder", async () => {
    const chains: any[] = [];
    await api.findAndBuildChainsRecursively(workspaceUri("chains"), (chain) => chain, chains);

    expect(chains).toMatchObject([{ id: CHAIN_ID }]);
  });

  it("collects a linked chain file while searching the workspace", async () => {
    const found = await api.findFiles(".chain.cip.yaml");

    expect(found.map((uri) => uri.path)).toEqual([`/workspace/${CHAIN_FILE}`]);
  });

  it("reports a linked chain file as a chain", async () => {
    await expect(api.getFileType(workspaceUri(CHAIN_FILE))).resolves.toBe(CipFileType.CHAIN);
  });

  it("writes to a linked file instead of taking it for its own parent directory", async () => {
    await api.writeFile(workspaceUri(CHAIN_FILE), new TextEncoder().encode("name: rewritten\n"));

    expect(workspaceTree()[CHAIN_FILE]).toBe("name: rewritten\n");
  });
});

describe("the file API leaves a symbolic link to a directory alone", () => {
  let api: VSCodeFileApi;

  // The fake has no link resolution, so the target's contents are seeded under the link itself:
  // reading through the link is what a `FileType.Directory` mask would do.
  beforeEach(() => {
    resetWorkspace();
    seedWorkspace({
      "links/chains": { type: SYMLINKED_DIRECTORY },
      [`links/chains/${CHAIN_ID}.chain.cip.yaml`]: CHAIN_YAML,
    });
    api = bindFileApi();
  });

  it("deletes a link to a non-empty directory rather than rejecting it as not empty", async () => {
    await expect(api.deleteFile(workspaceUri("links/chains"))).resolves.toBeUndefined();

    expect(Object.keys(workspaceTree())).not.toContain("links/chains");
  });

  it("answers UNKNOWN for a path it cannot stat", async () => {
    await expect(api.getFileType(workspaceUri("links/absent.chain.cip.yaml"))).resolves.toBe(
      CipFileType.UNKNOWN,
    );
  });
});
