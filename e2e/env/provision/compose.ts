/**
 * Bring the Docker Compose stack to the commit under test, before any project starts.
 *
 * One command has to be enough: `npx playwright test` starts what is missing, rebuilds what the
 * source tree has outgrown, and refuses to run against a stack it cannot vouch for.
 *
 * Two facts shape everything here.
 *
 * The service Dockerfiles copy a jar the host already built — `runtime-catalog/Dockerfile:24` is
 * `COPY target/qip-runtime-catalog-*-exec.jar` — so `docker compose up -d --build` repackages
 * whatever happens to be in `target/` and compiles no Java. Bringing the stack to a given commit
 * therefore takes two commands, not one: `mvn install` and only then `--build`. Measured: after a
 * `--build` on a fresh checkout of `main`, `PUT /v1/catalog/chains/roles` still answered the
 * pre-#754 `200`; after `mvn install` and a second `--build`, `204`.
 *
 * A service is also brought to this checkout's **configuration**, not only to its sources. A change
 * to `infrastructure/docker-compose.yml`, to an env file a service reads, or to `infrastructure/nginx/`
 * reaches a running container through none of the above: the container keeps the environment it was
 * started with and nothing says so. Recreating a container costs seconds where a Maven build costs
 * minutes, so the two reasons are decided separately and stay separate in the report.
 *
 * And nginx resolves `proxy_pass http://engine:8080` once, at config load, with no `resolver` in
 * scope. Recreate a backend and the proxy keeps an address Docker has since handed to a different
 * container. Measured: after the engine and sessions-management containers were recreated,
 * `/api/v1/qip/engine/live-exchanges` answered 500 with a *Session Management* error body. So every
 * created or recreated container is followed by `Env.reloadProxy()`, unconditionally.
 *
 * Provisioning runs in `globalSetup` rather than in a fixture: a cold service costs tens of seconds
 * and a Maven build costs minutes, and neither may be charged to a test's timeout.
 */
import path from "node:path";
import { ComposeEnv, composeFile } from "../compose.js";
import { capture, newestMtimeMs, pollUntil, repoRoot, stream } from "../host.js";
import {
  describeOverride,
  forgetOverride,
  overrideNotice,
  readOverrides,
  type ServiceOverride,
} from "../overrides.js";
import { CONTAINER, PROXY_CONTAINER } from "../compose-containers.js";
import type { ServiceRole } from "../index.js";
import type { ProvisionMode, ProvisionReport } from "../provision.js";
import { emptyProvisionReport, mavenInstall } from "./common.js";

/**
 * A service the suite provisions, and what makes its image stale.
 *
 * `sources` is anything under `<module>/src` or its `pom.xml`, compared against
 * the creation time of the image the container is actually running. No labels and no state file,
 * so it stays correct on a dirty tree, which is the normal state while developing.
 *
 * The rule is "what reaches the jar this image runs", so it holds three kinds of path rather than
 * one. `parent/pom.xml` is the parent of all three Spring services — every one of them declares
 * `qip-monorepo-parent` with `<relativePath>../parent</relativePath>` — and it carries the
 * `spring-boot-starter-parent` import, the dependency versions and the `spring-boot-maven-plugin`
 * repackage configuration, so a bump there changes every jar the build produces. The module's
 * `Dockerfile` is the other input to the image, and it is the only file in the build context the
 * jar does not already cover: all three copy nothing but `target/`. The root `pom.xml` is
 * deliberately absent — it aggregates the reactor and parents nothing, `parent/pom.xml` inherits
 * from `spring-boot-starter-parent` instead, and the `revision` and `changelist` the modules
 * interpolate are the parent's own properties rather than the aggregator's.
 */
interface Provisionable {
  /** Compose service name, which is what `docker compose up` takes. */
  service: string;
  /** Container name, which is what `docker inspect` takes. The two differ for `postgres`. */
  container: string;
  /** Maven module to install before the image is built, when the jar comes from the host. */
  mavenModule?: string;
  /** Paths whose mtime decides staleness, relative to the repository root. */
  sources: string[];
  /**
   * The env files this compose service declares, relative to the repository root.
   *
   * The compose file itself is added by `configOf` rather than repeated here, and a service whose
   * environment is written inline in the compose file needs no entry at all.
   */
  envFiles: string[];
}

