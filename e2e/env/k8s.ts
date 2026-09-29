/**
 * The Kubernetes adapter: the suite's install of `infrastructure/qip-dev` in namespace `qip-e2e`.
 *
 * Every host-side address is a fixed port from `env/containers.ts`. The chart names each platform
 * Deployment `qip-<role>-v1` and its container `qip-<role>`, and `kubectl` reaches both through the
 * current kube-context.
 */
import path from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";
import type {
  DomainFacts,
  EngineKind,
  Env,
  Overlay,
  ProcessEnvelope,
  ResourcePeak,
  ServiceRole,
} from "./index.js";
import { apiPath, CHAIN_ROUTE_PREFIX } from "./api-routes.js";
import { proxyUrl, serviceUrl } from "./containers.js";
import { readK8sPeaks } from "./k8s-observer.js";
import {
  restartStamp,
  restorePatch,
  settingsPatch,
  type EnvVar,
  type PatchOperation,
} from "./k8s-env-patch.js";
import { capture, httpStatus, pollUntil } from "./host.js";
import { forgetOverride, recordOverride } from "./overrides.js";
import { UUID } from "../support/absent.js";
import { readStateFile } from "../support/state-file.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

export const NAMESPACE = "qip-e2e";
export const RELEASE = "qip";

/** The chart's `blueGreenVersion`, the suffix of every platform Deployment and Service. */
const VERSION = "v1";

/** The Deployment behind a role. */
export function deploymentOf(role: ServiceRole): string {
  return `${RELEASE}-${role}-${VERSION}`;
}

/** The service container of a role's pod, as against the Istio sidecar beside it. */
export function containerOf(role: ServiceRole): string {
  return `${RELEASE}-${role}`;
}

/** The Service of a micro domain: `CloudServiceNamingStrategy` with an empty Service suffix. */
export function microServiceOf(domain: string): string {
  return `${RELEASE}-engine-${domain}-${VERSION}`;
}

/** Port 8080 of Service `service`, through the proxy's `/e2e/svc/` location. */
export function proxiedServiceUrl(service: string, path: string): string {
  return `${proxyUrl()}/e2e/svc/${service}${path}`;
}

function chainRoute(contextPath: string): string {
  return `${CHAIN_ROUTE_PREFIX}${contextPath.startsWith("/") ? contextPath : `/${contextPath}`}`;
}

/** Where a chain served by the engine behind Service `service` answers, through `/e2e/svc/`. */
export function proxiedChainUrl(service: string, contextPath: string): string {
  return proxiedServiceUrl(service, chainRoute(contextPath));
}

/**
 * Where a chain on micro domain `domain` answers. A micro domain has no host port, so the request
 * goes through the proxy's `/e2e/svc/` location to the domain's Service.
 */
export function microChainUrl(domain: string, contextPath: string): string {
  return proxiedChainUrl(microServiceOf(domain), contextPath);
}

/**
 * Where a `restartWith` is recorded until the `restart` that undoes it, beside the Compose record
 * and never shared with it, so a provisioner restores only what its own target was left with.
 */
export const K8S_OVERRIDES_FILE = path.resolve(HERE, "..", ".e2e-k8s-overrides.json");

/** Where `seed-micro` records the micro copy of the corpus and the domain it deployed it to. */
export const MICRO_CORPUS_STATE_FILE = path.resolve(HERE, "..", ".e2e-k8s-micro-corpus.json");

/** The micro domain `seed-micro` recorded in `file`. */
export function recordedMicroDomain(file: string = MICRO_CORPUS_STATE_FILE): string {
  const domain = readStateFile<{ domain?: string }>(file)?.domain;
  if (!domain) {
    throw new Error(`No micro domain is recorded in ${file}: the seed-micro project deploys one.`);
  }
  return domain;
}

/** The container camel-k gives the engine in a micro domain's pod. */
const MICRO_CONTAINER = "integration";

/** What answers when a role is up: the actuator for the JVM services, `/health` for the Go one. */
export function healthUrl(role: ServiceRole): string {
  return `${serviceUrl(role)}${role === "testing-service" ? "/health" : "/actuator/health"}`;
}

export const OVERLAYS_UNAVAILABLE = "Broker overlays are not available on the k8s target.";

/** Runs `kubectl` and returns its standard output; a non-zero exit throws with its stderr. */
export function kubectl(args: string[]): Promise<string> {
  return capture("kubectl", args);
}

/** Runs `helm` and returns its standard output; a non-zero exit throws with its stderr. */
export function helm(args: string[]): Promise<string> {
  return capture("helm", args);
}

/** The part of a Deployment a restart reads. */
interface DeploymentView {
  kind?: string;
  metadata: { name: string };
  spec: {
    template: {
      metadata?: { annotations?: Record<string, string> };
      spec: { containers: { name: string; env?: EnvVar[] }[] };
    };
  };
}

