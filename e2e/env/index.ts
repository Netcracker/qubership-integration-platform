/**
 * The seam between a spec and the thing that runs the platform.
 *
 * A spec never names docker or kubectl. It asks the environment for a base URL, for a service's
 * log, or for a service restarted under different settings, and the implementation behind this
 * interface decides how that happens. Without the seam the suite welds itself to Docker Compose
 * and cannot be pointed at a cluster, which is where micro-engine lives.
 */
export interface Env {
  readonly name: string;
  /**
   * The `X-Request-Id` a chain's outbound HTTP call arrives with when the chain sends none, as a
   * regular expression, or `null` when the call arrives without one.
   *
   * On Kubernetes the call leaves through the engine's Istio sidecar, and Envoy generates a UUID
   * for a request that has no request id. Compose puts no proxy on that path.
   */
  readonly generatedRequestId: string | null;
  /**
   * Base URL of a platform service on its own port, by role rather than by container or pod name.
   *
   * Direct, and deliberately so for the callers that need it: the session lookup addresses
   * sessions-management by design, and the operation registry reads each service's `/v3/api-docs`
   * where the service serves it rather than where one proxy rule happens to expose it.
   */
  url(service: ServiceRole): string;
  /**
   * The same request through the `/api/` surface the UI uses, with the prefix table applied.
   *
   * Two resolvers rather than one, because collapsing them is what produced the measured failure in
   * `env/api-routes.ts`: a spec cannot build an `/api/` path by concatenation, and the form the UI
   * builds answers 404 for `systems` and `common-variables`.
   */
  apiUrl(service: ServiceRole, servicePath: string): string;
  /**
   * Where a deployed chain answers. The engine directly, never the proxy.
   *
   * No nginx `location` matches `/routes/`, so the request falls through to the SPA and answers
   * 200 with `index.html`. A runtime spec calling a chain through the proxy cannot fail.
   */
  chainUrl(contextPath: string): string;
  /**
   * A page of the UI, as a link a person opens.
   *
   * The proxy on 8080, never port 4200: the UI reaches the services through nginx, so a page opened
   * on 4200 has no data behind it. The `ui` project's `baseURL` is the same proxy, read from
   * `proxyUrl()` in `env/containers.ts` because the config cannot build an `Env`. On a cluster
   * target, `QIP_PROXY_URL` moves both to the proxy's NodePort, 30080.
   */
  uiUrl(page: string): string;
  /** Recent log of a service, for assertions that a run logged no exception. */
  logs(service: ServiceRole, since: string): Promise<string>;
  /**
   * Recent log of the engine this environment's chains run on: the `engine` role for `classic`,
   * the micro domain's pod for `micro`. What a failed case attaches.
   */
  engineLogs(since: string): Promise<string>;
  /** Restart a service with extra settings applied, and wait until it is serving again. */
  restartWith(service: ServiceRole, settings: Record<string, string>): Promise<void>;
  /** Restart a service with its committed settings, undoing a restartWith. */
  restart(service: ServiceRole): Promise<void>;
  /** Settings a service currently runs with, so a spec can refuse to start from a dirty state. */
  settings(service: ServiceRole): Promise<Record<string, string>>;
  /**
   * Reload the proxy that fronts the `/api/` surface, and wait until the reload has taken effect.
   *
   * Unconditional after any container is created or recreated. nginx resolves
   * `proxy_pass http://engine:8080` once, at config load, with no `resolver` in scope, so a
   * recreated backend leaves the proxy pointing at an address Docker has since handed to someone
   * else. Measured: after the engine and sessions-management containers were recreated,
   * `/api/v1/qip/engine/live-exchanges` answered 500 with a *Session Management* error body and
   * `/api/v1/qip/sessions-management/sessions` answered 404; one reload restored 204 and 200.
   */
  reloadProxy(): Promise<void>;
  /**
   * Whether a service is still the process it was, and how close its Metaspace is to its ceiling.
   *
   * Behind the seam for the reason that bites hardest here: a cluster has no `docker inspect` and
   * no `jcmd`, and "did the services survive the run" is exactly the question that has to be
   * answerable on both targets. Compose reads it from the container and the JVM inside it; a
   * cluster env reads the pod's restart count and an actuator or JMX gauge.
   */
  processEnvelope(service: ServiceRole): Promise<ProcessEnvelope>;
  /**
   * The IP address of the service's running process: the container's on Compose, the live pod's on
   * Kubernetes. The engine registers in Consul under this address.
   */
  address(service: ServiceRole): Promise<string>;
  /**
   * Peak memory and CPU each service reached over the run, from the sampler `globalSetup` started.
   *
   * A peak rather than a reading: measured, peak memory and peak CPU run at roughly twice the idle
   * value and the peak lasts seconds, so a single `docker stats` at the end of a run reports the
   * stack at rest and says nothing about what it did.
   */
  resourcePeaks(): Promise<ResourcePeak[]>;
  /**
   * Start an optional Compose overlay by name, composed with the base stack under one project, and
   * wait until it is serving. A spec never names `docker`, so this is the one seam through which the
   * `brokers` project brings up the broker it needs.
   *
   * Left alone when it is already up: the same rule provisioning follows for the base stack applies
   * here, and `docker compose up -d` with no `--force-recreate` already does that on its own. An
   * unknown overlay name fails immediately, before anything is started, naming every overlay the
   * adapter knows — a broker fixture deployed against an overlay that never came up would otherwise
   * sit at `PROCESSING` and retry silently until the seed's own poll times out.
   */
  ensureOverlay(overlay: Overlay): Promise<void>;
  /**
   * Restarts the overlay's own broker container — never recreated, never rebuilt — and waits until
   * it is serving again. `broker-restart.spec.ts` is the one caller: it proves a trigger's listener
   * reconnects on its own once the broker it was attached to comes back, which `ensureOverlay`'s
   * "leave a healthy broker alone" rule cannot exercise.
   *
   * A restart rather than a recreate, because a recreate is what `restartWith`/`restart` already do
   * for the platform's own services and it replaces the container — a broker restart is meant to
   * disturb the connection, not the broker's identity or the topics and queues already declared on
   * it.
   */
  restartOverlay(overlay: Overlay): Promise<void>;
  /**
   * How the catalog's domain listing differs on this target.
   *
   * Compose runs the catalog under the `development` profile, whose `DevModeDomainSource` declares
   * one synthetic classic domain and lists no pods. The chart runs it under `localdev`, where
   * `ClassicDomainSource` reads the engine Deployments and lists their pods. A spec compares the
   * listing with these facts rather than naming the target.
   */
  domainFacts(): DomainFacts;
}

