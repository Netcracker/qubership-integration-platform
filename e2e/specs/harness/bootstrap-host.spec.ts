import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { test, expect } from "@playwright/test";
import { bashExecutable, repoRoot } from "../../env/host.js";

const made: string[] = [];

function bootstrap(args: string[], failCni = false, existing = false) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-bootstrap-"));
  made.push(dir);
  const log = path.join(dir, "commands.jsonl");
  const program = `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const tool = path.basename(process.argv[1]);
const args = process.argv.slice(2);
fs.appendFileSync(process.env.HOST_BOOTSTRAP_LOG, JSON.stringify([tool, ...args]) + '\\n');
if (tool === 'kind' && args[0] === 'get' && process.env.HOST_EXISTING_KIND) console.log('qip');
if (tool === 'kubectl' && args.join(' ') === 'config current-context') console.log('docker-desktop');
if (tool === 'kubectl' && args.includes('get') && args.includes('metrics-server')) console.log('v0.9.0 --kubelet-insecure-tls');
if (process.env.HOST_FAIL_CNI && (args.includes('daemonset/istio-cni-node') || args.includes('daemonset/ztunnel'))) process.exit(19);
`;
  for (const tool of ["kubectl", "helm", "kind", "istioctl"]) {
    fs.writeFileSync(path.join(dir, tool), program, { mode: 0o755 });
  }
  const result = spawnSync(bashExecutable(), [path.join(repoRoot(), "e2e/k8s/bootstrap-cluster.sh").split(path.sep).join("/"), ...args], {
    env: {
      ...process.env,
      PATH: `${dir}${path.delimiter}${process.env.PATH}`,
      HOST_BOOTSTRAP_LOG: log,
      HOST_FAIL_CNI: failCni ? "1" : "",
      HOST_EXISTING_KIND: existing ? "1" : "",
      E2E_ZTUNNEL_ORIG_SRC: "false",
    },
    encoding: "utf8",
    timeout: 10_000,
  });
  const commands = fs.readFileSync(log, "utf8").trim().split("\n").map((line) => JSON.parse(line) as string[]);
  return { ...result, commands };
}

test.describe("cluster bootstrap host", () => {
  test.afterEach(() => {
    for (const dir of made.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
  });

  test("an explicit kind cluster leaves Docker Desktop untouched", () => {
    const result = bootstrap(["--kind"]);
    expect(result.status, result.stderr).toBe(0);
    expect(result.commands.some((args) => args[0] === "kind" && args[1] === "create")).toBe(true);
    expect(result.commands).toContainEqual(["kubectl", "-n", "istio-system", "set", "env", "daemonset/ztunnel", "ENABLE_ORIG_SRC=false"]);
    const reused = bootstrap(["--kind"], false, true);
    expect(reused.status, reused.stderr).toBe(0);
    expect(reused.commands).toContainEqual(["kubectl", "config", "use-context", "kind-qip"]);
    expect(reused.commands.some((args) => args[0] === "kind" && args[1] === "create")).toBe(false);
  });

  test("a failed CNI rollout stops the cluster bootstrap", () => {
    const result = bootstrap([], true);
    expect(result.status).toBe(19);
    expect(result.commands.some((args) => args[0] === "helm")).toBe(false);
  });

});
