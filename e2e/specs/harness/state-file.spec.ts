/**
 * The write every state file goes through: a staging file and a rename, so a reader never meets a
 * file truncated mid-write. The writers' own specs check only that they call it.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { readStateFile, writeStateFile } from "../../support/state-file.js";

test.describe("state file", () => {
  test("is replaced through a rename and leaves no staging file", () => {
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "e2e-state-")), "nested", ".e2e-state.json");

    writeStateFile(file, { pid: 4242 });
    const inode = fs.statSync(file).ino;
    writeStateFile(file, { pid: 4343 });

    // A rename replaces the inode, and a write in place keeps it.
    expect(fs.statSync(file).ino).not.toBe(inode);
    expect(readStateFile(file)).toEqual({ pid: 4343 });
    expect(fs.readdirSync(path.dirname(file))).toEqual([path.basename(file)]);
  });
});
