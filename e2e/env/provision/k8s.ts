/**
 * Bring the suite's Kubernetes install to the commit under test, before any project starts.
 *
 * The developer installs the cluster and what runs cluster-wide: Istio with the Gateway API CRDs,
 * the camel-k operator, and metrics-server (`e2e/k8s/README.md`). This module checks for them, then
 * owns everything in namespace `qip-e2e`: it builds the images from the checkout, installs the chart
 * with `helm upgrade --install`, and waits until every role answers on its host port.
 *
 * Each image is tagged with a hash over its own inputs, so an image whose tag already exists is not
 * rebuilt, and Helm leaves a Deployment whose tag did not change alone.
 */
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import type { ServiceRole } from "../index.js";
import { hostPort, proxyPort, proxyUrl, ROLES } from "../containers.js";
import {
  deploymentOf,
  healthUrl,
  K8S_OVERRIDES_FILE,
  kubectl,
  NAMESPACE,
  RELEASE,
  restoreDeclared,
} from "../k8s.js";
import { capture, httpStatus, pollUntil, repoRoot, stream } from "../host.js";
import { describeOverride, overrideNotice, readOverrides } from "../overrides.js";
import { apiPath } from "../api-routes.js";
import type { ProvisionMode, ProvisionReport } from "../provision.js";
import { emptyProvisionReport, mavenInstall } from "./common.js";
import { provisionedSources } from "./compose.js";

const README = "e2e/k8s/README.md";

// ---------------------------------------------------------------------------
// Prerequisites
// ---------------------------------------------------------------------------

/** Runs `kubectl` with the given arguments and returns stdout; a non-zero exit throws. */
export type Kubectl = (args: string[]) => Promise<string>;

/** A cluster-wide prerequisite the suite does not install, and the README section that does. */
export interface Prerequisite {
  name: string;
  section: string;
  /** `null` when the prerequisite is in place, otherwise what is wrong. */
  check(kubectl: Kubectl): Promise<string | null>;
}

/** Whether `run(args)` exits zero: `kubectl`, or another command run through `capture`. */
async function answers(run: Kubectl, args: string[]): Promise<boolean> {
  return run(args).then(
    () => true,
    () => false,
  );
}

interface DeploymentStatusView {
  metadata: { name: string };
  status?: { availableReplicas?: number };
  spec: { template: { spec: { containers: { env?: { name: string; value?: string }[] }[] } } };
}

/** In the order they are checked. The run fails on the first one missing. */
const PREREQUISITES: Prerequisite[] = [
  {
    name: "a Kubernetes cluster",
    section: "cluster",
    async check(kubectl) {
      const context = await kubectl(["config", "current-context"]).then(
        (out) => out.trim(),
        () => "",
      );
      if (!context) return "kubectl has no current context";
      return (await answers(kubectl, ["get", "--raw", "/readyz"]))
        ? null
        : `the current kube-context ${context} does not answer`;
    },
  },
  {
    name: "the Gateway API CRDs, experimental channel",
    section: "gateway-api",
    async check(kubectl) {
      if (!(await answers(kubectl, ["get", "crd", "httproutes.gateway.networking.k8s.io"]))) {
        return "the HTTPRoute CRD is not installed";
      }
      // The standard channel lacks UDPRoute v1alpha2, which istiod watches under
      // PILOT_ENABLE_ALPHA_GATEWAY_API, and without it istiod never becomes ready.
      return (await answers(kubectl, ["get", "crd", "udproutes.gateway.networking.k8s.io"]))
        ? null
        : "the UDPRoute CRD is missing, so the standard channel is installed rather than the experimental one";
    },
  },
  {
    name: "Istio",
    section: "istio",
    async check(kubectl) {
      const raw = await kubectl(["get", "deploy", "istiod", "-n", "istio-system", "-o", "json"]).catch(
        () => null,
      );
      if (raw === null) return "no istiod Deployment in namespace istio-system";
      const istiod = JSON.parse(raw) as DeploymentStatusView;
      if (!istiod.status?.availableReplicas) return "istiod has no available replica";
      const alpha = istiod.spec.template.spec.containers
        .flatMap((container) => container.env ?? [])
        .find((each) => each.name === "PILOT_ENABLE_ALPHA_GATEWAY_API");
      return alpha?.value === "true"
        ? null
        : "istiod runs without PILOT_ENABLE_ALPHA_GATEWAY_API=true, so the egress routes are never programmed";
    },
  },
  {
    name: "the camel-k operator",
    section: "camel-k",
    async check(kubectl) {
      if (!(await answers(kubectl, ["get", "crd", "integrations.camel.apache.org"]))) {
        return "the Integration CRD is not installed";
      }
      const raw = await kubectl([
        "get", "deploy", "-A", "-l", "app=camel-k,camel.apache.org/component=operator", "-o", "json",
      ]).catch(() => null);
      const operators = raw === null ? [] : (JSON.parse(raw) as { items: DeploymentStatusView[] }).items;
      return operators.some((each) => (each.status?.availableReplicas ?? 0) > 0)
        ? null
        : "no camel-k operator Deployment has an available replica";
    },
  },
  {
    name: "metrics-server",
    section: "metrics-server",
    async check(kubectl) {
      return (await answers(kubectl, ["get", "--raw", "/apis/metrics.k8s.io/v1beta1/nodes"]))
        ? null
        : "the metrics API does not answer, so kubectl top and the resource sampler cannot work";
    },
  },
];

