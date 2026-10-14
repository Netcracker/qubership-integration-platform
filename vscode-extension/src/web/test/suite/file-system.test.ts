// What `vscode.workspace.fs` does in the host `test:integration` starts, measured rather than read
// off the API documentation. `fileApiImpl.ts` relies on these answers, and the Jest fake of `vscode`
// is written against them, so each case here records one obligation of that fake.
//
// Measured on `vscode.version` 1.142.0-insider, `env.appHost` "web", `uriScheme` "vscode-insiders",
// over the `vscode-test-web://mount/` filesystem. The suite logs the build string on every run, so a
// case that fails after a host update reports which build it failed on. Such a failure reports the
// host rather than this repository, and it turns the whole `npm-build` gate red: re-pin
// `--commit <sha>` in the `test:integration` script to unblock it, then raise the changed answer as
// its own issue, because the Jest fake below carries the old one.
//
// The answers, including the ones the documentation does not lead you to expect:
//
// - `stat` rejects a missing path with a `FileSystemError` whose `code` is "FileNotFound" and whose
//   `message` is the uri's `toString()`, not its `fsPath` and not a sentence. A plain `catch` takes it.
// - `stat` returns type 1 for a file and 2 for a directory, and leaves `permissions` undefined.
// - `readDirectory` returns `[name, FileType]` pairs in the order the entries were written, and does
//   not sort them. It rejects "FileNotADirectory" on a file and "FileNotFound" on a missing path.
// - `delete` resolves for a missing path, whatever `recursive` says.
// - `delete` removes a non-empty directory whole even without `recursive`.
// - `createDirectory` resolves for an existing directory and creates every missing parent. Over an
//   existing file it rejects with "FileExists".
// - `readFile` and `writeFile` round-trip bytes, `writeFile` replaces the contents, and it creates
//   every missing parent directory. Over a directory it rejects "FileIsADirectory", and under a
//   parent held by a file it rejects "FileExists" rather than writing beside that file.
// - `copy` copies a folder whole and rejects an existing target with "FileExists" unless `overwrite`
//   is set, which is what `harness.ts` relies on.
// - The host cannot create a symbolic link: `vscode.workspace.fs` has no member that makes one, so
//   `FileType.SymbolicLink`, which is 64, is reachable only through a fake.
import * as assert from "assert";
import * as vscode from "vscode";
import { SUITE_TIMEOUT, workspaceUri } from "./harness";

const encode = (text: string) => new TextEncoder().encode(text);
const decode = (bytes: Uint8Array) => new TextDecoder().decode(bytes);

// Fails unless the call rejects with a `FileSystemError`, and returns that error.
async function rejection(run: () => Thenable<unknown>): Promise<vscode.FileSystemError> {
  try {
    await run();
  } catch (err) {
    assert.ok(err instanceof vscode.FileSystemError, `expected a FileSystemError, got ${err}`);
    return err;
  }
  return assert.fail("the call resolved");
}

async function rejectionCode(run: () => Thenable<unknown>): Promise<string | undefined> {
  return (await rejection(run)).code;
}

