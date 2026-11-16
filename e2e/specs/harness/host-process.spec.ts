import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { repoRoot, runToCompletion, stream } from "../../env/host.js";
import { groupAlive, RecordedServer, stopGroup } from "../../support/process-group.js";
import { readRunResources, RESOURCES_FILE, SAMPLES_FILE, startResourceSampler, stopResourceSampler } from "../../env/resources.js";

const made: string[] = [];
const scratch = () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e host processes "));
  made.push(dir);
  return dir;
};

function cli(dir: string): string {
  const script = path.join(dir, "record.cjs");
  fs.writeFileSync(script, `require('node:fs').writeFileSync('result.json', JSON.stringify({args: process.argv.slice(2), value: process.env.E2E_HOST_VALUE})); if (process.env.E2E_HOST_FAIL) { console.error('command failed'); process.exit(7); }`);
  const command = path.join(dir, process.platform === "win32" ? "mvn.cmd" : "mvn");
  fs.writeFileSync(command, process.platform === "win32"
    ? `@echo off\r\n"${process.execPath}" "${script}" %*\r\n`
    : `#!/bin/sh\nexec '${process.execPath}' '${script}' "$@"\n`, { mode: 0o755 });
  return command;
}

const args = ["a path with spaces", 'a "quoted" value', "a&b", "(x)", "100%", "{{json .}}"];

test.describe("host processes", () => {
  test.afterEach(() => {
    for (const dir of made.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
  });

  test("build commands preserve arguments and environment", async () => {
    const dir = scratch();
    await runToCompletion(cli(dir), args, dir, { ...process.env, E2E_HOST_VALUE: "inherited" });
    expect(JSON.parse(fs.readFileSync(path.join(dir, "result.json"), "utf8"))).toEqual({ args, value: "inherited" });
    await stream(cli(dir), args, dir);
    expect(JSON.parse(fs.readFileSync(path.join(dir, "result.json"), "utf8")).args).toEqual(args);
  });

  test("failed commands report their output and exit code", async () => {
    const dir = scratch();
    await expect(runToCompletion(cli(dir), [], dir, { ...process.env, E2E_HOST_FAIL: "1" }))
      .rejects.toThrow(/exited 7:\s+command failed/);
    await expect(runToCompletion(path.join(dir, "missing"), [], dir, process.env)).rejects.toThrow(/ENOENT/);
  });

  test("Windows checkout preserves frozen fixture bytes", () => {
    for (const file of ["CHECKSUMS", "chain-1-30-100-105/deprecated-routing.chain.cip.yaml"]) {
      const relative = `e2e/fixtures/archives/frozen/${file}`;
      const checkedOut = execFileSync("git", ["-c", "core.autocrlf=true", "cat-file", "--filters", `HEAD:${relative}`], { cwd: repoRoot() });
      expect(checkedOut.equals(fs.readFileSync(path.join(repoRoot(), relative))), relative).toBe(true);
    }
  });

  test("server teardown stops the wrapper and its child", async () => {
    const dir = scratch();
    const childFile = path.join(dir, "child.pid");
    const source = `const {spawn}=require('node:child_process'); const fs=require('node:fs'); const child=spawn(process.execPath, ['-e','setInterval(()=>{},1000)'], {stdio:'ignore'}); fs.writeFileSync(process.argv[1], String(child.pid)); setInterval(()=>{},1000);`;
    const child = spawn(process.execPath, ["-e", source, childFile, "e2e-tree-server"], { detached: true, stdio: "ignore" });
    child.unref();
    const pid = child.pid!;
    let descendant: number | undefined;
    try {
      await expect.poll(() => fs.existsSync(childFile)).toBe(true);
      descendant = Number(fs.readFileSync(childFile, "utf8"));
      const record = new RecordedServer(path.join(dir, "server.json"), /e2e-tree-server/, process.pid);
      record.record(pid);
      expect(groupAlive(pid)).toBe(true);
      await record.stop();
      await expect.poll(() => groupAlive(pid)).toBe(false);
      await expect.poll(() => {
        try { process.kill(descendant!, 0); return true; } catch { return false; }
      }).toBe(false);
    } finally {
      await stopGroup(pid);
      if (descendant) { try { process.kill(descendant); } catch {} }
    }
  });

  test("resource sampling runs without a host shell", async () => {
    const dir = scratch();
    const file = path.join(dir, "samples with spaces.jsonl");
    const command = { command: process.execPath, args: ["-e", "console.log('sample')"] };
    const child = spawn(process.execPath, [path.join(repoRoot(), "e2e/support/resource-sampler.mjs"), JSON.stringify(command), file, "20", "1000"], { stdio: "pipe" });
    const code = await new Promise((resolve) => child.once("close", resolve));
    expect(code).toBe(0);
    expect(fs.readFileSync(file, "utf8").trim().split(/\r?\n/).every((line) => line === "sample")).toBe(true);
    expect(fs.readFileSync(file, "utf8").trim()).not.toBe("");
  });

  test("the provisioner records and stops its resource sampler", async () => {
    const run = "host-process-sampler";
    const pid = startResourceSampler(run, [], { command: process.execPath, args: ["-e", "console.log('sample')"] });
    try {
      expect(pid).not.toBeNull();
      expect(readRunResources()?.samplerPid).toBe(pid);
      await expect.poll(() => fs.readFileSync(SAMPLES_FILE, "utf8").trim()).toBe("sample");
      expect(stopResourceSampler(RESOURCES_FILE, run)).toBe(true);
      await expect.poll(() => groupAlive(pid!)).toBe(false);
      expect(readRunResources()?.samplerPid).toBeUndefined();
    } finally {
      stopResourceSampler(RESOURCES_FILE, run);
      if (pid) await stopGroup(pid);
      fs.rmSync(RESOURCES_FILE, { force: true });
      fs.rmSync(SAMPLES_FILE, { force: true });
    }
  });
});