/** The message a missing prerequisite fails the run with. */
function prerequisiteError(prerequisite: Prerequisite, problem: string): string {
  return (
    `The k8s target needs ${prerequisite.name}: ${problem}. Install it as ` +
    `${README}#${prerequisite.section} describes.`
  );
}

/** Checks every prerequisite in order and throws on the first one missing. */
export async function checkPrerequisites(run: Kubectl = kubectl): Promise<void> {
  for (const prerequisite of PREREQUISITES) {
    const problem = await prerequisite.check(run);
    if (problem !== null) throw new Error(prerequisiteError(prerequisite, problem));
  }
}

// ---------------------------------------------------------------------------
// The cluster kind
// ---------------------------------------------------------------------------

export type ClusterKind = "docker-desktop" | "kind" | "k3d";

/** What differs between the three cluster kinds the suite supports. */
export interface ClusterChoice {
  kind: ClusterKind;
  /** The cluster's own name, which `kind load` and `k3d image import` take. */
  name: string;
  /** How a host-built image reaches the node. */
  imageLoading: "host daemon" | "kind load docker-image" | "k3d image import";
  /** CoreDNS, for the nginx locations whose upstream is a variable. */
  resolver: string;
  /**
   * The name the proxy reaches the UI bundle on the host by, or `null` on kind, where it is the
   * gateway address of the `kind` Docker network, read when provisioning.
   */
  devServerHost: string | null;
  /**
   * How the fixed host ports reach the Services. Docker Desktop's kind-based cluster publishes a
   * LoadBalancer port on the host and no NodePort; kind and k3d map the NodePorts when the
   * cluster is created.
   */
  hostPorts: "LoadBalancer Services" | "NodePorts";
}

/** The cluster kind, read from the kube-context name. */
export function clusterChoice(context: string): ClusterChoice {
  if (context === "docker-desktop") {
    return {
      kind: "docker-desktop",
      name: "docker-desktop",
      imageLoading: "host daemon",
      resolver: "10.96.0.10",
      devServerHost: "host.docker.internal",
      hostPorts: "LoadBalancer Services",
    };
  }
  const kind = /^kind-(.+)$/.exec(context);
  if (kind) {
    return {
      kind: "kind",
      name: kind[1],
      imageLoading: "kind load docker-image",
      resolver: "10.96.0.10",
      devServerHost: null,
      hostPorts: "NodePorts",
    };
  }
  const k3d = /^k3d-(.+)$/.exec(context);
  if (k3d) {
    return {
      kind: "k3d",
      name: k3d[1],
      imageLoading: "k3d image import",
      resolver: "10.43.0.10",
      devServerHost: "host.k3d.internal",
      hostPorts: "NodePorts",
    };
  }
  throw new Error(
    `The kube-context ${JSON.stringify(context)} is none of docker-desktop, kind-<name>, or ` +
      `k3d-<name>, the clusters the k8s target supports. Switch with kubectl config use-context, ` +
      `or create one as ${README} describes.`,
  );
}

