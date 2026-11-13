import { test, expect } from "../../support/fixtures.js";
import { namespaceLabels, pods } from "../../support/kube.js";
import { resolveIstioMode } from "../../env/provision/k8s.js";
import { deploymentOf } from "../../env/k8s.js";
import { ROLES } from "../../env/containers.js";

test.describe("Istio dataplane", { tag: ["@infra"] }, () => {
  test("platform pods use the selected mesh", async () => {
    const mode = await resolveIstioMode();
    const labels = await namespaceLabels();
    if (mode === "sidecar") {
      expect(labels["istio-injection"]).toBe("enabled");
      expect(labels["istio.io/dataplane-mode"]).toBeUndefined();
      expect(labels["istio.io/use-waypoint"]).toBeUndefined();
    } else {
      expect(labels["istio-injection"]).toBeUndefined();
      expect(labels["istio.io/dataplane-mode"]).toBe("ambient");
      expect(labels["istio.io/use-waypoint"]).toBe("waypoint");
    }
    const running = (await pods()).filter((pod) => !pod.metadata.deletionTimestamp && pod.status?.phase === "Running");
    for (const role of ROLES) {
      const deployed = running.filter((pod) => pod.metadata.name.startsWith(`${deploymentOf(role)}-`));
      expect(deployed.length, role).toBeGreaterThan(0);
      for (const pod of deployed) {
        const containers = [...pod.spec.containers, ...(pod.spec.initContainers ?? []).filter((container) => container.restartPolicy === "Always")];
        expect(containers.some((container) => container.name === "istio-proxy"), pod.metadata.name).toBe(mode === "sidecar");
      }
    }
  });
});
