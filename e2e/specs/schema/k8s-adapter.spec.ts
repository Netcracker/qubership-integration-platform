/**
 * The Kubernetes adapter's logic that needs no cluster: the cluster kind read from the context, the
 * addresses per role and per engine kind, the prerequisite checks against a stubbed `kubectl`, the
 * Helm arguments, the micro-engine build profile, the `kubectl top` parser, the broker overlay methods, and the `env` patches a
 * restart sends.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { K8sEnv, OVERLAYS_UNAVAILABLE, recordedMicroDomain } from "../../env/k8s.js";
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
  type Kubectl,
} from "../../env/provision/k8s.js";
import { repoRoot } from "../../env/host.js";
import { CLASSIC_ONLY_RUNTIME_FILES, targetSetup } from "../../env/target-setup.js";
import type { ServiceRole } from "../../env/index.js";
import { URL_OVERRIDES, withEnv } from "../../support/with-env.js";

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

    // A micro domain has no host port; the proxy reaches its Service by the name the catalog gives it.
    const micro = new K8sEnv({ engineKind: "micro", microDomain: () => "e2e-run1-micro" });
    expect(micro.chainUrl("micro/e2e/echo")).toBe(
      "http://localhost:30080/e2e/svc/qip-engine-e2e-run1-micro-v1/routes/micro/e2e/echo",
    );
    expect(micro.url("engine")).toBe("http://localhost:30092");
    const unseeded = new K8sEnv({
      engineKind: "micro",
      microDomain: () => recordedMicroDomain(path.join(os.tmpdir(), "e2e-no-micro-corpus.json")),
    });
    expect(() => unseeded.chainUrl("x")).toThrow(/seed-micro/);
  });
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
  spec: {
    template: {
      spec: { containers: [{ env: [{ name: "PILOT_ENABLE_ALPHA_GATEWAY_API", value: "true" }] }] },
    },
  },
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
  "get crd udproutes.gateway.networking.k8s.io": "",
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
      message: /Gateway API CRDs, experimental channel: the HTTPRoute CRD is not installed\. .*#gateway-api/,
    },
    {
      answers: without("get crd udproutes.gateway.networking.k8s.io"),
      message: /the standard channel is installed rather than the experimental one\. .*#gateway-api/,
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
      answers: {
        ...COMPLETE,
        "get deploy istiod -n istio-system -o json": ISTIOD.replace('"true"', '"false"'),
      },
      message: /Istio: istiod runs without PILOT_ENABLE_ALPHA_GATEWAY_API=true.*#istio/,
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
    "upgrade", "--install", "qip", "/repo/infrastructure/qip-dev", "-n", "qip-e2e", "-f",
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
  expect(k3d.filter((each) => /nodePort=|ui\.port=/.test(each))).toEqual([
    "qip-runtime-catalog.nodePort=30091",
    "qip-engine.nodePort=30092",
    "qip-sessions-management.nodePort=30093",
    "qip-testing-service.nodePort=30095",
    "global.qip.ui.port=30080",
  ]);
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

test("the broker overlays fail on the k8s target, naming the target", { tag: ["@infra", "@tier1"] }, async () => {
  const env = new K8sEnv({ engineKind: "classic" });
  await expect(env.ensureOverlay("kafka")).rejects.toThrow(OVERLAYS_UNAVAILABLE);
  await expect(env.restartOverlay("rabbitmq")).rejects.toThrow(OVERLAYS_UNAVAILABLE);
});

test("the k8s target declares its own projects and lists the brokers and the classic-only files as not run", { tag: ["@infra", "@tier1"] }, () => {
  const setup = targetSetup("k8s");
  expect(
    setup.projects.map((each) => ({
      name: each.name,
      dependencies: each.dependencies,
      engineKind: each.use?.engineKind,
    })),
  ).toEqual([
    { name: "k8s", dependencies: ["seed"], engineKind: undefined },
    { name: "seed-micro", dependencies: undefined, engineKind: "micro" },
    { name: "seed-micro-teardown", dependencies: undefined, engineKind: undefined },
    { name: "runtime-micro", dependencies: ["seed-micro", "tooling"], engineKind: "micro" },
  ]);
  expect(setup.projects.find((each) => each.name === "seed-micro")?.teardown).toBe("seed-micro-teardown");
  // The micro teardown waits for `global`, and with it for the catalog restarts in `env`.
  expect(setup.decorate({ name: "global" }).teardown).toBe("seed-micro-teardown");
  expect(setup.decorate({ name: "env" }).teardown).toBeUndefined();
  expect(setup.absent.map((each) => each.name)).toEqual([
    "brokers-seed",
    "brokers-seed-teardown",
    "brokers",
    "brokers-restart",
    ...CLASSIC_ONLY_RUNTIME_FILES.map((file) => `runtime-micro: specs/runtime/${file}`),
  ]);
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