/** The choice in the words the provisioning log prints. */
function describeChoice(choice: ClusterChoice, devServerHost: string): string {
  return (
    `[provision] cluster ${choice.kind} (${choice.name}): images through ${choice.imageLoading}, ` +
    `host ports through ${choice.hostPorts}, resolver ${choice.resolver}, UI dev server on ` +
    `${devServerHost}`
  );
}

async function kindNetworkGateway(): Promise<string> {
  const stdout = await capture("docker", [
    "network", "inspect", "kind", "-f", "{{range .IPAM.Config}}{{.Gateway}} {{end}}",
  ]);
  const gateway = stdout.split(/\s+/).find((each) => /^\d+\.\d+\.\d+\.\d+$/.test(each));
  if (!gateway) throw new Error("The kind Docker network has no IPv4 gateway to reach the host by.");
  return gateway;
}

// ---------------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------------

/** An image the suite builds from the checkout. */
export interface SuiteImage {
  /** The role the image runs, or `micro-engine`, which the catalog deploys per micro domain. */
  name: ServiceRole | "micro-engine";
  repository: string;
  /** The module directory holding the Dockerfile, relative to the repository root. */
  context: string;
  /** Maven module to install before the image is built, when the Dockerfile copies `target/`. */
  mavenModule?: string;
  /** Extra Maven arguments for this module's build. They take part in the tag. */
  mavenArgs?: string[];
  /** What the tag is a hash over, relative to the repository root. */
  sources: string[];
}

const REPOSITORY = "ghcr.io/netcracker/qubership-integration";

/** The four platform images, with the Compose provisioner's sources, and the micro engine. */
export function suiteImages(): SuiteImage[] {
  const compose = new Map(provisionedSources().map((each) => [each.service, each]));
  const platform = ROLES.map((role): SuiteImage => {
    const entry = compose.get(`qip-${role}`);
    if (!entry) throw new Error(`the Compose provisioner has no entry for qip-${role}`);
    return {
      name: role,
      repository: `${REPOSITORY}-${role}`,
      context: role,
      mavenModule: entry.mavenModule,
      sources: entry.sources,
    };
  });
  return [
    ...platform,
    {
      name: "micro-engine",
      repository: `${REPOSITORY}-micro-engine`,
      context: "micro-engine",
      mavenModule: "micro-engine",
      // Quarkus fixes part of its configuration at build time. The default `prod` build needs a
      // DBaaS agent and an M2M Consul login, which the chart has neither of; micro-engine/README.md
      // gives these profiles for a local stack.
      mavenArgs: ["-Dquarkus.profile=development,no-m2m"],
      sources: ["micro-engine/src", "micro-engine/pom.xml", "micro-engine/Dockerfile"],
    },
  ];
}

function filesUnder(root: string, relative: string): string[] {
  const absolute = path.resolve(root, relative);
  const stat = fs.statSync(absolute, { throwIfNoEntry: false });
  if (!stat) return [];
  if (stat.isFile()) return [relative];
  return fs
    .readdirSync(absolute, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => path.relative(root, path.join(entry.parentPath, entry.name)));
}

/**
 * `e2e-` and 12 hex digits of a SHA-256 over the sources' paths and contents, and over the Maven
 * arguments, which change the jar the same sources build.
 *
 * Contents rather than modification times: a checkout of the same commit elsewhere, or a touch that
 * changes nothing, gives the same tag, and `target/` never takes part.
 */
export function imageTag(
  root: string,
  sources: readonly string[],
  mavenArgs: readonly string[] = [],
): string {
  const hash = createHash("sha256");
  for (const arg of mavenArgs) {
    hash.update(arg);
    hash.update("\0");
  }
  const files = sources.flatMap((each) => filesUnder(root, each)).sort();
  for (const file of files) {
    hash.update(file);
    hash.update("\0");
    hash.update(fs.readFileSync(path.resolve(root, file)));
    hash.update("\0");
  }
  return `e2e-${hash.digest("hex").slice(0, 12)}`;
}

async function imageExists(reference: string): Promise<boolean> {
  return answers((args) => capture("docker", args), ["image", "inspect", reference]);
}

