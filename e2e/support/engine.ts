/**
 * The engine's own REST surface, on its own port.
 *
 * One of the suite's four transports, beside `catalog.ts`, `sessions.ts` and `testing-service.ts`,
 * and it exists for the same reason they do: `noteReached()` fires inside a transport and nowhere
 * else, so an engine endpoint called through the raw `request` fixture records nothing however many
 * rows it touches. Until this module landed, all six engine rows in `registry/operations.ts` were
 * unverifiable in both directions and the registry said so in a comment.
 *
 * It stays deliberately small: one method per engine operation a spec asserts. The failed-sessions
 * list and the two retries belong to `specs/runtime/checkpoint-retry.spec.ts`.
 *
 * The engine is addressed **directly**, never through nginx. `specs/api/api-prefixes.spec.ts` and
 * `specs/env/restart-resilience.spec.ts` go through the proxy on purpose, because the proxy prefix
 * is what those two assert; a proxied path matches no registry row, so neither records anything and
 * neither should.
 */
import type { APIRequestContext, APIResponse } from "@playwright/test";
import { serviceUrl } from "../env/containers.js";
import { noteReached } from "../registry/reached.js";
import { fail } from "./catalog.js";

/** `LiveExchangeDTO` on the engine side, narrowed to what a spec reads. */
export interface LiveExchange {
  exchangeId: string;
  deploymentId: string;
  sessionId: string;
  chainId: string;
  /** How long this exchange has been running, in ms. Null while the start property is unset. */
  duration: number | null;
  sessionDuration: number | null;
  sessionStartTime: number | null;
  sessionLogLevel: string;
  main: boolean;
}

/** One saved checkpoint of a session, as `GET /v1/engine/sessions` reports it. */
export interface EngineCheckpoint {
  id: string;
  checkpointElementId: string;
  timestamp: string;
}

/** `CheckpointSessionDTO`: a session the engine kept because it can be retried. */
export interface CheckpointSession {
  id: string;
  started: string;
  finished: string;
  duration: number;
  executionStatus: string;
  chainId: string;
  chainName: string;
  engineAddress: string;
  loggingLevel: string;
  snapshotName: string;
  correlationId: string | null;
  checkpoints: EngineCheckpoint[];
}

/** `CheckpointPayloadOptions`, the part a case sends: headers a retry replaces in the context it restores. */
export interface CheckpointReplace {
  headers?: Record<string, string>;
}

export class Engine {
  // Plain fields rather than constructor parameter properties, for the same reason `Catalog` uses
  // them: `node --experimental-strip-types` rejects those outright.
  private readonly api: APIRequestContext;
  private readonly base: string;

  constructor(api: APIRequestContext, base: string = serviceUrl("engine")) {
    this.api = api;
    this.base = base;
  }

  private async send(method: string, path: string, data?: object): Promise<APIResponse> {
    const url = `${this.base}${path}`;
    noteReached("engine", method, url);
    return await this.api.fetch(url, { method: method.toUpperCase(), ...(data === undefined ? {} : { data }) });
  }

  /** The raw response, for a spec asserting a status or a failure body. */
  raw(method: string, path: string): Promise<APIResponse> {
    return this.send(method, path);
  }

  /**
   * The exchanges this engine has in flight, or an empty list.
   *
   * **204, not an empty array**, when nothing is running: `LiveExchangesController` answers
   * `noContent()` for an empty result, so a caller reading `response.json()` gets a parse error
   * rather than `[]`. That is the whole reason this method exists rather than a bare `call`.
   */
  async liveExchanges(): Promise<LiveExchange[]> {
    const response = await this.send("get", "/v1/engine/live-exchanges");
    if (response.status() === 204) return [];
    if (!response.ok()) await fail(response, "GET", `${this.base}/v1/engine/live-exchanges`);
    return (await response.json()) as LiveExchange[];
  }

  /**
   * Asks the engine to terminate one exchange.
   *
   * The raw response rather than a void, because the failure path is half the contract: the engine
   * answers **404** for a deployment it is not running and for an exchange that is not in flight,
   * which is what the catalog in front of it turns into a 500.
   */
  killExchange(deploymentId: string, exchangeId: string): Promise<APIResponse> {
    return this.send("delete", `/v1/engine/live-exchanges/${deploymentId}/${exchangeId}`);
  }

  /**
   * The sessions the engine kept for a retry, **by id**.
   *
   * It is a lookup and not a listing, whatever the operation description says: the service is
   * `sessionInfoRepository.findAllById(ids)`, so no `ids` means no rows even while the table holds
   * some. `specs/global/engines.spec.ts` pins both halves of that.
   */
  checkpointSessions(ids: string[] = []): Promise<CheckpointSession[]> {
    const query = ids.length === 0 ? "" : `?ids=${ids.map(encodeURIComponent).join(",")}`;
    return this.call(`/v1/engine/sessions${query}`);
  }

  /** The sessions of one chain that failed with a checkpoint to retry from. */
  failedSessions(chainId: string): Promise<CheckpointSession[]> {
    return this.call(`/v1/engine/chains/${encodeURIComponent(chainId)}/sessions/failed`);
  }

  /**
   * Retries a session from its **latest** checkpoint, not from the trigger.
   *
   * The raw response: the engine answers 202 before the retry runs, and 404 naming the session when
   * it holds no checkpoint for it, which is the answer a caller has to read rather than wait past.
   */
  retrySession(chainId: string, sessionId: string, replace: CheckpointReplace = {}): Promise<APIResponse> {
    return this.send("post", `/v1/engine/chains/${encodeURIComponent(chainId)}/sessions/${encodeURIComponent(sessionId)}/retry`, replace);
  }

  /** Retries a session from the checkpoint given. Answers like `retrySession`. */
  retryFromCheckpoint(chainId: string, sessionId: string, checkpointElementId: string, replace: CheckpointReplace = {}): Promise<APIResponse> {
    return this.send(
      "post",
      `/v1/engine/chains/${encodeURIComponent(chainId)}/sessions/${encodeURIComponent(sessionId)}/checkpoint-elements/${encodeURIComponent(checkpointElementId)}/retry`,
      replace,
    );
  }

  private async call<T>(path: string): Promise<T> {
    const response = await this.send("get", path);
    if (!response.ok()) await fail(response, "GET", `${this.base}${path}`);
    return (await response.json()) as T;
  }
}
