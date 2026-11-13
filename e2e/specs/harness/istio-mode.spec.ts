import { test, expect } from "@playwright/test";
import { checkPrerequisites, clusterChoice, ensureNamespace, helmArguments, resolveIstioMode, waitForWaypoint } from "../../env/provision/k8s.js";

const sidecarCluster: Record<string, string> = {
  "config current-context": "docker-desktop",
  "get --raw /readyz": "ok",
  "get crd httproutes.gateway.networking.k8s.io": "",
  "get deploy istiod -n istio-system -o json": JSON.stringify({ status: { availableReplicas: 1 } }),
  "get mutatingwebhookconfiguration istio-sidecar-injector": "",
  "get crd integrations.camel.apache.org": "",
  "get deploy -A -l app=camel-k,camel.apache.org/component=operator -o json": JSON.stringify({ items: [{ status: { availableReplicas: 1 } }] }),
  "get --raw /apis/metrics.k8s.io/v1beta1/nodes": "{}",
};

test.describe("Istio modes", () => {
  test("auto selects sidecars for Docker Desktop kubeadm", async () => {
    for (const [context, runtime, expected] of [
      ["docker-desktop", "docker-desktop=docker://28.5.0", "sidecar"],
      ["docker-desktop", "docker-desktop=containerd://2.3.4", "sidecar"],
      ["docker-desktop", "desktop-control-plane=containerd://2.3.4", "ambient"],
      ["kind-qip", "qip-control-plane=containerd://2.3.4", "ambient"],
    ]) {
      const mode = await resolveIstioMode(async (args) => args[0] === "config" ? context : runtime, "auto");
      expect(mode).toBe(expected);
    }
  });

  test("an explicit mode overrides runtime detection", async () => {
    const unused = async (): Promise<string> => { throw new Error("the cluster should not be read"); };
    expect(await resolveIstioMode(unused, "sidecar")).toBe("sidecar");
    expect(await resolveIstioMode(unused, "ambient")).toBe("ambient");
    await expect(resolveIstioMode(unused, "unknown")).rejects.toThrow('CIP_ISTIO_MODE must be "auto", "ambient", or "sidecar"');
  });

  test("sidecar mode accepts a cluster without Istio CNI", async () => {
    const calls: string[] = [];
    await checkPrerequisites(async (args) => {
      const key = args.join(" ");
      calls.push(key);
      if (!(key in sidecarCluster)) throw new Error(`not installed: ${key}`);
      return sidecarCluster[key];
    }, "sidecar");
    expect(calls).toContain("get mutatingwebhookconfiguration istio-sidecar-injector");
    expect(calls.some((call) => call.includes("get ds"))).toBe(false);
  });

  test("each mode requires its own mesh components", async () => {
    const missing = async (args: string[]) => {
      const key = args.join(" ");
      if (!(key in sidecarCluster) || key.includes("istio-sidecar-injector")) throw new Error("not installed");
      return sidecarCluster[key];
    };
    await expect(checkPrerequisites(missing, "sidecar")).rejects.toThrow("Istio sidecar injection");
    await expect(checkPrerequisites(missing, "ambient")).rejects.toThrow("Istio ambient");
  });

  test("sidecar namespaces remove ambient enrollment", async () => {
    const calls: string[][] = [];
    await ensureNamespace("sidecar", async (args) => { calls.push(args); return ""; });
    expect(calls).toContainEqual([
      "label", "--overwrite", "namespace", "cip-e2e", "istio.io/dataplane-mode-", "istio.io/use-waypoint-", "istio-injection=enabled",
    ]);
    calls.length = 0;
    await ensureNamespace("ambient", async (args) => { calls.push(args); return ""; });
    expect(calls).toContainEqual([
      "label", "--overwrite", "namespace", "cip-e2e", "istio-injection-", "istio.io/dataplane-mode=ambient", "istio.io/use-waypoint=waypoint",
    ]);
  });

  test("only ambient installs and waits for a waypoint", async () => {
    const options = {
      root: "/repo", choice: clusterChoice("docker-desktop"), devServerHost: "host.docker.internal",
      tags: { "runtime-catalog": "latest", engine: "latest", "sessions-management": "latest", "testing-service": "latest", "micro-engine": "latest" },
    };
    expect(helmArguments({ ...options, istioMode: "sidecar" })).toContain("gateways.waypoint.enabled=false");
    expect(helmArguments({ ...options, istioMode: "ambient" })).not.toContain("gateways.waypoint.enabled=false");
    const calls: string[][] = [];
    const run = async (command: string, args: string[]) => { calls.push([command, ...args]); };
    await waitForWaypoint("sidecar", run);
    expect(calls).toEqual([]);
    await waitForWaypoint("ambient", run);
    expect(calls[0]).toEqual(["kubectl", "wait", "--for=condition=Programmed", "gateway/waypoint", "-n", "cip-e2e", "--timeout=180s"]);
  });
});