async function loadImage(choice: ClusterChoice, reference: string, root: string): Promise<void> {
  if (choice.kind === "kind") {
    await stream("kind", ["load", "docker-image", reference, "--name", choice.name], root);
  } else if (choice.kind === "k3d") {
    await stream("k3d", ["image", "import", reference, "-c", choice.name], root);
  }
}

// ---------------------------------------------------------------------------
// The install
// ---------------------------------------------------------------------------

/** The `helm upgrade --install` arguments for the images this run built and the cluster kind. */
export function helmArguments(options: {
  root: string;
  choice: ClusterChoice;
  devServerHost: string;
  tags: Record<SuiteImage["name"], string>;
}): string[] {
  const { root, choice, devServerHost, tags } = options;
  const args = [
    "upgrade", "--install", RELEASE, path.join(root, "infrastructure/qip-dev"),
    "-n", NAMESPACE,
    "-f", path.join(root, "e2e/k8s/values.e2e.yaml"),
    "--set-string", `global.qip.ui.resolver=${choice.resolver}`,
    "--set-string", `global.qip.ui.devServer.host=${devServerHost}`,
    "--set-string", `global.qip.deploy.micro.image=${REPOSITORY}-micro-engine:${tags["micro-engine"]}`,
    // The catalog reads the micro image from a ConfigMap once, at startup. The annotation changes its
    // pod template with the tag, so Helm restarts it.
    "--set-string", `qip-runtime-catalog.podAnnotations.e2e-micro-engine-tag=${tags["micro-engine"]}`,
  ];
  for (const role of ROLES) args.push("--set-string", `qip-${role}.image.tag=${tags[role]}`);
  // The host ports the suite addresses, from the table its URLs are built from.
  for (const role of ROLES) args.push("--set", `qip-${role}.nodePort=${hostPort(role, "k8s")}`);
  args.push("--set", `global.qip.ui.port=${proxyPort("k8s")}`);
  if (choice.hostPorts === "LoadBalancer Services") {
    for (const role of ROLES) args.push("--set", `qip-${role}.loadBalancerPort=${hostPort(role, "k8s")}`);
    args.push("--set", `global.qip.ui.loadBalancerPort=${proxyPort("k8s")}`);
  }
  return args;
}

/** A route through the proxy that answers only once the catalog behind it does. */
function proxyProbe(): string {
  return `${proxyUrl()}${apiPath("runtime-catalog", "/v1/folders")}`;
}

/** The addresses that are not serving yet, each with what it answered. */
async function notServing(): Promise<string[]> {
  const down: string[] = [];
  for (const role of ROLES) {
    const got = await httpStatus(healthUrl(role));
    if (got !== 200) down.push(`${role} (${healthUrl(role)}: ${got || "no answer"})`);
  }
  const proxied = await httpStatus(proxyProbe());
  if (proxied !== 200) down.push(`proxy (${proxyProbe()}: ${proxied || "no answer"})`);
  return down;
}

/**
 * Waits until every role and the proxy answer. A cold install pulls nothing but starts OpenSearch,
 * Postgres, and four services behind their sidecars, so the budget is generous.
 */
async function waitServing(budgetMs = 600_000): Promise<void> {
  await pollUntil(
    budgetMs,
    5_000,
    async () => {
      const down = await notServing();
      return down.length ? down.join(", ") : null;
    },
    (last) =>
      `Not serving within ${budgetMs / 1000}s after the install into ${NAMESPACE}: ` +
      `${last}. kubectl get pods -n ${NAMESPACE} shows what is wrong.`,
  );
}

/**
 * The generation of each platform Deployment, which moves whenever Helm changes its spec, the image
 * and the rest of the pod template included. A Deployment that does not exist has none.
 */
async function deploymentGenerations(): Promise<Partial<Record<ServiceRole, string>>> {
  const generations: Partial<Record<ServiceRole, string>> = {};
  for (const role of ROLES) {
    const generation = await kubectl([
      "get", "deploy", deploymentOf(role), "-n", NAMESPACE, "-o", "jsonpath={.metadata.generation}",
    ]).catch(() => "");
    if (generation.trim()) generations[role] = generation.trim();
  }
  return generations;
}

