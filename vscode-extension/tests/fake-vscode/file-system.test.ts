// What the Jest fake of `vscode` promises the suites above it. Each case here holds the fake to one
// answer `src/web/test/suite/file-system.test.ts` measured against the real web host, so a shortcut
// in the fake that is convenient and wrong turns this file red rather than quietly making a handler
// test lie. The symbolic-link cases are the exception: the host has no member that creates a link,
// so they pin what the fake promises instead of what was measured.
import * as vscode from "vscode";
import {
  FileType,
  resetWorkspace,
  seedWorkspace,
  window as fakeWindow,
  workspaceTree,
  workspaceUri,
} from ".";

const fs = vscode.workspace.fs;
const uri = workspaceUri;
const encode = (text: string) => new TextEncoder().encode(text);
const decode = (bytes: Uint8Array) => new TextDecoder().decode(bytes);

async function rejectionCode(run: () => Thenable<unknown>): Promise<string | undefined> {
  try {
    await run();
  } catch (err) {
    expect(err).toBeInstanceOf(vscode.FileSystemError);
    return (err as vscode.FileSystemError).code;
  }
  throw Error("the call resolved");
}

describe("the fake workspace.fs", () => {
  beforeEach(() => {
    resetWorkspace();
    seedWorkspace({
      "chains/one.chain.cip.yaml": "id: one\n",
      "chains/nested": {},
    });
  });

  test("stat rejects a missing path with FileNotFound, and names the uri in the message", async () => {
    const missing = uri("absent/chain.cip.yaml");

    await expect(fs.stat(missing)).rejects.toMatchObject({
      code: "FileNotFound",
      message: missing.toString(),
    });
    await expect(fs.stat(missing)).rejects.toBeInstanceOf(vscode.FileSystemError);
  });

  test("stat reports File for a file and Directory for a directory", async () => {
    expect((await fs.stat(uri("chains/one.chain.cip.yaml"))).type).toBe(FileType.File);
    expect((await fs.stat(uri("chains/one.chain.cip.yaml"))).size).toBe(8);
    expect((await fs.stat(uri("chains"))).type).toBe(FileType.Directory);
  });

  test("a seed carrying a type stats as that bitmask, which no host call can produce", async () => {
    seedWorkspace({
      "links/file.chain.cip.yaml": {
        content: "id: linked\n",
        type: FileType.File | FileType.SymbolicLink,
      },
      "links/folder": { type: FileType.Directory | FileType.SymbolicLink },
    });

    const file = (await fs.stat(uri("links/file.chain.cip.yaml"))).type;
    expect(file).toBe(65);
    expect(file & FileType.File).toBe(FileType.File);

    const folder = (await fs.stat(uri("links/folder"))).type;
    expect(folder).toBe(66);
    expect(folder & FileType.Directory).toBe(FileType.Directory);
  });

  test("joinPath joins the segments onto the base path", () => {
    expect(vscode.Uri.joinPath(uri("chains"), "one.chain.cip.yaml").path).toBe(
      "/workspace/chains/one.chain.cip.yaml",
    );
    expect(vscode.Uri.joinPath(uri(), "chains", "nested", "..").path).toBe("/workspace/chains");
    expect(vscode.Uri.file("/tmp/export").path).toBe("/tmp/export");
    expect(vscode.Uri.parse("file:///tmp/export").path).toBe("/tmp/export");
  });

  test("readDirectory returns [name, FileType] pairs in the order they were planted", async () => {
    expect(await fs.readDirectory(uri("chains"))).toEqual([
      ["one.chain.cip.yaml", FileType.File],
      ["nested", FileType.Directory],
    ]);
  });

  test("readDirectory rejects a file, and readFile rejects a directory", async () => {
    expect(await rejectionCode(() => fs.readDirectory(uri("chains/one.chain.cip.yaml")))).toBe(
      "FileNotADirectory",
    );
    expect(await rejectionCode(() => fs.readDirectory(uri("absent")))).toBe("FileNotFound");
    expect(await rejectionCode(() => fs.readFile(uri("chains")))).toBe("FileIsADirectory");
  });

  test("delete resolves for a missing path, whatever recursive says", async () => {
    await fs.delete(uri("absent.txt"));
    await fs.delete(uri("absent"), { recursive: false });
    await fs.delete(uri("absent"), { recursive: true });
  });

  test("delete removes a non-empty directory whole, without recursive", async () => {
    await fs.delete(uri("chains"));

    expect(workspaceTree()).toEqual({});
    expect(await rejectionCode(() => fs.stat(uri("chains/one.chain.cip.yaml")))).toBe(
      "FileNotFound",
    );
  });

  test("writeFile round-trips bytes, replaces the contents, and creates every missing parent", async () => {
    const bytes = new Uint8Array([0, 1, 2, 255, 10, 13]);
    await fs.writeFile(uri("fresh/deeper/bytes.bin"), bytes);
    expect(await fs.readFile(uri("fresh/deeper/bytes.bin"))).toEqual(bytes);
    expect((await fs.stat(uri("fresh"))).type).toBe(FileType.Directory);

    await fs.writeFile(uri("chains/one.chain.cip.yaml"), encode("id: two\n"));
    expect(decode(await fs.readFile(uri("chains/one.chain.cip.yaml")))).toBe("id: two\n");
  });

  test("writeFile rejects a path held by a directory, and a path under a file", async () => {
    expect(await rejectionCode(() => fs.writeFile(uri("chains"), encode("id: two\n")))).toBe(
      "FileIsADirectory",
    );
    expect(
      await rejectionCode(() =>
        fs.writeFile(uri("chains/one.chain.cip.yaml/under.txt"), encode("id: two\n")),
      ),
    ).toBe("FileExists");
    expect(workspaceTree()["chains/one.chain.cip.yaml"]).toBe("id: one\n");
  });

  test("deleting a symbolic link removes the link and leaves what was seeded under it", async () => {
    seedWorkspace({
      "links/chains": { type: FileType.Directory | FileType.SymbolicLink },
      "links/chains/two.chain.cip.yaml": "id: two\n",
    });

    await fs.delete(uri("links/chains"));

    expect(await rejectionCode(() => fs.stat(uri("links/chains")))).toBe("FileNotFound");
    expect(decode(await fs.readFile(uri("links/chains/two.chain.cip.yaml")))).toBe("id: two\n");
  });

  test("createDirectory resolves for an existing directory, creates parents, and rejects a file", async () => {
    await fs.createDirectory(uri("chains"));
    await fs.createDirectory(uri("made/up/deep"));

    for (const madePath of ["made", "made/up", "made/up/deep"]) {
      expect((await fs.stat(uri(madePath))).type).toBe(FileType.Directory);
    }
    expect(await rejectionCode(() => fs.createDirectory(uri("chains/one.chain.cip.yaml")))).toBe(
      "FileExists",
    );
  });

  test("workspaceTree shows a file's text and a directory's type", () => {
    expect(workspaceTree()).toEqual({
      chains: "<Directory>",
      "chains/nested": "<Directory>",
      "chains/one.chain.cip.yaml": "id: one\n",
    });
  });

  test("resetWorkspace empties the tree and leaves the workspace root behind", async () => {
    resetWorkspace();

    expect(workspaceTree()).toEqual({});
    expect((await fs.stat(uri())).type).toBe(FileType.Directory);
    expect(vscode.workspace.workspaceFolders?.[0]?.uri.path).toBe("/workspace");
  });

  test("a prompt records its call and resolves undefined, which is what a dismissed one gives", async () => {
    expect(await vscode.window.showWarningMessage("stale file")).toBeUndefined();
    expect(vscode.window.showWarningMessage).toHaveBeenCalledWith("stale file");
  });

  // `clearMocks` runs `mockClear`, which keeps a queued `mockResolvedValueOnce` and a plain
  // `mockResolvedValue` alike, so without the reset the next case here gets an answer it never set.
  test("resetWorkspace drops a stubbed answer, consumed or not", async () => {
    fakeWindow.showInputBox.mockResolvedValueOnce("one.chain.cip.yaml");
    fakeWindow.showQuickPick.mockResolvedValue("Chain");

    resetWorkspace();

    // `mockReset` drops the implementation too, so the reset puts it back and a prompt still
    // returns a promise.
    const answer = fakeWindow.showInputBox();
    expect(answer).toBeInstanceOf(Promise);
    expect(await answer).toBeUndefined();
    expect(await vscode.window.showQuickPick([])).toBeUndefined();
  });
});
