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
  const log = path.join(dir, "commands");
  const stubs = path.join(dir, "tools.sh");
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
  const result = spawnSync(bashExecutable(), [
    "-ec", 'source "$1"; script="$2"; shift 2; source "$script" "$@"', "bootstrap-host", bashPath(stubs), script, ...args,
  ], {
    env: {
      ...process.env,
      HOST_BOOTSTRAP_LOG: bashPath(log),
      HOST_FAIL_CNI: failCni ? "1" : "",
      HOST_EXISTING_KIND: existing ? "1" : "",
      E2E_ZTUNNEL_ORIG_SRC: "false",
    },
    encoding: "utf8",
    timeout: 10_000,
  });
  if (result.error) throw result.error;
  const fields = fs.readFileSync(log, "utf8").split("\0");
  const commands: string[][] = [];
  for (let i = 0; i < fields.length - 1;) {
    const count = Number(fields[i++]);
    commands.push(fields.slice(i, i + count));
    i += count;
  }
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
    expect(result.status, result.stderr).toBe(19);
    expect(result.commands.some((args) => args[0] === "helm")).toBe(false);
  });

});
