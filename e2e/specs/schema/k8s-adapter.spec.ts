/**
 * The Kubernetes adapter's logic that needs no cluster: the cluster kind read from the context, the
 * addresses per role and per engine kind, the micro state file a project names, the prerequisite
 * checks against a stubbed `kubectl`, the Helm arguments, the micro-engine build profile, the
 * `kubectl top` parser, the broker overlay methods, the micro engine's address and restart, and the
 * `env` patches a restart sends.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { K8sEnv, recordedMicroDomain, type Kubectl } from "../../env/k8s.js";
import { withoutColors } from "../../support/colors.js";
import { readingOfTop } from "../../env/k8s-observer.js";
import {
  restartStamp,
  restorePatch,
  settingsPatch,
  type EnvVar,
} from "../../env/k8s-env-patch.js";
import {
  checkPrerequisites,
  clusterChoice,
  helmArguments,
  imageTag,
  suiteImages,
} from "../../env/provision/k8s.js";
import { repoRoot } from "../../env/host.js";
import { OVERLAY_READY_TIMEOUT } from "../../env/overlays.js";
import { targetSetup } from "../../env/target-setup.js";
import type { Overlay, ServiceRole } from "../../env/index.js";
import { URL_OVERRIDES, withEnv } from "../../support/with-env.js";
import { projectCorpus } from "../../support/corpus.js";
import { BROKER_PROJECTS, K8S_PROJECTS, MICRO_BROKER_PROJECTS } from "./projects.js";
import type { Catalog } from "../../support/catalog.js";
import { deleteDomain, deployToDomain, dispatchToDomain, tokenedDomain } from "../../support/deployable.js";

const ON_K8S = {
  CIP_TARGET: "k8s",
  ...Object.fromEntries(URL_OVERRIDES.map((name) => [name, undefined])),
};

test("the cluster kind is read from the kube-context name", { tag: ["@infra", "@tier1"] }, () => {
  expect(clusterChoice("docker-desktop")).toMatchObject({
    kind: "docker-desktop",
    imageLoading: "host daemon",
    resolver: "10.96.0.10",
    devServerHost: "host.docker.internal",
    hostPorts: "LoadBalancer Services",
  });
  expect(clusterChoice("kind-qip")).toMatchObject({
    kind: "kind",
    name: "qip",
    imageLoading: "kind load docker-image",
    resolver: "10.96.0.10",
    // Read from the `kind` Docker network when provisioning.
    devServerHost: null,
    hostPorts: "NodePorts",
  });
  expect(clusterChoice("k3d-qip")).toMatchObject({
    kind: "k3d",
    name: "qip",
    imageLoading: "k3d image import",
    resolver: "10.43.0.10",
    devServerHost: "host.k3d.internal",
    hostPorts: "NodePorts",
  });
  for (const context of ["minikube", "kind-", "gke_project_zone_cluster", ""]) {
    expect(() => clusterChoice(context)).toThrow(/docker-desktop, kind-<name>, or k3d-<name>/);
  }
});

test("each role answers on its fixed host port, and a chain on the engine kind the project runs", { tag: ["@infra", "@tier1"] }, () => {
  withEnv(ON_K8S, () => {
    const classic = new K8sEnv({ engineKind: "classic" });
    const ports: Record<ServiceRole, number> = {
      "runtime-catalog": 30091,
      engine: 30092,
      "sessions-management": 30093,
      "testing-service": 30095,
    };
    for (const [role, port] of Object.entries(ports) as [ServiceRole, number][]) {
      expect(classic.url(role)).toBe(`http://localhost:${port}`);
    }
    expect(classic.apiUrl("runtime-catalog", "/v1/folders")).toMatch(/^http:\/\/localhost:30080\/api\//);
    expect(classic.uiUrl("chains")).toBe("http://localhost:30080/chains");
    expect(classic.chainUrl("e2e/echo")).toBe("http://localhost:30092/routes/e2e/echo");
    expect(classic.chainUrl("/e2e/echo")).toBe("http://localhost:30092/routes/e2e/echo");
    expect(classic.engineUrl()).toBe("http://localhost:30092");
    expect(classic.engineMetricsUrl()).toBe("http://localhost:30092/actuator/prometheus");

    // A micro domain has no host port; the proxy reaches its Service by the name the catalog gives it.
    const micro = new K8sEnv({ engineKind: "micro", microDomain: () => "e2e-run1-micro" });
    const microEngine = "http://localhost:30080/e2e/svc/cip-engine-e2e-run1-micro-v1";
    expect(micro.chainUrl("micro/e2e/echo")).toBe(`${microEngine}/routes/micro/e2e/echo`);
    expect(micro.engineUrl()).toBe(microEngine);
    expect(micro.engineMetricsUrl()).toBe(`${microEngine}/metrics`);
    expect(micro.url("engine")).toBe("http://localhost:30092");
    const unseeded = new K8sEnv({
      engineKind: "micro",
      microDomain: () => recordedMicroDomain(path.join(os.tmpdir(), "e2e-no-micro-corpus.json")),
    });
    expect(() => unseeded.chainUrl("x")).toThrow(/seed-micro/);
    expect(() => unseeded.engineUrl()).toThrow(/seed-micro/);
  });
});

test("a micro project reads its domain and its corpus copy from the state file it names", { tag: ["@infra", "@tier1"] }, () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-micro-state-"));
  try {
    const state = path.join(dir, "own-micro-corpus.json");
    const chains = [{ fixture: "http-echo", id: "c1" }, { fixture: "choice", id: "c2" }];
    fs.writeFileSync(state, JSON.stringify({ run: "run2", domain: "e2e-run2-own", chains, services: [] }));
    const ownEngine = "http://localhost:30080/e2e/svc/qip-engine-e2e-run2-own-v1";

    withEnv(ON_K8S, () => {
      expect(targetSetup("k8s").createEnv("micro", state).engineUrl()).toBe(ownEngine);
    });

    const micro = projectCorpus({ engineKind: "micro", microDomainState: state });
    expect(micro.chains).toEqual(chains);
    expect(micro.domain).toBe("e2e-run2-own");
    // A chain with no HTTP route; a spec that lists the corpus meets it on both engines.
    expect(micro.fixtures).toContain("scheduler-basic");

    const missing = path.join(dir, "absent.json");
    expect(() => projectCorpus({ engineKind: "micro", microDomainState: missing })).toThrow(missing);
    expect(() => targetSetup("k8s").createEnv("micro", missing).engineUrl()).toThrow(missing);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a tokened domain is recorded where its project's env reads it, until it is deleted", { tag: ["@infra", "@tier1"] }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-tokened-domain-"));
  try {
    const record = path.join(dir, "redeploy.json");
    const target = tokenedDomain("run3", "placeholder-redeploy", record);
    expect(target.domain).toBe("e2e-run3-placeholder-redeploy");
    const calls: string[] = [];
    const catalog = {
      deployChains: async (ids: string[], domains: string[]) => {
        calls.push(`deployChains ${ids.join(",")} ${domains.join(",")}`);
        return ids.map((chainId) => ({ chainId, status: "CREATED" }));
      },
      deleteCustomResource: async (name: string) => {
        calls.push(`deleteCustomResource ${name}`);
      },
    } as unknown as Catalog;

    // A chain with no route, so the route poll has nothing to call.
    await deployToDomain(catalog, target, [{ id: "c1", name: "e2e-run3-x", contextPath: "" }]);
    withEnv(ON_K8S, () => {
      const engine = "http://localhost:30080/e2e/svc/qip-engine-e2e-run3-placeholder-redeploy-v1";
      expect(target.env.engineUrl()).toBe(engine);
      expect(targetSetup("k8s").createEnv("micro", record).engineUrl()).toBe(engine);
    });

    await deleteDomain(catalog, target);
    expect(fs.existsSync(record)).toBe(false);
    expect(calls).toEqual([
      "deployChains c1 e2e-run3-placeholder-redeploy",
      "deleteCustomResource e2e-run3-placeholder-redeploy",
    ]);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a dispatch to a tokened domain returns once the catalog accepts every chain, without waiting for a route", { tag: ["@infra", "@tier1"] }, async () => {
  const target = tokenedDomain("run4", "broken-chain");
  const accepting = {
    deployChains: async (ids: string[]) => ids.map((chainId) => ({ chainId, status: "CREATED" })),
  } as unknown as Catalog;
  // Nothing answers on this route, so a route poll would run out its timeout.
  const routed = [{ id: "c1", name: "e2e-run4-x", contextPath: "e2e-run4-x" }];
  await dispatchToDomain(accepting, target, routed);

  const dropping = { deployChains: async () => [] } as unknown as Catalog;
  await expect(dispatchToDomain(dropping, target, routed)).rejects.toThrow("every chain needs one CREATED row");
});

/** A `kubectl` that answers the listed argument strings and fails every other call. */
function stubKubectl(answers: Record<string, string>): Kubectl {
  return async (args) => {
    const key = args.join(" ");
    if (key in answers) return answers[key];
    throw new Error(`kubectl ${key}: not found`);
  };
}