/**
 * What makes a container's configuration stale, as against its image.
 *
 * Measured: after `MONITORING_ENABLED=true` was added to `infrastructure/engine-dev.env`, an engine
 * on a stack that was already up kept running without it — so the metrics spec failed
 * pointing at a file the developer had never touched. A configuration change is answered by
 * recreating the container, never by rebuilding it.
 */
function configOf(candidate: Provisionable): string[] {
  return [composeFile(), ...candidate.envFiles];
}

const PROVISIONED: Provisionable[] = [
  {
    service: "qip-runtime-catalog",
    container: "qip-runtime-catalog",
    mavenModule: "runtime-catalog",
    // integration-build-pipeline is the chain-compilation library linked into this jar, so an edit
    // there reaches the image the same way an edit under runtime-catalog/src does.
    sources: [
      "runtime-catalog/src",
      "runtime-catalog/pom.xml",
      "runtime-catalog/Dockerfile",
      "parent/pom.xml",
      "integration-build-pipeline/src",
      "integration-build-pipeline/pom.xml",
    ],
    envFiles: ["infrastructure/qip-dev.env"],
  },
  {
    service: "qip-engine",
    container: "qip-engine",
    mavenModule: "engine",
    sources: ["engine/src", "engine/pom.xml", "engine/Dockerfile", "parent/pom.xml"],
    envFiles: ["infrastructure/qip-dev.env", "infrastructure/engine-dev.env"],
  },
  {
    service: "qip-sessions-management",
    container: "qip-sessions-management",
    mavenModule: "sessions-management",
    sources: [
      "sessions-management/src",
      "sessions-management/pom.xml",
      "sessions-management/Dockerfile",
      "parent/pom.xml",
    ],
    envFiles: ["infrastructure/qip-dev.env"],
  },
  // The Go service compiles inside its own Dockerfile, so `--build` alone is enough for it, and its
  // environment is written inline in the compose file rather than in an env file of its own.
  {
    service: "qip-testing-service",
    container: "qip-testing-service",
    sources: ["testing-service"],
    envFiles: [],
  },
];

/**
 * Started when missing, never rebuilt: the suite does not own their images.
 *
 * Configuration is where that rule needs a distinction, because a change reaches a running support
 * container through nothing at all. `recreatable` is what decides whether the change is acted on or
 * only reported, and it is a fact about the compose file rather than a preference:
 * `infrastructure/docker-compose.yml` declares no volume for the data of postgres, opensearch or
 * consul, so recreating one discards the catalog's rows, the recorded sessions or the deployment
 * state that this stack is holding. The proxy holds nothing but a bind-mounted configuration, so it
 * is recreated like any application service.
 *
 * `config` is the compose file's own bind mounts, which the compose file's mtime does not cover: a
 * container reads them when it is created and never again, so editing one changes nothing about the
 * stack the suite is testing and no line said so. `init-db/` is the sharpest of the three — postgres
 * runs `/docker-entrypoint-initdb.d` only against an empty data directory, so a new schema there is
 * inert until the container is replaced, which is exactly what the notice's recreate command does.
 * The proxy's `./nginx` is absent here on purpose: a change there is answered by a reload further
 * down, which is cheaper than a recreate and already covered.
 */
const SUPPORT: Array<
  Pick<Provisionable, "service" | "container"> & { recreatable: boolean; config: string[] }
> = [
  {
    service: "postgres",
    container: "postgreSQL",
    recreatable: false,
    config: ["infrastructure/init-db"],
  },
  {
    service: "opensearch",
    container: "opensearch",
    recreatable: false,
    config: ["infrastructure/opensearch/opensearch.yml"],
  },
  { service: "consul", container: "consul", recreatable: false, config: ["infrastructure/consul"] },
  { service: "ui-proxy", container: "ui-proxy", recreatable: true, config: [] },
];