suite("workspace.fs semantics", function () {
  this.timeout(SUITE_TIMEOUT);

  // Files of this suite's own: the host keeps writes in memory, and the teardown removes them.
  const root = () => workspaceUri("fs-semantics");
  const at = (path: string) => vscode.Uri.joinPath(root(), path);

  suiteSetup(() => {
    console.log(`workspace.fs measured on ${vscode.version}, appHost ${vscode.env.appHost}`);
  });

  setup(async () => {
    await vscode.workspace.fs.createDirectory(root());
    await vscode.workspace.fs.writeFile(at("file.txt"), encode("one"));
  });

  teardown(() => vscode.workspace.fs.delete(root(), { recursive: true }));

  // Every answer below is the web host's. On a desktop host the same calls reach real disk, where
  // some of them behave differently.
  test("the host is the web one, which keeps every write in memory", () => {
    assert.strictEqual(vscode.env.appHost, "web");
    assert.strictEqual(root().scheme, "vscode-test-web");
  });

  test("stat rejects a missing path with FileNotFound, and names the uri in the message", async () => {
    const missing = at("absent/chain.cip.yaml");
    const error = await rejection(() => vscode.workspace.fs.stat(missing));
    assert.strictEqual(error.code, "FileNotFound");
    assert.strictEqual(error.message, missing.toString());
  });

  test("a missing path's rejection reaches a plain catch, so getParentDirectoryUri falls back", async () => {
    // fileApiImpl.ts:125-133 takes a rejection as "the path does not exist" and falls back, so the
    // catch has to run for a missing path and not for a file.
    const typeOrFallback = async (uri: vscode.Uri): Promise<vscode.FileType | "fallback"> => {
      try {
        return (await vscode.workspace.fs.stat(uri)).type;
      } catch {
        return "fallback";
      }
    };

    assert.strictEqual(await typeOrFallback(at("absent/chain.cip.yaml")), "fallback");
    assert.strictEqual(await typeOrFallback(at("file.txt")), vscode.FileType.File);
  });

  test("stat reports File for a file and Directory for a directory", async () => {
    const file = await vscode.workspace.fs.stat(at("file.txt"));
    assert.strictEqual(file.type, vscode.FileType.File);
    assert.strictEqual(file.size, 3);
    assert.strictEqual(file.permissions, undefined);

    const directory = await vscode.workspace.fs.stat(root());
    assert.strictEqual(directory.type, vscode.FileType.Directory);
  });

  test("readDirectory returns [name, FileType] pairs", async () => {
    await vscode.workspace.fs.createDirectory(at("sub"));
    assert.deepStrictEqual(await vscode.workspace.fs.readDirectory(root()), [
      ["file.txt", vscode.FileType.File],
      ["sub", vscode.FileType.Directory],
    ]);
  });

  // The case above writes its entries in already-sorted order, so it cannot tell a sort from the
  // write order. This one writes them the other way round, and the host reports them that way.
  test("readDirectory reports the write order and does not sort", async () => {
    await vscode.workspace.fs.writeFile(at("order/b.txt"), encode("two"));
    await vscode.workspace.fs.writeFile(at("order/a.txt"), encode("one"));

    const names = (await vscode.workspace.fs.readDirectory(at("order"))).map(([name]) => name);
    assert.deepStrictEqual(names, ["b.txt", "a.txt"]);
  });

  test("readDirectory rejects a file with FileNotADirectory and a missing path with FileNotFound", async () => {
    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.readDirectory(at("file.txt"))), "FileNotADirectory");
    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.readDirectory(at("absent"))), "FileNotFound");
  });

  test("delete resolves for a missing path, whatever recursive says", async () => {
    await vscode.workspace.fs.delete(at("absent.txt"));
    await vscode.workspace.fs.delete(at("absent"), { recursive: false });
    await vscode.workspace.fs.delete(at("absent"), { recursive: true });
  });

  test("delete removes a non-empty directory whole, without recursive", async () => {
    await vscode.workspace.fs.createDirectory(at("tree/inner"));
    await vscode.workspace.fs.writeFile(at("tree/inner/leaf.txt"), encode("two"));

    await vscode.workspace.fs.delete(at("tree"));

    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.stat(at("tree"))), "FileNotFound");
    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.stat(at("tree/inner/leaf.txt"))), "FileNotFound");
  });

  test("createDirectory resolves for an existing directory and creates every missing parent", async () => {
    await vscode.workspace.fs.createDirectory(root());
    await vscode.workspace.fs.createDirectory(at("made/up/deep"));

    for (const path of ["made", "made/up", "made/up/deep"]) {
      assert.strictEqual((await vscode.workspace.fs.stat(at(path))).type, vscode.FileType.Directory, path);
    }
  });

  test("createDirectory rejects a path held by a file with FileExists", async () => {
    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.createDirectory(at("file.txt"))), "FileExists");
  });

  test("readFile and writeFile round-trip bytes, and writeFile replaces the contents", async () => {
    const bytes = new Uint8Array([0, 1, 2, 255, 10, 13]);
    await vscode.workspace.fs.writeFile(at("bytes.bin"), bytes);
    assert.deepStrictEqual(await vscode.workspace.fs.readFile(at("bytes.bin")), bytes);

    await vscode.workspace.fs.writeFile(at("file.txt"), encode("two"));
    assert.strictEqual(decode(await vscode.workspace.fs.readFile(at("file.txt"))), "two");
    assert.strictEqual((await vscode.workspace.fs.stat(at("file.txt"))).size, 3);
  });

  test("writeFile creates every missing parent directory", async () => {
    await vscode.workspace.fs.writeFile(at("fresh/deeper/file.txt"), encode("two"));

    assert.strictEqual(decode(await vscode.workspace.fs.readFile(at("fresh/deeper/file.txt"))), "two");
    assert.strictEqual((await vscode.workspace.fs.stat(at("fresh"))).type, vscode.FileType.Directory);
  });

  // Creating the parents stops at a parent held by a file: the write is refused rather than left to
  // land beside it.
  test("writeFile rejects a path held by a directory, and a path under a file", async () => {
    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.writeFile(root(), encode("two"))), "FileIsADirectory");
    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.writeFile(at("file.txt/under.txt"), encode("two"))), "FileExists");
  });

  test("readFile rejects a directory with FileIsADirectory", async () => {
    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.readFile(root())), "FileIsADirectory");
  });

  // `copy` is what harness.ts:90 uses to give a spec a copy of a fixture folder to write on, which
  // is the whole of what this suite pins: the layer itself never calls it.
  test("copy copies a folder whole, and rejects an existing target unless overwrite is set", async () => {
    await vscode.workspace.fs.createDirectory(at("tree/inner"));
    await vscode.workspace.fs.writeFile(at("tree/inner/leaf.txt"), encode("two"));

    await vscode.workspace.fs.copy(at("tree"), at("copied"));
    assert.strictEqual(decode(await vscode.workspace.fs.readFile(at("copied/inner/leaf.txt"))), "two");

    assert.strictEqual(await rejectionCode(() => vscode.workspace.fs.copy(at("tree"), at("copied"))), "FileExists");

    await vscode.workspace.fs.writeFile(at("tree/inner/leaf.txt"), encode("three"));
    await vscode.workspace.fs.copy(at("tree"), at("copied"), { overwrite: true });
    assert.strictEqual(decode(await vscode.workspace.fs.readFile(at("copied/inner/leaf.txt"))), "three");
  });

  test("the host has no way to create a symbolic link", async () => {
    // The members the layer and this suite call are present, and no member makes a link. Pinning the
    // set exactly would turn the gate red on any member a later insiders build adds.
    const members = Object.keys(vscode.workspace.fs);
    for (const name of ["copy", "createDirectory", "delete", "readDirectory", "readFile", "stat", "writeFile"]) {
      assert.ok(members.includes(name), `workspace.fs has no ${name}, but ${members.join(", ")}`);
    }
    assert.deepStrictEqual(members.filter((name) => /link/i.test(name)), []);
    // Nothing here can produce the bitmask, so a stat in this host returns a plain type.
    assert.strictEqual(vscode.FileType.SymbolicLink, 64);
    assert.strictEqual((await vscode.workspace.fs.stat(at("file.txt"))).type, vscode.FileType.File);
  });
});