/** The index of the role's service container in the pod template. */
function containerIndex(role: ServiceRole, deployment: DeploymentView): number {
  const at = deployment.spec.template.spec.containers.findIndex(
    (each) => each.name === containerOf(role),
  );
  if (at === -1) throw new Error(`${deploymentOf(role)} has no container ${containerOf(role)}`);
  return at;
}

async function liveDeployment(role: ServiceRole): Promise<DeploymentView> {
  return JSON.parse(
    await kubectl(["get", "deploy", deploymentOf(role), "-n", NAMESPACE, "-o", "json"]),
  ) as DeploymentView;
}

/** The container `env` the release's manifest declares, which `helm upgrade` alone never restores. */
async function declaredEnv(role: ServiceRole): Promise<EnvVar[]> {
  const deployment = yaml
    .loadAll(await helm(["get", "manifest", RELEASE, "-n", NAMESPACE]))
    .map((each) => each as DeploymentView | null)
    .find((each) => each?.kind === "Deployment" && each.metadata.name === deploymentOf(role));
  if (!deployment) {
    throw new Error(`the manifest of release ${RELEASE} declares no Deployment ${deploymentOf(role)}`);
  }
  return deployment.spec.template.spec.containers[containerIndex(role, deployment)].env ?? [];
}

/** Sends `patch` together with a restart stamp, as one change, so the pod rolls out once. */
async function patchAndRestart(
  role: ServiceRole,
  deployment: DeploymentView,
  patch: PatchOperation[],
): Promise<void> {
  const annotations = deployment.spec.template.metadata?.annotations;
  const stamp = restartStamp(annotations, new Date().toISOString());
  await kubectl([
    "patch", "deploy", deploymentOf(role), "-n", NAMESPACE, "--type=json",
    "-p", JSON.stringify([...patch, stamp]),
  ]);
}

/**
 * Waits for the rollout a patch started, then for the role to answer on its host port.
 *
 * `rollout status` waits until the controller has observed the new generation, so it cannot return
 * on the pod the patch replaced. Under `Recreate` the role has no pod for part of the wait.
 */
async function waitRolledOut(role: ServiceRole): Promise<void> {
  await kubectl([
    "rollout", "status", `deploy/${deploymentOf(role)}`, "-n", NAMESPACE, "--timeout=300s",
  ]);
  const url = healthUrl(role);
  await pollUntil(
    180_000,
    2_000,
    async () => {
      const status = await httpStatus(url);
      return status === 200 ? null : `HTTP ${status || "no answer"}`;
    },
    (last) => `${deploymentOf(role)} rolled out but ${url} did not answer 200 within 180s (${last})`,
  );
}

/**
 * Restores the role's `env` from the Helm manifest and restarts its pod. Exported for the
 * provisioner, which runs it for every role a previous run left in the override record.
 */
export async function restoreDeclared(role: ServiceRole): Promise<void> {
  const deployment = await liveDeployment(role);
  await patchAndRestart(
    role,
    deployment,
    restorePatch(containerIndex(role, deployment), await declaredEnv(role)),
  );
  await waitRolledOut(role);
  // After the rollout: a restore that threw leaves the record for the next run to act on.
  await forgetOverride(role, K8S_OVERRIDES_FILE);
}

/** The part of a pod `processEnvelope` and `address` read. */
interface PodView {
  metadata: { name: string; deletionTimestamp?: string };
  status: {
    phase?: string;
    startTime?: string;
    podIP?: string;
    containerStatuses?: { name: string; restartCount: number }[];
  };
}

/** One value of a Spring Boot actuator metric, or `null` where the service does not serve it. */
async function actuatorGauge(role: ServiceRole, metric: string): Promise<number | null> {
  const url = `${serviceUrl(role)}/actuator/metrics/${metric}?tag=id:Metaspace`;
  const response = await fetch(url, { signal: AbortSignal.timeout(5_000) }).catch(() => null);
  if (!response?.ok) return null;
  const body = (await response.json()) as { measurements?: { statistic: string; value: number }[] };
  return body.measurements?.find((each) => each.statistic === "VALUE")?.value ?? null;
}

/** The one pod of a role that is not being deleted. */
async function livePod(role: ServiceRole): Promise<PodView> {
  const pods = JSON.parse(
    await kubectl([
      "get", "pods", "-n", NAMESPACE, "-l", `app=${deploymentOf(role)}`, "-o", "json",
    ]),
  ) as { items: PodView[] };
  // A Recreate rollout leaves the old pod terminating for a while beside nothing, or beside the new
  // one once it is scheduled; the live one is the one not being deleted.
  const live = pods.items.filter((pod) => pod.metadata.deletionTimestamp === undefined);
  if (live.length !== 1) {
    throw new Error(
      `expected one live pod of ${deploymentOf(role)} in ${NAMESPACE}, found ` +
        `${live.map((pod) => pod.metadata.name).join(", ") || "none"}`,
    );
  }
  return live[0];
}

