import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import fs from "node:fs/promises";
import os from "node:os";
import type {
  DomainFacts,
  Env,
  Overlay,
  ProcessEnvelope,
  ResourcePeak,
  ServiceRole,
} from "./index.js";
import { apiPath, CHAIN_ROUTE_PREFIX } from "./api-routes.js";
import { CONTAINER, PROXY_CONTAINER } from "./compose-containers.js";
import { OVERLAY_NAMES, proxyUrl, serviceUrl } from "./containers.js";
import { awaitOverlayReady, hostReady } from "./overlays.js";
import { composeProcessEnvelope, readPeaks } from "./resources.js";
import { pollUntil, repoRoot } from "./host.js";
import { forgetOverride, recordOverride } from "./overrides.js";

const run = promisify(execFile);

/** Exported because the provisioner names the same file, and one definition is enough. */
export function composeFile(): string {
  return (
    process.env.CIP_COMPOSE_FILE ?? path.resolve(repoRoot(), "infrastructure/docker-compose.yml")
  );
}

/** What one optional overlay is, and how the adapter tells that it has started serving. */
export interface OverlayDefinition {
  name: Overlay;
  /** The overlay's compose file, alongside `docker-compose.yml`. */
  file: string;
  /** Compose service names the overlay adds, so `up -d` starts only them and not the base stack. */
  services: string[];
  /**
   * The one service that is the broker itself, for `restartOverlay` to restart alone. Every overlay
   * the suite starts is that one service today; the Kafka file also declares `akhq`, a UI the suite
   * does not start.
   */
  primary: string;
  /** Resolves to `null` once the overlay is serving, or a description of what is still missing. */
  ready(): Promise<string | null>;
}

/** The named container's own `Health.Status`, for an overlay whose compose file already declares one. */
function healthcheckReady(container: string): () => Promise<string | null> {
  return async () => {
    const { stdout } = await run("docker", [
      "inspect", "-f", "{{.State.Health.Status}}", container,
    ]).catch(() => ({ stdout: "" }));
    const status = stdout.trim();
    return status === "healthy" ? null : status || "no status";
  };
}

/**
 * Every optional overlay the adapter knows how to bring up, keyed by the name a spec passes to
 * `Env.ensureOverlay`.
 *
 * `file` names are relative to the directory `docker-compose.yml` sits in, so the overlay is always
 * started together with the base file under one Compose project — the overlays declare no network
 * of their own, and started standalone the broker would land on a network the running stack cannot
 * reach.
 */
const OVERLAYS: Record<Overlay, OverlayDefinition> = {
  kafka: {
    name: "kafka",
    file: "docker-compose.kafka.yml",
    services: ["kafka"],
    primary: "kafka",
    ready: hostReady("kafka"),
  },
  rabbitmq: {
    name: "rabbitmq",
    file: "docker-compose.rabbitmq.yml",
    services: ["rabbitmq"],
    primary: "rabbitmq",
    ready: healthcheckReady("rabbitmq"),
  },
  pubsub: {
    name: "pubsub",
    file: "docker-compose.pubsub.yml",
    services: ["pubsub"],
    primary: "pubsub",
    ready: hostReady("pubsub"),
  },
  sftp: {
    name: "sftp",
    file: "docker-compose.sftp.yml",
    services: ["sftp-server"],
    primary: "sftp-server",
    ready: hostReady("sftp"),
  },
};

/**
 * The overlay `name` names, or a failure listing every overlay the adapter knows.
 *
 * Thrown synchronously, before `ensureOverlay` runs a single Compose command, so a typo in an
 * overlay name fails the run immediately rather than through a deployment that sits at `PROCESSING`
 * and retries against a broker nothing ever started. No stack is needed to reach this function,
 * which is what `specs/schema/env-overlay.spec.ts` proves it.
 */
export function overlayDefinition(name: string): OverlayDefinition {
  const definition = OVERLAYS[name as Overlay];
  if (!definition) {
    throw new Error(`no overlay named ${JSON.stringify(name)}. Known overlays: ${OVERLAY_NAMES.join(", ")}`);
  }
  return definition;
}

export class ComposeEnv implements Env {
  readonly name = "compose";
  readonly generatedRequestId = null;

  // A plain field rather than a parameter property: `node --experimental-strip-types` rejects
  // those, and `provision.ts` reaches this class from a step that runs under it.
  private readonly composeFilePath: string;

  constructor(composeFilePath: string = composeFile()) {
    this.composeFilePath = composeFilePath;
  }

  url(service: ServiceRole): string {
    return serviceUrl(service);
  }

  apiUrl(service: ServiceRole, servicePath: string): string {
    return `${proxyUrl()}${apiPath(service, servicePath)}`;
  }

