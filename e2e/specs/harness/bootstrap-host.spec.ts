import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, type ChildProcess } from "node:child_process";
import { test, expect } from "@playwright/test";
import { bashExecutable, repoRoot } from "../../env/host.js";
import { stopGroup } from "../../support/process-group.js";

const made: string[] = [];
const running = new Map<ChildProcess, Promise<number | null>>();

async function bootstrap(args: string[], failCni = false, existing = false) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-bootstrap-"));
  made.push(dir);
  const log = path.join(dir, "commands");
  const stubs = path.join(dir, "tools.sh");
  fs.writeFileSync(log, "");
  // Source the stubs in Git Bash to avoid a Node startup for every command.
  fs.writeFileSync(stubs, String.raw`
fake_tool() {
  printf '%s\0' "$#" "$@" >> "$HOST_BOOTSTRAP_LOG"
  local tool="$1"
  shift
  if [[ -n "$HOST_FAIL_CNI" && "$tool" == kubectl ]]; then
    case " $* " in
      *" daemonset/istio-cni-node "*|*" daemonset/ztunnel "*) return 19 ;;
    esac
  fi
  case "$tool:$*" in
    "kind:get clusters")
      if [[ -n "$HOST_EXISTING_KIND" ]]; then echo qip; fi
      ;;
    "kubectl:config current-context") echo docker-desktop ;;
    kubectl:*" get deployment metrics-server "*) echo 'v0.9.0 --kubelet-insecure-tls' ;;
  esac
}
kubectl() { fake_tool kubectl "$@"; }
kind() { fake_tool kind "$@"; }
helm() { fake_tool helm "$@"; }
istioctl() { fake_tool istioctl "$@"; }
`);
  const bashPath = (file: string) => file.split(path.sep).join("/");
  const script = bashPath(path.join(repoRoot(), "e2e/k8s/bootstrap-cluster.sh"));
  const child = spawn(bashExecutable(), [
    "-ec", 'source "$1"; script="$2"; shift 2; source "$script" "$@"', "bootstrap-host", bashPath(stubs), script, ...args,
  ], {
    env: {
      ...process.env,
      HOST_BOOTSTRAP_LOG: bashPath(log),
      HOST_FAIL_CNI: failCni ? "1" : "",
      HOST_EXISTING_KIND: existing ? "1" : "",
      E2E_ZTUNNEL_ORIG_SRC: "false",
    },
    detached: true,
    stdio: ["ignore", "ignore", "pipe"],
    windowsHide: true,
  });
  let stderr = "";
  child.stderr.setEncoding("utf8").on("data", (chunk: string) => { stderr += chunk; });
  const closed = new Promise<number | null>((resolve, reject) => {
    child.once("close", resolve);
    child.once("error", reject);
  });
  running.set(child, closed);
  try {
    const status = await closed;
    const fields = fs.readFileSync(log, "utf8").split("\0");
    const commands: string[][] = [];
    for (let i = 0; i < fields.length - 1;) {
      const count = Number(fields[i++]);
      commands.push(fields.slice(i, i + count));
      i += count;
    }
    return { status, stderr, commands };
  } finally {
    running.delete(child);
  }
}

test.describe("cluster bootstrap host", () => {
  test.afterEach(async () => {
    try {
      for (const [child, closed] of running) {
        if (child.pid !== undefined) await stopGroup(child.pid);
        await closed;
      }
    } finally {
      for (const dir of made.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  test("an explicit kind cluster leaves Docker Desktop untouched", async () => {
    const result = await bootstrap(["--kind"]);
    expect(result.status, result.stderr).toBe(0);
    expect(result.commands.some((args) => args[0] === "kind" && args[1] === "create")).toBe(true);
    expect(result.commands).toContainEqual(["kubectl", "-n", "istio-system", "set", "env", "daemonset/ztunnel", "ENABLE_ORIG_SRC=false"]);
    const reused = await bootstrap(["--kind"], false, true);
    expect(reused.status, reused.stderr).toBe(0);
    expect(reused.commands).toContainEqual(["kubectl", "config", "use-context", "kind-qip"]);
    expect(reused.commands.some((args) => args[0] === "kind" && args[1] === "create")).toBe(false);
  });

  test("a failed CNI rollout stops the cluster bootstrap", async () => {
    const result = await bootstrap([], true);
    expect(result.status, result.stderr).toBe(19);
    expect(result.commands.some((args) => args[0] === "helm")).toBe(false);
  });

});
