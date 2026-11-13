import { test, expect } from "@playwright/test";
import { preparationJob, prepareDockerDesktopAmbient } from "../../k8s/prepare-docker-desktop-ambient.mjs";

test.describe("ambient node preparation", () => {
  test("the helper targets only the Docker Desktop node", () => {
    const job = preparationJob("--prepare", "node script");
    expect(job.metadata.namespace).toBe("kube-system");
    expect(job.spec.template.spec.nodeName).toBe("docker-desktop");
    expect(job.spec.template.spec.hostPID).toBe(true);
    expect(job.spec.template.spec.hostNetwork).toBe(true);
    expect(job.spec.template.spec.automountServiceAccountToken).toBe(false);
    expect(job.spec.backoffLimit).toBe(0);
    const container = job.spec.template.spec.containers[0];
    expect(container.securityContext.privileged).toBe(true);
    expect(container.env).toContainEqual({ name: "NODE_ACTION", value: "--prepare" });
    expect(container.command.join(" ")).toContain('nsenter -t "$daemon_pid" -m -r -w');
  });

  test("another context is rejected before creating a job", async () => {
    const calls: string[][] = [];
    await expect(prepareDockerDesktopAmbient("--prepare", (args) => {
      calls.push(args);
      return "kind-qip";
    })).rejects.toThrow("Select the Docker Desktop kubeadm context");
    expect(calls).toEqual([["config", "current-context"]]);
  });

  test("a completed preparation removes its privileged job", async () => {
    const calls: string[][] = [];
    let manifest: ReturnType<typeof preparationJob> | undefined;
    await prepareDockerDesktopAmbient("--check", (args, input) => {
      calls.push(args);
      if (args[0] === "config") return "docker-desktop";
      if (args[1] === "node") return JSON.stringify({ status: { nodeInfo: { containerRuntimeVersion: "docker://28.5.0" } } });
      if (args[0] === "create") {
        manifest = JSON.parse(input!);
        return JSON.stringify({ metadata: { name: "test-preparation" } });
      }
      if (args[1] === "job" && args[0] === "get") return JSON.stringify({ status: { succeeded: 1 } });
      return "";
    });
    expect(manifest!.spec.template.spec.containers[0].env).toContainEqual({ name: "NODE_ACTION", value: "--check" });
    expect(calls.at(-1)).toEqual(["delete", "job", "test-preparation", "-n", "kube-system", "--wait=false"]);
  });

  test("a failed preparation cleans up its privileged job", async () => {
    const calls: string[][] = [];
    await expect(prepareDockerDesktopAmbient("--prepare", (args) => {
      calls.push(args);
      if (args[0] === "config") return "docker-desktop";
      if (args[1] === "node") return JSON.stringify({ status: { nodeInfo: { containerRuntimeVersion: "docker://28.5.0" } } });
      if (args[0] === "create") return JSON.stringify({ metadata: { name: "failed-preparation" } });
      if (args[1] === "job" && args[0] === "get") return JSON.stringify({ status: { failed: 1 } });
      return "";
    })).rejects.toThrow("Ambient netns preparation failed");
    expect(calls).toContainEqual(["logs", "job/failed-preparation", "-n", "kube-system"]);
    expect(calls.at(-1)).toEqual(["delete", "job", "failed-preparation", "-n", "kube-system", "--wait=false"]);
  });
});