  chainUrl(contextPath: string): string {
    const route = contextPath.startsWith("/") ? contextPath : `/${contextPath}`;
    return `${this.url("engine")}${CHAIN_ROUTE_PREFIX}${route}`;
  }

  uiUrl(page: string): string {
    return `${proxyUrl()}${page.startsWith("/") ? page : `/${page}`}`;
  }

  async logs(service: ServiceRole, since: string): Promise<string> {
    const { stdout, stderr } = await run("docker", [
      "logs", CONTAINER[service], "--since", since,
    ], { maxBuffer: 64 * 1024 * 1024 });
    return stdout + stderr;
  }

  engineLogs(since: string): Promise<string> {
    return this.logs("engine", since);
  }

  async settings(service: ServiceRole): Promise<Record<string, string>> {
    const { stdout } = await run("docker", [
      "inspect", "-f", "{{range .Config.Env}}{{println .}}{{end}}", CONTAINER[service],
    ]);
    return Object.fromEntries(
      stdout.split("\n").filter(Boolean).map((line) => {
        const at = line.indexOf("=");
        return [line.slice(0, at), line.slice(at + 1)];
      }),
    );
  }

  async restartWith(service: ServiceRole, settings: Record<string, string>): Promise<void> {
    // A throwaway override rather than an edit of the committed env file: qip-dev.env is read by
    // three services, so changing it there recreates three containers instead of one.
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), "qip-e2e-"));
    const override = path.join(dir, "override.yml");
    const body = Object.entries(settings)
      .map(([k, v]) => `      ${k}: ${JSON.stringify(String(v))}`)
      .join("\n");
    await fs.writeFile(
      override,
      `services:\n  ${CONTAINER[service]}:\n    environment:\n${body}\n`,
    );
    // Recorded before the recreate rather than after it, for the reason the callers set their own
    // flag before the await: the container is replaced first and the health wait can still throw,
    // and a settings change nothing recorded is one no later run can undo.
    await recordOverride(service, settings);
    try {
      await this.recreate(service, override);
    } finally {
      await fs.rm(dir, { recursive: true, force: true });
    }
  }

  async restart(service: ServiceRole): Promise<void> {
    await this.recreate(service);
    // After the recreate: a restart that threw leaves the override in force, and the record is
    // what tells the next run to clear it.
    await forgetOverride(service);
  }

  async reloadProxy(): Promise<void> {
    await run("docker", ["exec", PROXY_CONTAINER, "nginx", "-s", "reload"]);
    // The reload is asynchronous: nginx signals the master and returns before the new workers have
    // resolved anything, so poll a route that only answers once an upstream is reachable. The probe
    // is built from `apiPath`, so it moves with the proxy's routing table rather than beside it.
    const probe = this.apiUrl("runtime-catalog", "/v1/folders");
    await pollUntil(
      60_000,
      1_000,
      async () => {
        const status = await fetch(probe)
          .then((response) => response.status)
          .catch(() => 0);
        return status === 200 ? null : `HTTP ${status}`;
      },
      (last) => `${PROXY_CONTAINER} did not serve ${probe} after a reload (${last})`,
    );
  }

  processEnvelope(service: ServiceRole): Promise<ProcessEnvelope> {
    return composeProcessEnvelope(service);
  }

  async address(service: ServiceRole): Promise<string> {
    const { stdout } = await run("docker", [
      "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", CONTAINER[service],
    ]);
    return stdout.trim();
  }

  /**
   * What the sampler `globalSetup` started recorded, or nothing at all.
   *
   * An empty answer is a fact rather than a failure here: a run started with the sampler off, or a
   * `--no-deps` re-run of one case, has no peaks to report. The spec is what decides that an empty
   * reading is worth failing over.
   */
  async resourcePeaks(): Promise<ResourcePeak[]> {
    return readPeaks();
  }

  domainFacts(): DomainFacts {
    return { listsEnginePods: false };
  }

  /** `-f` arguments merging the overlay's own file with the base one, under one Compose project. */
  private overlayFiles(definition: OverlayDefinition): string[] {
    return ["-f", this.composeFilePath, "-f", this.overlayFile(definition)];
  }

  private overlayFile(definition: OverlayDefinition): string {
    return path.resolve(path.dirname(this.composeFilePath), definition.file);
  }

  async ensureOverlay(overlay: Overlay): Promise<void> {
    const definition = overlayDefinition(overlay);
    const files = this.overlayFiles(definition);
    // `provisionCompose()` reduces `E2E_PROVISION=never` to a health check for the base stack — the
    // mode a stack somebody else is debugging runs under, per `e2e/README.md`'s own flag table.
    // Without this check, `ensureOverlay` ran `docker compose up -d` regardless, which is the one provisioning
    // step that promise did not actually cover. `provisionMode()` already validated the raw value
    // once, in `globalSetup`, before any project — including this one — started.
    if (process.env.E2E_PROVISION === "never") {
      const problem = await definition.ready();
      if (problem) {
        throw new Error(
          `E2E_PROVISION=never and the ${definition.name} overlay is not serving: ${problem}. Start ` +
            `it with "docker compose -f ${this.composeFilePath} -f ${this.overlayFile(definition)} up -d ` +
            `${definition.services.join(" ")}", or unset E2E_PROVISION to let the suite provision it.`,
        );
      }
      return;
    }
    // `up -d` alone, never `--force-recreate`: a container that is already running and current is
    // left exactly as it is, the same rule provisioning follows for the base stack. Only the
    // overlay's own services are named, so a merge with the base file starts nothing it already owns.
    await run(
      "docker",
      ["compose", ...files, "up", "-d", ...definition.services],
      { maxBuffer: 16 * 1024 * 1024 },
    );
    await awaitOverlayReady(overlay, definition.ready, "start serving");
  }

  async restartOverlay(overlay: Overlay): Promise<void> {
    const definition = overlayDefinition(overlay);
    const files = this.overlayFiles(definition);
    // Read before the restart, so a caller that only asserts "consumption still works" is not the
    // only thing standing between this function and a silent no-op — a `restart` swapped for an
    // idempotent `up -d` on an already-healthy container would satisfy every downstream spec without
    // the broker ever having gone away.
    const before = await this.containerStartedAt(files, definition.primary);
    // `restart`, never `up -d --force-recreate`: this is meant to drop the connection a trigger
    // already holds, not replace the container or lose whatever topics and queues are declared on
    // it. Only the broker itself, not the overlay's other services — see `primary`'s own comment.
    await run(
      "docker",
      ["compose", ...files, "restart", definition.primary],
      { maxBuffer: 16 * 1024 * 1024 },
    );
    await awaitOverlayReady(overlay, definition.ready, "resume serving after a restart");
    const after = await this.containerStartedAt(files, definition.primary);
    if (before && after && before === after) {
      throw new Error(
        `overlay ${definition.name}'s ${definition.primary} container answered ready without its ` +
          `process actually restarting (State.StartedAt stayed ${after}) — restartOverlay proved ` +
          `nothing a spec's own assertions would not have proved against an undisturbed broker`,
      );
    }
  }

  async removeOverlay(overlay: Overlay): Promise<void> {
    // A stack somebody else is debugging keeps its brokers, the way `ensureOverlay` starts nothing there.
    if (process.env.E2E_PROVISION === "never") return;
    const definition = overlayDefinition(overlay);
    await run(
      "docker",
      ["compose", ...this.overlayFiles(definition), "rm", "--stop", "--force", ...definition.services],
      { maxBuffer: 16 * 1024 * 1024 },
    );
  }

  /** `State.StartedAt` of the compose `service`'s container, or `""` for one that is not running. */
  private async containerStartedAt(files: string[], service: string): Promise<string> {
    const { stdout: id } = await run("docker", ["compose", ...files, "ps", "-q", service]);
    const containerId = id.trim();
    if (!containerId) return "";
    const { stdout } = await run("docker", ["inspect", "-f", "{{.State.StartedAt}}", containerId]).catch(
      () => ({ stdout: "" }),
    );
    return stdout.trim();
  }

  private async recreate(service: ServiceRole, override?: string): Promise<void> {
    const files = ["-f", this.composeFilePath];
    if (override) files.push("-f", override);
    // --no-deps is the guard on postgres, which mounts no named volume and holds every row this
    // run has created. Compose leaves a `depends_on` container alone under `--force-recreate`
    // anyway — recreating a dependency takes `--always-recreate-deps` — so this narrows the command
    // to what it means rather than undoing something Compose would otherwise do.
    await run("docker", [
      "compose", ...files, "up", "-d", "--no-deps", "--force-recreate", CONTAINER[service],
    ], { maxBuffer: 16 * 1024 * 1024 });
    await this.waitHealthy(service);
    // A recreate is exactly what strands the proxy on the old container address, so the reload is
    // part of the restart rather than something a spec has to remember.
    await this.reloadProxy();
  }

  private async waitHealthy(service: ServiceRole): Promise<void> {
    await pollUntil(
      180_000,
      2_000,
      async () => {
        const { stdout } = await run("docker", [
          "inspect", "-f", "{{.State.Health.Status}}", CONTAINER[service],
        ]).catch(() => ({ stdout: "" }));
        const status = stdout.trim();
        return status === "healthy" ? null : status || "no status";
      },
      (last) => `${CONTAINER[service]} did not become healthy within 180s (${last})`,
    );
  }
}