/**
 * Each support service's bind-mounted configuration, for the spec that pins it against the mounts.
 *
 * `newestMtimeMs` skips a path that does not exist, so a list that has drifted from the compose
 * file's mounts reports "nothing to do" forever rather than failing — the one shape of rot no run
 * can show.
 */
export function supportConfig(): Array<{ service: string; config: string[] }> {
  return SUPPORT.map(({ service, config }) => ({ service, config }));
}

/**
 * Each provisioned service's staleness sources, for the spec that pins them against the checkout.
 *
 * Exported for the reason `supportConfig` is: `newestMtimeMs` skips a path that does not exist, so
 * an entry that was renamed reads as a service nothing can make stale, and the run says "nothing to
 * do" over a jar built before the change.
 */
export function provisionedSources(): Array<{
  service: string;
  mavenModule?: string;
  sources: string[];
}> {
  return PROVISIONED.map(({ service, mavenModule, sources }) => ({ service, mavenModule, sources }));
}

export interface ContainerState {
  id: string;
  running: boolean;
  /** `healthy`, `starting`, `unhealthy`, or `none` when the service declares no healthcheck. */
  health: string;
  imageId: string;
  /** When this container was created, which is when it last read its configuration. */
  createdMs: number;
}

async function containerState(name: string): Promise<ContainerState | null> {
  // `{{json .}}` rather than a field list: a Go template that reads `.State.Health` alongside
  // `.Id` fails outright on a container that declares no healthcheck, and consul and ui-proxy do
  // not declare one.
  const out = await capture("docker", ["inspect", "--type", "container", "-f", "{{json .}}", name]).catch(
    () => null,
  );
  if (!out) return null;
  const inspected = JSON.parse(out) as {
    Id: string;
    Image: string;
    Created: string;
    State: { Running: boolean; Health?: { Status: string } };
  };
  return {
    id: inspected.Id,
    running: inspected.State.Running,
    health: inspected.State.Health?.Status ?? "none",
    imageId: inspected.Image,
    createdMs: Date.parse(inspected.Created),
  };
}

function isUp(state: ContainerState | null): boolean {
  return !!state && state.running && (state.health === "healthy" || state.health === "none");
}

async function imageCreatedMs(ref: string): Promise<number | null> {
  const out = await capture("docker", ["image", "inspect", "-f", "{{.Created}}", ref]).catch(() => null);
  if (!out) return null;
  const ms = Date.parse(out.trim());
  return Number.isNaN(ms) ? null : ms;
}

/** Whether anything under `paths` was written after the container read its configuration. */
async function newerThanContainer(
  root: string,
  paths: string[],
  state: ContainerState | null,
): Promise<boolean> {
  // An unparsable creation time answers `false` rather than recreating on every run: a recreate
  // nobody asked for costs a developer their debugging session.
  if (!state || !Number.isFinite(state.createdMs)) return false;
  return (await newestMtimeMs(root, paths)) > state.createdMs;
}

/**
 * The support containers created before the configuration they read, split by what a recreate costs.
 *
 * `states` is what `docker inspect` answered for each of them, so the file system is the only thing
 * this reaches — the spec that pins it hands over a synthetic creation time.
 *
 * Health is not consulted, and a gate on it was the defect this function exists to hold shut. A
 * container that is not up is started rather than recreated, and `docker compose up -d` starts an
 * existing container with the mounts it already has: postgres runs `/docker-entrypoint-initdb.d`
 * only against an empty data directory, so a schema added under `infrastructure/init-db` stays inert
 * until the container is replaced. That is the sharpest case the notice exists for, and gating this
 * reading on `isUp` made it the one case the notice could never report.
 */
export async function supportStaleness(
  root: string,
  states: ReadonlyMap<string, ContainerState | null>,
): Promise<{ recreate: string[]; report: string[] }> {
  const recreate: string[] = [];
  const report: string[] = [];
  for (const each of SUPPORT) {
    // A container that does not exist is created by this run and reads the configuration as it
    // stands, which is the `false` `newerThanContainer` answers for a null state.
    const state = states.get(each.container) ?? null;
    if (!(await newerThanContainer(root, [composeFile(), ...each.config], state))) continue;
    (each.recreatable ? recreate : report).push(each.service);
  }
  return { recreate, report };
}

