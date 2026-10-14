/**
 * The Kubernetes observer: the baseline envelope, the resource sampler, and the run header facts.
 *
 * The sampler is the Compose one from `env/resources.ts` with another reading: `kubectl top pod
 * --containers`, which metrics-server answers. Its rows name the service container apart from the
 * Istio sidecar, and only the service container is counted.
 */
import fs from "node:fs";
import type { ProcessEnvelope, ResourcePeak, ServiceRole } from "./index.js";
import { ROLES, serviceUrl } from "./containers.js";
import { capture } from "./host.js";
import { containerOf, deploymentOf, helm, k8sProcessEnvelope, kubectl, NAMESPACE, RELEASE } from "./k8s.js";
import { mergePeaks, SAMPLES_FILE } from "./resources.js";
import type { ProvisionReport } from "./provision.js";
import type { ReleaseFacts, ServiceFacts, StackFacts } from "../support/report.js";
import { collectCommitFacts } from "../support/report.js";

/** `250m` or `2` cores as a percent of one core, the unit `docker stats` reports. */
function parseCores(text: string): number {
  const found = /^(\d+(?:\.\d+)?)(m|n|u)?$/.exec(text.trim());
  if (!found) return 0;
  const value = Number(found[1]);
  const divisor = { m: 1e3, u: 1e6, n: 1e9 }[found[2] ?? ""] ?? 1;
  return (value / divisor) * 100;
}

const BINARY: Record<string, number> = { Ki: 1024, Mi: 1024 ** 2, Gi: 1024 ** 3, "": 1 };

/** `812Mi` as bytes. */
function parseQuantity(text: string): number {
  const found = /^(\d+(?:\.\d+)?)(Ki|Mi|Gi)?$/.exec(text.trim());
  if (!found) return 0;
  return Number(found[1]) * BINARY[found[2] ?? ""];
}

/**
 * The rows of one `kubectl top pod --containers --no-headers` reading, reduced to the roles the
 * suite watches. A row is `POD NAME CPU MEMORY`; a pod belongs to a role when its name starts with
 * the role's Deployment, and the row counts when its container is the service container.
 */
export function readingOfTop(lines: readonly string[]): ResourcePeak[] {
  const found: ResourcePeak[] = [];
  for (const line of lines) {
    const [pod, container, cpu, memory] = line.trim().split(/\s+/);
    if (!pod || !memory) continue;
    const role = ROLES.find(
      (each) => pod.startsWith(`${deploymentOf(each)}-`) && container === containerOf(each),
    );
    if (role === undefined) continue;
    found.push({
      role,
      memoryBytes: parseQuantity(memory),
      cpuPercent: parseCores(cpu),
      samples: 1,
    });
  }
  return found;
}

/** The peaks, folded out of the rows the sampler appended. */
export function readK8sPeaks(file: string = SAMPLES_FILE): ResourcePeak[] {
  if (!fs.existsSync(file)) return [];
  let peaks: ResourcePeak[] = [];
  for (const line of fs.readFileSync(file, "utf-8").split("\n")) {
    peaks = mergePeaks(peaks, readingOfTop([line]));
  }
  return peaks;
}

/** The shell command of one reading, for the sampler loop. */
export function topSample(): string {
  return `kubectl top pod --containers --no-headers -n ${NAMESPACE}`;
}

export async function k8sBaseline(): Promise<ProcessEnvelope[]> {
  const envelopes: ProcessEnvelope[] = [];
  for (const role of ROLES) envelopes.push(await k8sProcessEnvelope(role));
  return envelopes;
}

async function releaseFacts(): Promise<ReleaseFacts> {
  const context = await kubectl(["config", "current-context"])
    .then((out) => out.trim())
    .catch(() => null);
  // `helm list` rather than `helm status`: Helm 4 leaves the chart out of the status document.
  let listed: { name: string; revision?: string; status?: string; chart?: string }[] = [];
  try {
    listed = JSON.parse(await helm(["list", "-n", NAMESPACE, "-o", "json"]));
  } catch {
    // Every field below answers null, as the Compose header does for a probe that failed.
  }
  const release = listed.find((each) => each.name === RELEASE);
  return {
    context,
    namespace: NAMESPACE,
    name: RELEASE,
    revision: release?.revision ? Number(release.revision) : null,
    status: release?.status ?? null,
    chart: release?.chart ?? null,
  };
}

/** The image a role's Deployment names, and when the host daemon built it. */
async function imageOf(role: ServiceRole): Promise<{ image: string | null; created: string | null }> {
  const image = await kubectl([
    "get", "deploy", deploymentOf(role), "-n", NAMESPACE,
    "-o", `jsonpath={.spec.template.spec.containers[?(@.name=="${containerOf(role)}")].image}`,
  ])
    .then((out) => out.trim() || null)
    .catch(() => null);
  if (image === null) return { image, created: null };
  const created = await capture("docker", ["image", "inspect", "-f", "{{.Created}}", image])
    .then((out) => out.trim())
    .catch(() => null);
  return { image, created };
}

const INFO_PATH: Record<ServiceRole, string> = {
  "runtime-catalog": "/actuator/info",
  engine: "/actuator/info",
  "sessions-management": "/actuator/info",
  "testing-service": "/api/v1/mode",
};

/** The header facts of a Kubernetes run: the commit, the Helm release, and each role's image. */
export async function collectK8sHeader(about: {
  run: string;
  workers: number;
  provision: ProvisionReport;
}): Promise<StackFacts> {
  const services: ServiceFacts[] = [];
  for (const role of ROLES) {
    const url = serviceUrl(role);
    const build = await fetch(`${url}${INFO_PATH[role]}`, { signal: AbortSignal.timeout(5_000) })
      .then((response) => (response.ok ? (response.json() as Promise<unknown>) : null))
      .catch(() => null);
    const { image, created } = await imageOf(role);
    services.push({
      role,
      url,
      build,
      container: `${NAMESPACE}/${deploymentOf(role)}`,
      imageId: image,
      imageCreated: created,
    });
  }
  return {
    run: about.run,
    startedAt: new Date().toISOString(),
    commit: collectCommitFacts(),
    workers: about.workers,
    provision: about.provision,
    services,
    release: await releaseFacts(),
  };
}
