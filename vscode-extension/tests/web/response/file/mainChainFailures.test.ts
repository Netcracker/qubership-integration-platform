// Every way `getMainChainFileUri` in `fileApiImpl.ts` rejects, and what the user is told for each.
// `getMainChain` and `writeMainChain` both call it outside their own `try`, so its rejection reaches
// the consumer unchanged and is the only place these failures can report themselves.
//
// A missing path showed nothing before the branch around the `stat` was added. The two other
// rejections are pinned as they are: `files.length !== 1` already warns, and an absent `baseUri`
// stays silent on purpose, since no report asks for a message there.
import { FileSystemError, Uri, window, workspace } from "vscode";
import { resetWorkspace, seedWorkspace, workspaceUri } from "../../../fake-vscode";
import { bindFileApi } from "../../../fake-vscode/fileApi";
import { VSCodeFileApi } from "../../../../src/web/response/file/fileApiImpl";
import { deleteChain } from "../../../../src/web/response/chainApiModify";

const CHAIN_ID = "9f1c7a2e-5d84-4a1b-9c0e-7b2f3a5d6e10";
const CHAIN_YAML = `id: ${CHAIN_ID}\nname: a chain\ncontent:\n  elements: []\n`;
const CHAIN_DATA = { id: CHAIN_ID, name: "a chain", content: { elements: [] } };

const ABSENT = "chains/absent";
const ABSENT_PATH = `/workspace/${ABSENT}`;

const showErrorMessage = window.showErrorMessage as unknown as jest.Mock;
const showWarningMessage = window.showWarningMessage as unknown as jest.Mock;

const rejection = (promise: PromiseLike<unknown>) =>
  Promise.resolve(promise).then(
    () => undefined,
    (err) => err,
  );

let api: VSCodeFileApi;

// Every case below seeds its own files, so one empty workspace serves all three describes.
beforeEach(() => {
  resetWorkspace();
  api = bindFileApi();
});

describe("a chain read or save against a path that does not exist", () => {
  it("tells the user which path writeMainChain could not reach", async () => {
    await expect(api.writeMainChain(workspaceUri(ABSENT), CHAIN_DATA as any)).rejects.toBeDefined();

    expect(showErrorMessage).toHaveBeenCalledWith(expect.stringContaining(ABSENT_PATH));
  });

  it("tells the user which path getMainChain could not reach", async () => {
    await expect(api.getMainChain(workspaceUri(ABSENT))).rejects.toBeDefined();

    expect(showErrorMessage).toHaveBeenCalledWith(expect.stringContaining(ABSENT_PATH));
  });

  // `deleteChain` cleans up the element property files before deleting, and ignores a failure there.
  // Deleting a chain whose file is already gone is the case the message above names, and the cleanup
  // must stay silent about it: the command handler in `extension.ts` reports the delete itself, so a
  // popup here would be the second one for one action.
  it("raises no popup of its own when deleteChain cannot read the chain it deletes", async () => {
    await expect(deleteChain(workspaceUri(ABSENT))).rejects.toBeDefined();

    expect(showErrorMessage).not.toHaveBeenCalled();
  });

  it("rejects with the error workspace.fs threw rather than a wrapper", async () => {
    const fromStat = await rejection(workspace.fs.stat(workspaceUri(ABSENT)));
    const fromWrite = await rejection(api.writeMainChain(workspaceUri(ABSENT), CHAIN_DATA as any));

    expect(fromWrite).toBeInstanceOf(FileSystemError);
    expect((fromWrite as FileSystemError).code).toBe((fromStat as FileSystemError).code);
    expect((fromWrite as Error).message).toBe((fromStat as Error).message);
  });
});

describe("a chain directory that holds no single chain file", () => {
  it("warns the user when the directory holds no chain file", async () => {
    seedWorkspace({ "chains/empty": {} });

    await expect(api.getMainChain(workspaceUri("chains/empty"))).rejects.toThrow(
      "Single *.chain.cip.yaml file not found in the current directory",
    );
    expect(showWarningMessage).toHaveBeenCalledWith(
      "*.chain.cip.yaml file not found in the current directory",
    );
  });

  it("warns the user when the directory holds two chain files", async () => {
    seedWorkspace({
      [`chains/pair/${CHAIN_ID}.chain.cip.yaml`]: CHAIN_YAML,
      "chains/pair/other.chain.cip.yaml": CHAIN_YAML,
    });

    await expect(api.getMainChain(workspaceUri("chains/pair"))).rejects.toThrow(
      "Single *.chain.cip.yaml file not found in the current directory",
    );
    expect(showWarningMessage).toHaveBeenCalledWith(
      "*.chain.cip.yaml file not found in the current directory",
    );
  });
});

describe("a chain read or save with no base uri", () => {
  it("rejects silently, which is the behavior this suite pins rather than changes", async () => {
    await expect(api.getMainChain(undefined as unknown as Uri)).rejects.toThrow(
      "No base uri provided",
    );

    expect(showErrorMessage).not.toHaveBeenCalled();
    expect(showWarningMessage).not.toHaveBeenCalled();
  });
});