/**
 * Wait for a service to report healthy.
 *
 * On health rather than on a timer, and with a budget that fits a cold start: measured, a service
 * takes 30-40 s to come up and OpenSearch longer.
 */
async function waitUp(containers: string[], budgetMs = 300_000): Promise<void> {
  const pending = new Set(containers);
  await pollUntil(
    budgetMs,
    2000,
    async () => {
      for (const container of [...pending]) {
        if (isUp(await containerState(container))) pending.delete(container);
      }
      return pending.size ? [...pending].sort().join(", ") : null;
    },
    (last) => `not healthy within ${Math.round(budgetMs / 1000)}s: ${last}`,
  );
}

export async function provisionCompose(mode: ProvisionMode): Promise<ProvisionReport> {
  const startedAt = Date.now();
  const root = repoRoot();
  const env = new ComposeEnv();
  const report = emptyProvisionReport(mode);

  const required = [...PROVISIONED, ...SUPPORT];
  const before = new Map<string, ContainerState | null>();
  for (const each of required) before.set(each.container, await containerState(each.container));
  const isDown = (each: { container: string }) => !isUp(before.get(each.container) ?? null);

  if (mode === "never") {
    // Read here too, and acted on nowhere. Every record is reported rather than only the ones
    // `PROVISIONED` can recreate: `never` is not going to recreate any of them, and a record naming
    // a service this table does not carry is the one a reader is least likely to work out alone.
    for (const [role, override] of Object.entries(await readOverrides())) {
      const service = PROVISIONED.find((each) => each.container === CONTAINER[role as ServiceRole]);
      console.error(overrideNotice(service?.service ?? role, override));
    }

    // The same reading `auto` makes below, acted on nowhere, for the reason the overrides above are
    // read here: this mode recreates nothing, and a run against a container the checkout has moved
    // past fails pointing at files the developer never touched. The recreatable ones are named on a
    // line of their own, because the notice the list carries is about data a recreate would discard
    // and the proxy holds none.
    const support = await supportStaleness(root, before);
    report.staleSupport = support.report;
    for (const service of support.recreate) {
      console.error(
        `[provision] ${service} was created before the compose file or a path it mounts changed, ` +
          `and E2E_PROVISION=never recreates nothing. Recreate it by hand if the change concerns ` +
          `it: docker compose up -d --force-recreate ${service}`,
      );
    }

    const down = required.filter(isDown);
    if (down.length) {
      throw new Error(
        `E2E_PROVISION=never and these are missing or unhealthy: ` +
          `${down.map((each) => each.container).sort().join(", ")}. ` +
          `Start them with "docker compose -f ${composeFile()} up -d", or unset E2E_PROVISION ` +
          `to let the suite provision.`,
      );
    }
    report.untouched = required.map((each) => each.service).sort();
    report.durationMs = Date.now() - startedAt;
    return report;
  }

  // Staleness is decided per service, against the image the container is actually running. With no
  // container, fall back to the image compose would have tagged; with neither, the service is built.
  const project = path.basename(path.dirname(composeFile()));
  const stale: Provisionable[] = [];
  const reconfigured: Provisionable[] = [];
  for (const candidate of PROVISIONED) {
    const state = before.get(candidate.container) ?? null;
    const created = await imageCreatedMs(state?.imageId ?? `${project}-${candidate.service}`);
    if (created === null || (await newestMtimeMs(root, candidate.sources)) > created) {
      stale.push(candidate);
      continue;
    }
    // Against the container's creation time rather than the image's, and only for a container that
    // exists: one the run is about to start reads the configuration as it stands.
    if (await newerThanContainer(root, configOf(candidate), state)) reconfigured.push(candidate);
  }

  // The same question for the containers the suite starts but does not own, asked of the compose
  // file and of the files it bind-mounts into them, where the answer splits by what a recreate
  // costs. The proxy holds nothing, so it is recreated like an application service. The other three
  // hold this stack's data in the container itself, so the change is reported and left for a
  // person: a recreate that discards a developer's chains or a run's sessions is a worse outcome
  // than a container running an older port mapping.
  const support = await supportStaleness(root, before);
  const recreatableSupport = support.recreate;
  report.staleSupport = support.report;

  // A `restartWith` that was never undone — a run killed between the restart and its teardown —
  // leaves a container running settings no file carries, so staleness above cannot see it. The
  // record `restartWith` wrote is the handle: recreating the service from the committed
  // configuration is what puts it back.
  const overridden = await readOverrides();
  const toRestore: ServiceRole[] = [];
  for (const [role, override] of Object.entries(overridden) as [ServiceRole, ServiceOverride][]) {
    const candidate = PROVISIONED.find((each) => each.container === CONTAINER[role]);
    if (candidate === undefined) continue;
    console.log(`[provision] clearing ${describeOverride(candidate.service, override)}`);
    toRestore.push(role);
    if (!reconfigured.includes(candidate) && !stale.includes(candidate)) {
      reconfigured.push(candidate);
    }
  }

  const rebuild = stale.map((each) => each.service);
  const recreate = [...reconfigured.map((each) => each.service), ...recreatableSupport];
  // Start only what is missing or unhealthy. A running container that is already current is never
  // recreated: a developer may be mid-debug on this stack.
  const start = required
    .filter(
      (each) =>
        !rebuild.includes(each.service) && !recreate.includes(each.service) && isDown(each),
    )
    .map((each) => each.service);

  const modules = stale.map((each) => each.mavenModule).filter((each): each is string => !!each);
  if (modules.length) {
    // `mvn install` first, then `--build`. The Dockerfiles copy a jar the host already built, so
    // `--build` on its own repackages a stale binary and the run tests a commit nobody chose.
    await mavenInstall(modules);
    report.built = modules;
  }

  const file = composeFile();
  if (rebuild.length) {
    console.log(`[provision] docker compose up --build: ${rebuild.join(", ")}`);
    await stream("docker", ["compose", "-f", file, "up", "-d", "--build", ...rebuild], root);
    report.rebuilt = rebuild;
  }
  if (recreate.length) {
    // `--force-recreate`, and no Maven: the image is current and only the environment the container
    // holds is not. Compose reads the changed file when it builds the new container.
    console.log(`[provision] docker compose up --force-recreate: ${recreate.join(", ")}`);
    await stream(
      "docker",
      ["compose", "-f", file, "up", "-d", "--force-recreate", ...recreate],
      root,
    );
    report.recreated = recreate;
  }
  if (start.length) {
    console.log(`[provision] docker compose up: ${start.join(", ")}`);
    await stream("docker", ["compose", "-f", file, "up", "-d", ...start], root);
    report.started = start;
  }

  await waitUp(required.map((each) => each.container));

  // Only once the recreated containers are up, and never before: a record dropped over a recreate
  // that failed would hand the next run a stack it no longer knows is dirty.
  for (const role of toRestore) await forgetOverride(role);

  // Which containers actually changed, rather than which ones were named on a command line: `up -d`
  // on a current container is a no-op, and the proxy only cares about the difference. A container
  // that was merely stopped counts too, because Docker hands out its address again on start.
  const changed: string[] = [];
  for (const each of required) {
    const after = await containerState(each.container);
    if (after?.id !== before.get(each.container)?.id || isDown(each)) changed.push(each.service);
  }
  // The proxy's configuration is bind-mounted (`./nginx:/etc/nginx/`), so a change there is read by
  // a reload rather than by a recreate — and it has to be read even on a run where no container
  // changed at all.
  report.proxyConfigChanged = await newerThanContainer(
    root,
    ["infrastructure/nginx"],
    before.get(PROXY_CONTAINER) ?? null,
  );
  if (changed.length || report.proxyConfigChanged) {
    await env.reloadProxy();
    report.proxyReloaded = true;
  }

  report.untouched = required
    .map((each) => each.service)
    .filter((service) => !changed.includes(service))
    .sort();
  report.durationMs = Date.now() - startedAt;
  return report;
}