async function ensureNamespace(): Promise<void> {
  if (!(await answers(kubectl, ["get", "namespace", NAMESPACE]))) {
    await kubectl(["create", "namespace", NAMESPACE]);
  }
  // Before the install: a sidecar is injected when a pod is created.
  await kubectl(["label", "--overwrite", "namespace", NAMESPACE, "istio-injection=enabled"]);
}

export async function provisionK8s(mode: ProvisionMode): Promise<ProvisionReport> {
  const startedAt = Date.now();
  const report = emptyProvisionReport(mode);

  if (mode === "never") {
    for (const [role, override] of Object.entries(await readOverrides(K8S_OVERRIDES_FILE))) {
      console.error(overrideNotice(deploymentOf(role as ServiceRole), override, K8S_OVERRIDES_FILE));
    }
    const down = await notServing();
    if (down.length) {
      throw new Error(
        `E2E_PROVISION=never and these do not answer: ${down.join(", ")}. Unset E2E_PROVISION to ` +
          `let the suite install the chart into ${NAMESPACE}.`,
      );
    }
    report.untouched = [...ROLES];
    report.durationMs = Date.now() - startedAt;
    return report;
  }

  await checkPrerequisites();
  const choice = clusterChoice((await kubectl(["config", "current-context"])).trim());
  const devServerHost = choice.devServerHost ?? (await kindNetworkGateway());
  console.log(describeChoice(choice, devServerHost));

  const root = repoRoot();
  const images = suiteImages();
  const tags = Object.fromEntries(
    images.map((image) => [image.name, imageTag(root, image.sources, image.mavenArgs)]),
  ) as Record<SuiteImage["name"], string>;
  const missing: SuiteImage[] = [];
  for (const image of images) {
    if (!(await imageExists(`${image.repository}:${tags[image.name]}`))) missing.push(image);
  }

  // The Dockerfiles copy what `target/` holds, so the jar is built first: one Maven run for the
  // modules that need no extra arguments, and one per module that does.
  const builds = new Map<string, { args: string[]; modules: string[] }>();
  for (const image of missing) {
    if (!image.mavenModule) continue;
    const args = image.mavenArgs ?? [];
    const build = builds.get(args.join(" ")) ?? { args, modules: [] };
    build.modules.push(image.mavenModule);
    builds.set(args.join(" "), build);
  }
  for (const { args, modules } of builds.values()) {
    await mavenInstall(modules, args);
    report.built.push(...modules);
  }
  for (const image of missing) {
    const reference = `${image.repository}:${tags[image.name]}`;
    console.log(`[provision] docker build: ${reference}`);
    await stream("docker", ["build", "-t", reference, path.join(root, image.context)], root);
    report.rebuilt.push(image.name);
  }
  // kind skips an image the node already holds; k3d imports it again, which costs seconds.
  for (const image of images) await loadImage(choice, `${image.repository}:${tags[image.name]}`, root);

  await ensureNamespace();
  // Before the upgrade. Helm applies server-side and merges its entry into a patched one, so over a
  // `replace` of TRACING_ENABLED the upgrade failed with "may not be specified when `value` is not
  // empty".
  for (const [role, override] of Object.entries(await readOverrides(K8S_OVERRIDES_FILE))) {
    console.log(`[provision] clearing ${describeOverride(deploymentOf(role as ServiceRole), override)}`);
    await restoreDeclared(role as ServiceRole);
    report.recreated.push(role);
  }
  const before = await deploymentGenerations();
  console.log(`[provision] helm upgrade --install ${RELEASE} -n ${NAMESPACE}`);
  await stream("helm", helmArguments({ root, choice, devServerHost, tags }), root);
  // Helm returns once the objects are written. Under Recreate the old pod may still answer the
  // health poll, so the rollout is awaited first.
  for (const role of ROLES) {
    await stream(
      "kubectl",
      ["rollout", "status", `deploy/${deploymentOf(role)}`, "-n", NAMESPACE, "--timeout=600s"],
      root,
    );
  }
  await waitServing();

  // A Deployment that is new, or whose spec Helm changed, is reported as `started`.
  const after = await deploymentGenerations();
  for (const role of ROLES) {
    if (before[role] === after[role]) report.untouched.push(role);
    else report.started.push(role);
  }
  report.durationMs = Date.now() - startedAt;
  return report;
}