/** The envelope of one role, from its pod and its actuator. */
export async function k8sProcessEnvelope(role: ServiceRole): Promise<ProcessEnvelope> {
  const pod = await livePod(role);
  const restarts =
    pod.status.containerStatuses?.find((each) => each.name === containerOf(role))?.restartCount ?? 0;
  // The Go service has no actuator, so its Metaspace is null, as `jcmd` failing makes it on Compose.
  const [used, max] =
    role === "testing-service"
      ? [null, null]
      : await Promise.all([
          actuatorGauge(role, "jvm.memory.used"),
          actuatorGauge(role, "jvm.memory.max"),
        ]);
  return {
    role,
    startedAt: pod.status.startTime ?? "",
    restarts,
    metaspace: used === null || max === null ? null : { usedBytes: used, maxBytes: max },
  };
}

export interface K8sEnvOptions {
  engineKind: EngineKind;
  /** The micro domain a micro chain is addressed on; `recordedMicroDomain` by default. */
  microDomain?: () => string;
}

export class K8sEnv implements Env {
  readonly name = "k8s";
  // UUID-shaped, not a v4 UUID: Envoy writes its trace decision into the version digit.
  readonly generatedRequestId = UUID.source;

  private readonly engineKind: EngineKind;
  private readonly microDomain: () => string;

  constructor(options: K8sEnvOptions) {
    this.engineKind = options.engineKind;
    this.microDomain = options.microDomain ?? (() => recordedMicroDomain());
  }

  url(service: ServiceRole): string {
    return serviceUrl(service);
  }

  apiUrl(service: ServiceRole, servicePath: string): string {
    return `${proxyUrl()}${apiPath(service, servicePath)}`;
  }

  chainUrl(contextPath: string): string {
    // The classic engine answers on its own port, as on Compose.
    return this.engineKind === "classic"
      ? `${serviceUrl("engine")}${chainRoute(contextPath)}`
      : microChainUrl(this.microDomain(), contextPath);
  }

  uiUrl(page: string): string {
    return `${proxyUrl()}${page.startsWith("/") ? page : `/${page}`}`;
  }

  async logs(service: ServiceRole, since: string): Promise<string> {
    return kubectl([
      "logs", `deploy/${deploymentOf(service)}`, "-n", NAMESPACE, "-c", containerOf(service),
      `--since-time=${since}`,
    ]);
  }

  /** The classic engine's log, or the log of the micro domain's pod, which camel-k names after it. */
  async engineLogs(since: string): Promise<string> {
    if (this.engineKind === "classic") return this.logs("engine", since);
    return kubectl([
      "logs", `deploy/${microServiceOf(this.microDomain())}`, "-n", NAMESPACE, "-c", MICRO_CONTAINER,
      `--since-time=${since}`,
    ]);
  }

  async settings(service: ServiceRole): Promise<Record<string, string>> {
    const stdout = await kubectl([
      "exec", `deploy/${deploymentOf(service)}`, "-n", NAMESPACE, "-c", containerOf(service),
      "--", "env",
    ]);
    return Object.fromEntries(
      stdout.split("\n").filter(Boolean).map((line) => {
        const at = line.indexOf("=");
        return [line.slice(0, at), line.slice(at + 1)];
      }),
    );
  }

  async restartWith(service: ServiceRole, settings: Record<string, string>): Promise<void> {
    const deployment = await liveDeployment(service);
    const container = containerIndex(service, deployment);
    const current = deployment.spec.template.spec.containers[container].env ?? [];
    // Recorded before the patch, as on Compose: the rollout can still fail after the patch lands.
    await recordOverride(service, settings, K8S_OVERRIDES_FILE);
    await patchAndRestart(service, deployment, settingsPatch(container, current, settings));
    await waitRolledOut(service);
  }

  async restart(service: ServiceRole): Promise<void> {
    await restoreDeclared(service);
  }

  /** Nothing to reload: nginx reaches each Service by its ClusterIP, which a pod restart keeps. */
  async reloadProxy(): Promise<void> {}

  processEnvelope(service: ServiceRole): Promise<ProcessEnvelope> {
    return k8sProcessEnvelope(service);
  }

  async address(service: ServiceRole): Promise<string> {
    return (await livePod(service)).status.podIP ?? "";
  }

  async resourcePeaks(): Promise<ResourcePeak[]> {
    return readK8sPeaks();
  }

  async ensureOverlay(_overlay: Overlay): Promise<void> {
    throw new Error(OVERLAYS_UNAVAILABLE);
  }

  async restartOverlay(_overlay: Overlay): Promise<void> {
    throw new Error(OVERLAYS_UNAVAILABLE);
  }

  /** The chart's `ClassicDomainSource` reads the engine Deployments and lists their pods. */
  domainFacts(): DomainFacts {
    return { listsEnginePods: true };
  }
}
