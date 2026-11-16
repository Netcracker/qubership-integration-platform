import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { setTimeout } from "node:timers/promises";

/** @param {string[]} args @param {string} [input] */
function kubectl(args, input = "") {
  const result = spawnSync("kubectl", args, { input, encoding: "utf8", windowsHide: true });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr || `kubectl ${args.join(" ")} exited ${result.status}`);
  return result.stdout;
}

/** @param {string} action @param {string} script */
export function preparationJob(action, script) {
  return {
    apiVersion: "batch/v1", kind: "Job",
    metadata: { generateName: "cip-e2e-ambient-netns-", namespace: "kube-system" },
    spec: {
      backoffLimit: 0,
      template: {
        metadata: { labels: { app: "cip-e2e-ambient-netns" }, annotations: { "sidecar.istio.io/inject": "false" } },
        spec: {
          nodeName: "docker-desktop", hostPID: true, hostNetwork: true,
          automountServiceAccountToken: false, restartPolicy: "Never",
          containers: [{
            name: "prepare-netns", image: "alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f",
            securityContext: { privileged: true, readOnlyRootFilesystem: true },
            command: ["/bin/sh", "-eu", "-c", `
set -- $(pidof dockerd)
if [ "$#" != 1 ]; then
    echo 'Expected one Docker daemon on the Docker Desktop kubeadm node' >&2
    exit 1
fi
daemon_pid="$1"
printf '%s\\n' "$NODE_SCRIPT" | nsenter -t "$daemon_pid" -m -r -w -- /bin/sh -s -- "$NODE_ACTION"
`],
            env: [{ name: "NODE_ACTION", value: action }, { name: "NODE_SCRIPT", value: script }],
          }],
        },
      },
    },
  };
}

/** @param {string} action @param {(args: string[], input?: string) => string} run */
export async function prepareDockerDesktopAmbient(action = "--check", run = kubectl) {
  if (action !== "--check" && action !== "--prepare") throw new Error("Use --check or --prepare.");
  if (run(["config", "current-context"]).trim() !== "docker-desktop") {
    throw new Error("Select the Docker Desktop kubeadm context before preparing its node.");
  }
  const node = JSON.parse(run(["get", "node", "docker-desktop", "-o", "json"]));
  if (!node.status.nodeInfo.containerRuntimeVersion.startsWith("docker://")) {
    throw new Error("Node preparation targets Docker Desktop kubeadm with the Docker runtime.");
  }
  const script = fs.readFileSync(new URL("./prepare-ambient-netns.sh", import.meta.url), "utf8");
  const job = JSON.parse(run(["create", "-f", "-", "-o", "json"], JSON.stringify(preparationJob(action, script))));
  const name = job.metadata.name;
  try {
    const deadline = Date.now() + 180_000;
    for (;;) {
      const current = JSON.parse(run(["get", "job", name, "-n", "kube-system", "-o", "json"]));
      if (current.status?.succeeded) return;
      if (current.status?.failed) throw new Error("Ambient netns preparation failed; see the node job output.");
      if (Date.now() >= deadline) throw new Error("Ambient netns preparation did not complete within 180 seconds.");
      await setTimeout(1_000);
    }
  } finally {
    try {
      process.stdout.write(run(["logs", `job/${name}`, "-n", "kube-system"]));
    } finally {
      run(["delete", "job", name, "-n", "kube-system", "--wait=false"]);
    }
  }
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  try {
    if (process.argv.length > 3) throw new Error("Use --check or --prepare.");
    await prepareDockerDesktopAmbient(process.argv[2]);
  } catch (error) {
    console.error(String(error));
    process.exitCode = 1;
  }
}