/** How the catalog reports the classic domains on a target. */
export interface DomainFacts {
  /** Whether `GET /v1/catalog/domains/{d}/engines` lists the pods of a classic domain. */
  listsEnginePods: boolean;
}

/** An optional Compose overlay the `brokers` project can bring up beside the base stack. */
export type Overlay = "kafka" | "rabbitmq" | "pubsub" | "sftp";

/** Committed Metaspace and the ceiling it is committed against, in bytes. */
export interface Metaspace {
  usedBytes: number;
  maxBytes: number;
}

/** What a service's process is, in the terms a run can compare its start against its end. */
export interface ProcessEnvelope {
  role: ServiceRole;
  /** When the process started. A different value means this is not the process the run began with. */
  startedAt: string;
  /** How many times the orchestrator restarted it in place. */
  restarts: number;
  /** Null for a service with no JVM — the testing service is Go. */
  metaspace: Metaspace | null;
}

/** The peak one service reached, and how many samples that peak was taken from. */
export interface ResourcePeak {
  role: ServiceRole;
  memoryBytes: number;
  /**
   * Percent of **one** core, the way `docker stats` reports it: 400 is four cores saturated.
   *
   * Watching memory alone watches the wrong resource. Measured, the stack reached 94% of its 12
   * allocated cores under a load lighter than a full run while using 29% of its memory, so a suite
   * that grows hits the processor ceiling first.
   */
  cpuPercent: number;
  samples: number;
}

/**
 * The engine a project runs its chains on. `classic` is the Spring Boot engine; `micro` is the
 * Quarkus engine, which exists only on the Kubernetes target.
 */
export type EngineKind = "classic" | "micro";

export type ServiceRole =
  | "runtime-catalog"
  | "engine"
  | "sessions-management"
  | "testing-service";