const ISTIOD = JSON.stringify({
  metadata: { name: "istiod" },
  status: { availableReplicas: 1 },
});

const OPERATORS = JSON.stringify({
  items: [
    {
      metadata: { name: "camel-k-operator" },
      status: { availableReplicas: 1 },
      spec: { template: { spec: { containers: [] } } },
    },
  ],
});

/** Every answer a cluster with all the prerequisites gives. */
const COMPLETE: Record<string, string> = {
  "config current-context": "docker-desktop\n",
  "get --raw /readyz": "ok",
  "get crd httproutes.gateway.networking.k8s.io": "",
  "get deploy istiod -n istio-system -o json": ISTIOD,
  "get crd integrations.camel.apache.org": "",
  "get deploy -A -l app=camel-k,camel.apache.org/component=operator -o json": OPERATORS,
  "get --raw /apis/metrics.k8s.io/v1beta1/nodes": "{}",
};

function without(...keys: string[]): Record<string, string> {
  return Object.fromEntries(Object.entries(COMPLETE).filter(([key]) => !keys.includes(key)));
}

test("a missing prerequisite fails the run, naming it and the README section that installs it", { tag: ["@infra", "@tier1"] }, async () => {
  await expect(checkPrerequisites(stubKubectl(COMPLETE))).resolves.toBeUndefined();

  const cases: Array<{ answers: Record<string, string>; message: RegExp }> = [
    {
      answers: without("config current-context"),
      message: /a Kubernetes cluster: kubectl has no current context\. .*README\.md#cluster/,
    },
    {
      answers: without("get --raw /readyz"),
      message: /a Kubernetes cluster: the current kube-context docker-desktop does not answer\. .*#cluster/,
    },
    {
      answers: without("get crd httproutes.gateway.networking.k8s.io"),
      message: /Gateway API CRDs: the HTTPRoute CRD is not installed\. .*#gateway-api/,
    },
    {
      answers: without("get deploy istiod -n istio-system -o json"),
      message: /Istio: no istiod Deployment in namespace istio-system\. .*#istio/,
    },
    {
      answers: {
        ...COMPLETE,
        "get deploy istiod -n istio-system -o json": ISTIOD.replace('"availableReplicas":1', '"availableReplicas":0'),
      },
      message: /Istio: istiod has no available replica\. .*#istio/,
    },
    {
      answers: without("get crd integrations.camel.apache.org"),
      message: /camel-k operator: the Integration CRD is not installed\. .*#camel-k/,
    },
    {
      answers: {
        ...COMPLETE,
        "get deploy -A -l app=camel-k,camel.apache.org/component=operator -o json": '{"items":[]}',
      },
      message: /camel-k operator: no camel-k operator Deployment has an available replica.*#camel-k/,
    },
    {
      answers: without("get --raw /apis/metrics.k8s.io/v1beta1/nodes"),
      message: /metrics-server: the metrics API does not answer.*#metrics-server/,
    },
  ];
  for (const each of cases) {
    await expect(checkPrerequisites(stubKubectl(each.answers))).rejects.toThrow(each.message);
  }

  // In order: with both the Gateway API and metrics-server missing, the Gateway API is named.
  await expect(
    checkPrerequisites(
      stubKubectl(
        without(
          "get crd httproutes.gateway.networking.k8s.io",
          "get --raw /apis/metrics.k8s.io/v1beta1/nodes",
        ),
      ),
    ),
  ).rejects.toThrow(/#gateway-api/);
});

test("the Helm arguments carry the tags, the catalog's micro-image annotation, the cluster's values, the node ports, and LoadBalancer ports on Docker Desktop only", { tag: ["@infra", "@tier1"] }, () => {
  const tags = {
    "runtime-catalog": "e2e-aaaaaaaaaaaa",
    engine: "e2e-bbbbbbbbbbbb",
    "sessions-management": "e2e-cccccccccccc",
    "testing-service": "e2e-dddddddddddd",
    "micro-engine": "e2e-eeeeeeeeeeee",
  };
  const desktop = helmArguments({
    root: "/repo",
    choice: clusterChoice("docker-desktop"),
    devServerHost: "host.docker.internal",
    tags,
  });
  expect(desktop.slice(0, 7)).toEqual([
    "upgrade", "--install", "qip", "/repo/infrastructure/helm/qip-dev", "-n", "qip-e2e", "-f",
  ]);
  expect(desktop).toContain("/repo/e2e/k8s/values.e2e.yaml");
  expect(desktop).toContain("qip-engine.image.tag=e2e-bbbbbbbbbbbb");
  expect(desktop).toContain("qip-testing-service.image.tag=e2e-dddddddddddd");
  expect(desktop).toContain(
    "global.qip.deploy.micro.image=ghcr.io/netcracker/qubership-integration-micro-engine:e2e-eeeeeeeeeeee",
  );
  expect(desktop).toContain("qip-runtime-catalog.podAnnotations.e2e-micro-engine-tag=e2e-eeeeeeeeeeee");
  expect(desktop).toContain("global.qip.ui.resolver=10.96.0.10");
  expect(desktop).toContain("global.qip.ui.devServer.host=host.docker.internal");
  expect(desktop).toContain("qip-runtime-catalog.loadBalancerPort=30091");
  expect(desktop).toContain("global.qip.ui.loadBalancerPort=30080");
  // The node ports are the host ports the suite's URLs name, on every cluster kind.
  expect(desktop).toContain("qip-sessions-management.nodePort=30093");

  const k3d = helmArguments({
    root: "/repo",
    choice: clusterChoice("k3d-qip"),
    devServerHost: "host.k3d.internal",
    tags,
  });
  expect(k3d).toContain("global.qip.ui.resolver=10.43.0.10");
  expect(k3d.filter((each) => each.includes("loadBalancerPort"))).toEqual([]);
  expect(k3d.filter((each) => /^qip-.*nodePort=|ui\.port=/.test(each))).toEqual([
    "qip-runtime-catalog.nodePort=30091",
    "qip-engine.nodePort=30092",
    "qip-sessions-management.nodePort=30093",
    "qip-testing-service.nodePort=30095",
    "global.qip.ui.port=30080",
  ]);
});

test("the Helm arguments switch the brokers with E2E_BROKERS and keep everything else, the trusted CA included", { tag: ["@infra", "@tier1"] }, () => {
  const tags = {
    "runtime-catalog": "e2e-aaaaaaaaaaaa",
    engine: "e2e-bbbbbbbbbbbb",
    "sessions-management": "e2e-cccccccccccc",
    "testing-service": "e2e-dddddddddddd",
    "micro-engine": "e2e-eeeeeeeeeeee",
  };
  const argumentsWith = (brokers: string | undefined, context: string) =>
    withEnv({ E2E_BROKERS: brokers }, () =>
      helmArguments({ root: "/repo", choice: clusterChoice(context), devServerHost: "host", tags }),
    );
  const on = argumentsWith(undefined, "kind-qip");
  expect(on.filter((each) => /^(kafka|rabbitmq|pubsub|sftp)\./.test(each))).toEqual([
    "kafka.enabled=true",
    "kafka.nodePort=30992",
    "pubsub.enabled=true",
    "pubsub.nodePort=30085",
    "rabbitmq.enabled=true",
    "rabbitmq.nodePort=30672",
    "sftp.enabled=true",
    "sftp.nodePort=30022",
    "kafka.tls.serverCert=/repo/infrastructure/kafka-ssl/server/kafka.crt",
    "kafka.tls.serverKey=/repo/infrastructure/kafka-ssl/server/kafka.key",
  ]);
  // Helm reads `\.` as a dot inside the key, so the Secret holds `ca.crt`.
  expect(on).toContain("qip-engine.trustedCerts.ca\\.crt=/repo/infrastructure/kafka-ssl/client/ca.crt");
  expect(on).toContain(
    "qip-runtime-catalog.microDomainResources={secret:qip-engine-certs@/app/cert/defaultsslcertificate}",
  );
  expect(on).toContain("qip-runtime-catalog.microDomainEmptyDirs={truststore:/app/truststore}");
  // Each value file follows its flag: --set-file reads the path, --set would install the path itself.
  for (const file of on.filter((each) => each.includes("/repo/infrastructure/kafka-ssl/"))) {
    expect(on[on.indexOf(file) - 1], file).toBe("--set-file");
  }

  const desktop = argumentsWith(undefined, "docker-desktop");
  expect(desktop).toContain("rabbitmq.loadBalancerPort=30672");
  expect(desktop.filter((each) => /^(kafka|rabbitmq|pubsub|sftp)\.nodePort/.test(each))).toEqual([]);

  // Off, only the switches differ: the engine and the catalog keep their pod templates.
  const off = argumentsWith("0", "kind-qip");
  expect(off.filter((each) => each.endsWith(".enabled=false"))).toEqual([
    "kafka.enabled=false",
    "pubsub.enabled=false",
    "rabbitmq.enabled=false",
    "sftp.enabled=false",
  ]);
  expect(off.map((each) => each.replace(/\.enabled=false$/, ".enabled=true"))).toEqual(on);
});

test("the micro engine is built with the local-stack Quarkus profiles, and the tag changes with them", { tag: ["@infra", "@tier1"] }, () => {
  const micro = suiteImages().find((each) => each.name === "micro-engine");
  expect(micro?.mavenArgs).toEqual(["-Dquarkus.profile=development,no-m2m"]);
  // A tag over the sources alone would reuse an image built with the default `prod` profile.
  expect(imageTag(repoRoot(), micro!.sources, micro!.mavenArgs)).not.toBe(
    imageTag(repoRoot(), micro!.sources),
  );
});

test("an image tag follows the contents of its sources and nothing outside them", { tag: ["@infra", "@tier1"] }, () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-image-tag-"));
  try {
    fs.mkdirSync(path.join(root, "module/src"), { recursive: true });
    fs.writeFileSync(path.join(root, "module/src/Main.java"), "class Main {}");
    fs.writeFileSync(path.join(root, "module/pom.xml"), "<project/>");
    fs.writeFileSync(path.join(root, "module/README.md"), "notes");
    const sources = ["module/src", "module/pom.xml"];
    const first = imageTag(root, sources);
    expect(first).toMatch(/^e2e-[0-9a-f]{12}$/);

    fs.writeFileSync(path.join(root, "module/README.md"), "other notes");
    expect(imageTag(root, sources), "a file outside the sources changed the tag").toBe(first);

    fs.writeFileSync(path.join(root, "module/src/Main.java"), "class Main { int x; }");
    const edited = imageTag(root, sources);
    expect(edited, "a changed source file left the tag as it was").not.toBe(first);

    fs.writeFileSync(path.join(root, "module/src/Other.java"), "class Other {}");
    expect(imageTag(root, sources), "a new source file left the tag as it was").not.toBe(edited);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("a kubectl top reading counts the service container and never the sidecar", { tag: ["@infra", "@tier1"] }, () => {
  const reading = readingOfTop([
    "qip-engine-v1-7d9c9b7f5-abcde   qip-engine    250m   812Mi",
    "qip-engine-v1-7d9c9b7f5-abcde   istio-proxy   40m    60Mi",
    "qip-runtime-catalog-v1-5f7-xyz   qip-runtime-catalog   1   1Gi",
    "qip-postgres-6b8d-qwert   postgres   5m   40Mi",
    "",
  ]);
  expect(reading).toEqual([
    { role: "engine", memoryBytes: 812 * 1024 ** 2, cpuPercent: 25, samples: 1 },
    { role: "runtime-catalog", memoryBytes: 1024 ** 3, cpuPercent: 100, samples: 1 },
  ]);
});

/** A broker pod as `kubectl get pods -o json` lists it, beside a terminating one the adapter skips. */
function brokerPods(name: string, restartCount: number, ready: boolean): string {
  return JSON.stringify({
    items: [
      {
        metadata: { name: `${name}-old`, deletionTimestamp: "2026-10-13T00:00:00Z" },
        status: { containerStatuses: [{ name, restartCount: 0, ready: false }] },
      },
      {
        metadata: { name: `${name}-live` },
        status: { containerStatuses: [{ name, restartCount, ready }] },
      },
    ],
  });
}

/** A `kubectl` that records every call and answers `get pods` from `pods` in order, the last one repeated. */
function recordingKubectl(pods: string[] = []): { calls: string[]; run: Kubectl } {
  const calls: string[] = [];
  let next = 0;
  return {
    calls,
    run: async (args) => {
      calls.push(args.join(" "));
      if (args[0] !== "get") return "";
      return pods[Math.min(next++, pods.length - 1)];
    },
  };
}

/** A host probe that records, per call, how many `kubectl` calls ran before it, and reports ready. */
function recordingProbe(calls: string[]): { probed: string[]; overlayReady: (overlay: Overlay) => () => Promise<null> } {
  const probed: string[] = [];
  return {
    probed,
    overlayReady: (overlay) => async () => {
      probed.push(`${overlay} after ${calls.length} kubectl calls`);
      return null;
    },
  };
}

test("ensureOverlay waits for the broker's rollout, then for its host port", { tag: ["@infra", "@tier1"] }, async () => {
  const { calls, run } = recordingKubectl();
  const { probed, overlayReady } = recordingProbe(calls);
  const env = new K8sEnv({ engineKind: "classic", kubectl: run, overlayReady });
  await env.ensureOverlay("pubsub");
  expect(calls).toEqual([`rollout status deploy/pubsub -n qip-e2e --timeout=${OVERLAY_READY_TIMEOUT / 1000}s`]);
  expect(probed).toEqual(["pubsub after 1 kubectl calls"]);
});

test("restartOverlay kills PID 1 and waits for a higher restartCount, a Ready container, and the host port", { tag: ["@infra", "@tier1"] }, async () => {
  // Right after `kill 1` the old container can still read Ready with the same restartCount.
  const { calls, run } = recordingKubectl([
    brokerPods("kafka", 2, true),
    brokerPods("kafka", 2, true),
    brokerPods("kafka", 3, true),
  ]);
  const { probed, overlayReady } = recordingProbe(calls);
  const env = new K8sEnv({ engineKind: "classic", kubectl: run, overlayReady });
  await env.restartOverlay("kafka");
  expect(calls).toEqual([
    "get pods -n qip-e2e -l app=kafka -o json",
    "exec deploy/kafka -n qip-e2e -c kafka -- sh -c kill 1",
    "get pods -n qip-e2e -l app=kafka -o json",
    "get pods -n qip-e2e -l app=kafka -o json",
  ]);
  expect(probed).toEqual(["kafka after 4 kubectl calls"]);
});

test("restartOverlay keeps polling while the restarted container is not Ready", { tag: ["@infra", "@tier1"] }, async () => {
  const { calls, run } = recordingKubectl([
    brokerPods("rabbitmq", 0, true),
    brokerPods("rabbitmq", 1, false),
    brokerPods("rabbitmq", 1, true),
  ]);
  const env = new K8sEnv({ engineKind: "classic", kubectl: run, overlayReady: () => async () => null });
  await env.restartOverlay("rabbitmq");
  expect(calls.filter((each) => each.startsWith("get pods"))).toHaveLength(3);
});

test("removeOverlay leaves the Helm-owned brokers alone", { tag: ["@infra", "@tier1"] }, async () => {
  const { calls, run } = recordingKubectl();
  const env = new K8sEnv({ engineKind: "classic", kubectl: run, overlayReady: () => async () => null });
  await env.removeOverlay("sftp");
  expect(calls).toEqual([]);
});

/** The pods of a micro domain as `kubectl get pods -o json` lists them, each `[name, deleted, integration Ready]`. */
function microPods(...pods: [string, boolean, boolean][]): string {
  return JSON.stringify({
    items: pods.map(([name, deleted, ready]) => ({
      metadata: { name, ...(deleted ? { deletionTimestamp: "2026-10-14T00:00:00Z" } : {}) },
      status: {
        podIP: `ip-of-${name}`,
        containerStatuses: [
          { name: "istio-proxy", restartCount: 0, ready: true },
          { name: "integration", restartCount: 0, ready },
        ],
      },
    })),
  });
}

const MICRO_SELECTOR = "camel.apache.org/integration=qip-engine-e2e-run1-micro-v1";

test("on a micro env the engine address is the micro domain's live pod", { tag: ["@infra", "@tier1"] }, async () => {
  const { calls, run } = recordingKubectl([microPods(["old", true, false], ["live", false, true])]);
  const env = new K8sEnv({ engineKind: "micro", microDomain: () => "e2e-run1-micro", kubectl: run });
  expect(await env.address("engine")).toBe("ip-of-live");
  expect(calls).toEqual([`get pods -n qip-e2e -l ${MICRO_SELECTOR} -o json`]);
});

test("on a micro env the engine restart deletes the domain's pod and waits for a Ready replacement to answer", { tag: ["@infra", "@tier1"] }, async () => {
  // The deleted pod is listed live at first; the replacement is then not Ready, then Ready while its
  // engine still answers 503.
  const { calls, run } = recordingKubectl([
    microPods(["old", false, true]),
    microPods(["old", false, true]),
    microPods(["old", true, true], ["new", false, false]),
    microPods(["new", false, true]),
  ]);
  const statuses = [503, 200];
  const probed: string[] = [];
  const env = new K8sEnv({
    engineKind: "micro",
    microDomain: () => "e2e-run1-micro",
    kubectl: run,
    httpStatus: async (url) => {
      probed.push(url);
      return statuses.shift() ?? 0;
    },
  });
  await env.restart("engine");
  expect(calls).toEqual([
    `get pods -n qip-e2e -l ${MICRO_SELECTOR} -o json`,
    "delete pod old -n qip-e2e --wait=false",
    `get pods -n qip-e2e -l ${MICRO_SELECTOR} -o json`,
    `get pods -n qip-e2e -l ${MICRO_SELECTOR} -o json`,
    `get pods -n qip-e2e -l ${MICRO_SELECTOR} -o json`,
    `get pods -n qip-e2e -l ${MICRO_SELECTOR} -o json`,
  ]);
  // Probed only once the replacement's engine container is Ready, and until it answers 200.
  expect(probed).toHaveLength(2);
  for (const url of probed) expect(url).toMatch(/\/e2e\/svc\/qip-engine-e2e-run1-micro-v1\/q\/health\/ready$/);
});

test("a micro engine restart fails when no single Ready pod replaces the deleted one", { tag: ["@infra", "@tier1"] }, async () => {
  const restart = async (replacements: string) => {
    const { run } = recordingKubectl([microPods(["old", false, true]), replacements]);
    const env = new K8sEnv({
      engineKind: "micro",
      microDomain: () => "e2e-run1-micro",
      kubectl: run,
      httpStatus: async () => 200,
      microRestartTimeout: 50,
    });
    return String(await env.restart("engine").catch((cause: unknown) => cause));
  };
  expect(await restart(microPods(["old", true, true], ["new", false, false]))).toContain(
    "micro pod old was deleted and no pod replaced it within 50ms (container integration of new is not Ready)",
  );
  expect(await restart(microPods(["new", false, true], ["newer", false, true]))).toContain("(2 pods replace old)");
});

test("the k8s target declares its own projects and the broker projects, and leaves nothing out", { tag: ["@infra", "@tier1"] }, () => {
  const setup = withEnv({ E2E_BROKERS: undefined }, () => targetSetup("k8s"));
  expect(
    setup.projects.map((each) => ({
      name: each.name,
      dependencies: each.dependencies,
      teardown: each.teardown,
      engineKind: each.use?.engineKind,
    })),
  ).toEqual([
    { name: "k8s", dependencies: ["seed"], teardown: undefined, engineKind: undefined },
    { name: "seed-micro", dependencies: undefined, teardown: "suite-teardown", engineKind: "micro" },
    { name: "runtime-micro", dependencies: ["seed-micro", "tooling"], teardown: undefined, engineKind: "micro" },
    { name: "runtime-micro-redeploy", dependencies: ["seed-micro"], teardown: undefined, engineKind: "micro" },
    { name: "global-micro", dependencies: ["global", "seed-micro"], teardown: undefined, engineKind: "micro" },
    { name: "env-micro", dependencies: ["global-micro", "runtime-micro"], teardown: undefined, engineKind: "micro" },
    { name: "brokers-seed", dependencies: ["seed"], teardown: "suite-teardown", engineKind: undefined },
    { name: "brokers", dependencies: ["brokers-seed"], teardown: undefined, engineKind: undefined },
    { name: "brokers-restart", dependencies: ["brokers", "brokers-micro"], teardown: undefined, engineKind: undefined },
    { name: "brokers-micro-seed", dependencies: undefined, teardown: "suite-teardown", engineKind: "micro" },
    { name: "brokers-micro", dependencies: ["brokers-seed", "brokers-micro-seed"], teardown: undefined, engineKind: "micro" },
  ]);
  expect(setup.absent).toEqual([]);
  // Only a tagged case stays classic: runtime-micro runs every file of specs/runtime/.
  const runtimeMicro = setup.projects.find((each) => each.name === "runtime-micro");
  expect(runtimeMicro?.grepInvert).toEqual(/@classic-only/);
  expect(runtimeMicro?.testIgnore).toBeUndefined();
});

test("E2E_BROKERS=0 drops the broker projects on k8s and names them as absent", { tag: ["@infra", "@tier1"] }, () => {
  const setup = withEnv({ E2E_BROKERS: "0" }, () => targetSetup("k8s"));
  expect(setup.projects.map((each) => each.name)).toEqual(K8S_PROJECTS);
  expect(setup.absent).toEqual(
    [...BROKER_PROJECTS, ...MICRO_BROKER_PROJECTS].map((name) => ({ name, reason: "E2E_BROKERS=0" })),
  );
});

/** The engine container's `env` as the chart renders it: a ConfigMap reference and a plain value. */
const ENGINE_ENV: EnvVar[] = [
  { name: "CONSUL_URL", valueFrom: { configMapKeyRef: { name: "qip-env", key: "CONSUL_URL" } } },
  { name: "TRACING_ENABLED", valueFrom: { configMapKeyRef: { name: "qip-env", key: "TRACING_ENABLED" } } },
  { name: "MONITORING_ENABLED", value: "true" },
];

test("a variable the container does not declare is added at the end of its env", { tag: ["@infra", "@tier1"] }, () => {
  expect(settingsPatch(1, ENGINE_ENV, { CIP_EXPORT_LEGACY_FORMAT: "true" })).toEqual([
    {
      op: "add",
      path: "/spec/template/spec/containers/1/env/-",
      value: { name: "CIP_EXPORT_LEGACY_FORMAT", value: "true" },
    },
  ]);
});

test("a variable wired through configMapKeyRef is replaced whole, so no valueFrom stays beside the value", { tag: ["@infra", "@tier1"] }, () => {
  expect(settingsPatch(0, ENGINE_ENV, { TRACING_ENABLED: "true" })).toEqual([
    {
      op: "replace",
      path: "/spec/template/spec/containers/0/env/1",
      value: { name: "TRACING_ENABLED", value: "true" },
    },
  ]);
});

test("a variable with a plain value is replaced at its own index", { tag: ["@infra", "@tier1"] }, () => {
  expect(settingsPatch(0, ENGINE_ENV, { MONITORING_ENABLED: "false", NEW_ONE: "x" })).toEqual([
    {
      op: "replace",
      path: "/spec/template/spec/containers/0/env/2",
      value: { name: "MONITORING_ENABLED", value: "false" },
    },
    {
      op: "add",
      path: "/spec/template/spec/containers/0/env/-",
      value: { name: "NEW_ONE", value: "x" },
    },
  ]);
});

test("a restore replaces the whole env and stamps the pod template without dropping its annotations", { tag: ["@infra", "@tier1"] }, () => {
  expect(restorePatch(0, ENGINE_ENV)).toEqual([
    { op: "replace", path: "/spec/template/spec/containers/0/env", value: ENGINE_ENV },
  ]);
  const now = "2026-09-25T00:00:00.000Z";
  // The chart's sidecar opt-out is one such annotation; an `add` of the whole map would drop it.
  expect(restartStamp({ "sidecar.istio.io/inject": "false" }, now)).toEqual({
    op: "add",
    path: "/spec/template/metadata/annotations/kubectl.kubernetes.io~1restartedAt",
    value: now,
  });
  expect(restartStamp(undefined, now)).toEqual({
    op: "add",
    path: "/spec/template/metadata/annotations",
    value: { "kubectl.kubernetes.io/restartedAt": now },
  });
});

test("the micro engine's log line reads plain once its color codes are taken out", { tag: ["@infra", "@tier1"] }, () => {
  // Measured on a micro domain, trimmed to the level and the message.
  const colored = "\u001b[39m\u001b[38;5;251m[\u001b[39m\u001b[38;5;222mWARN \u001b[39m\u001b[38;5;251m] \u001b[39m\u001b[38;5;251m\u001b[38;5;231me2e-log log-odsfjcq3tqwo";
  expect(withoutColors(colored)).toBe("[WARN ] e2e-log log-odsfjcq3tqwo");
  expect(withoutColors("[INFO ] plain")).toBe("[INFO ] plain");
});
