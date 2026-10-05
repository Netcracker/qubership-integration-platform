/**
 * The catalog calls the specs share, and the sharp edges the wrapper exists to absorb.
 *
 * Every one of these bit a fixture helper written without it, so each is encoded here once rather
 * than rediscovered per spec:
 *
 * - `PATCH /v1/chains/{c}/elements/{e}` replaces the property map **wholesale**. Patching
 *   `contextPath` alone answers `400 Value not found for accessControlType`, because the defaults
 *   the element was created with are gone. `patchElementProperties` round-trips the full map.
 * - A deployment needs an explicit snapshot first. Note **which** failure a JS client gets:
 *   `JSON.stringify({snapshotId: undefined})` drops the key and a body of `{}` answers
 *   **500 `The given id must not be null`** — not the `404 Can't find configuration with id
 *   undefined` a shell client produces by sending the literal string. `deploy` refuses an empty id
 *   before the call rather than teaching a spec to recognize either.
 * - Export and import are split across two prefixes: chains live under `/v1/catalog/export` and
 *   `/v1/catalog/import`, services under `/v1/export/system` and `/v1/import/system`. Neither
 *   answers on the other's path, and the chain export answered 404 on `/v1/export` for as long as
 *   nothing called it.
 * - `POST /v1/chains` is sent with `labels: []`. An earlier build answered 500 on the missing key,
 *   an NPE on `Chain.getLabels()`; on this build the same call answers 200, so the key is sent
 *   because it costs nothing and not because a spec should assert the old failure.
 *
 * One rule rather than an edge, and it is the client's rather than a spec's: **an endpoint that
 * answers 202 and is then polled is waited on by asserting the artifact, never the flag.** Almost
 * every defect this product produces is a case where it reports success — a gRPC import that
 * answers `done: true` with a null library id, an archive that imports nothing and reports 200 —
 * and a poll that stops at `done` cannot see any of them. `awaitSpecificationImport` is the whole
 * of that rule today, because the two 202-plus-poll operations the registry reaches
 * (`POST /v1/specificationGroups/import` and `POST /v1/import`) share one status endpoint. The
 * `/v2` and `/v3` import surfaces poll differently and have their own methods at the foot of this
 * class: `/v2` answers a **303** naming its result once it is done, `/v3` a session document.
 *
 * The client addresses the catalog on its own port. The `/api/` surface is a separate resolver on
 * `Env`, and which one a spec wants is a decision it should make explicitly.
 */
import type { APIRequestContext, APIResponse } from "@playwright/test";
import { noteReached } from "../registry/reached.js";
import { serviceUrl } from "../env/containers.js";
import { sleep } from "./poll.js";

/** The catalog on its own port, off the one table every addressed service is read from. */
function catalogUrl(): string {
  return serviceUrl("runtime-catalog");
}

/**
 * Every response shape the catalog answers with, re-exported so `support/catalog.js` stays the one
 * module a spec imports from. The shapes live in `support/catalog-types.ts`; see its header.
 */
export * from "./catalog-types.js";

import type {
  ActionLogFilter,
  ActionLogPage,
  ActionLogPageV2,
  BulkDeploymentRow,
  BulkSnapshotAction,
  CatalogItemV2,
  ChainDiff,
  ChainDifference,
  ChainDifferenceRequest,
  ChainElement,
  ChainElementSearch,
  ChainRolesPage,
  ChainRolesPath,
  ChainRolesUpdate,
  ChainView,
  ContextSystemFilter,
  ContextSystemView,
  CustomResourceMode,
  DeleteInstructionsRequest,
  DependencyView,
  DeploymentView,
  DeploymentsUpdate,
  DesignTemplateView,
  DetailedDesign,
  DiagnosticFilterRequest,
  DiagnosticValidation,
  DiagramMode,
  DiscoveredServiceView,
  DiscoveryResult,
  ElementWithChainName,
  EngineDeploymentView,
  EngineDomainView,
  EnginePodView,
  EnvironmentView,
  EventsUpdate,
  FilterRequest,
  FolderItem,
  FolderSearchItem,
  FolderView,
  ImportInstructionRequest,
  ImportInstructionResult,
  ImportInstructionView,
  ImportInstructions,
  ImportPreviewResult,
  ImportPreviewV3,
  ImportSessionSummaryV3,
  ImportSystemResult,
  ImportVariablePreview,
  ImportVariableResult,
  ImportVariablesResultV2,
  ImportedSpecification,
  LibraryElementsView,
  ListFolderRequestV2,
  LiveExchangeExtView,
  LiveExchangeFilter,
  LoggingProperties,
  LoggingPropertiesSet,
  MaskedFieldView,
  McpSystemView,
  MigratedChainView,
  Named,
  OperationDetail,
  OperationInfo,
  OperationSchemas,
  OperationView,
  RolloutImportRequest,
  RouteDeployment,
  RouteQuery,
  RuntimeDeployment,
  SecretView,
  SecuredVariableRef,
  SequenceDiagram,
  SnapshotFullV2,
  SnapshotView,
  SpecificationGroupView,
  SpecificationImportView,
  SpecificationView,
  StartedSpecificationImport,
  StringResponse,
  SystemUsage,
  SystemView,
  UsedProperty,
  UsedSystem,
} from "./catalog-types.js";
import { CHAIN_ROLES_PATHS, CLOCK_SKEW, DEFAULT_DOMAIN } from "./catalog-types.js";

/**
 * The multipart part both variables uploads take — the field is `file` on each.
 *
 * The type follows the extension because the importer reads the file name: the same endpoint takes
 * the exported YAML and the archive form of it, and tells them apart that way.
 */
function variablesUpload(name: string, buffer: Buffer) {
  return { name, mimeType: name.endsWith(".zip") ? "application/zip" : "application/x-yaml", buffer };
}

/** Copy, move and folder move all take the target as a query parameter, and all treat none as the root. */
function targetQuery(targetFolderId?: string): string {
  return targetFolderId ? `?targetFolderId=${encodeURIComponent(targetFolderId)}` : "";
}

/** Throws naming the call and the start of the answer. The engine client throws the same way. */
export async function fail(response: APIResponse, method: string, url: string): Promise<never> {
  const body = await response.text().catch(() => "<unreadable>");
  throw new Error(`${method} ${url} answered ${response.status()}: ${body.slice(0, 800)}`);
}

export class Catalog {
  // Plain fields rather than constructor parameter properties: `node --experimental-strip-types`
  // rejects those outright, and the after-the-run steps load this module outside Playwright.
  private readonly api: APIRequestContext;
  private readonly base: string;

  constructor(api: APIRequestContext, base: string = catalogUrl()) {
    this.api = api;
    this.base = base;
  }

  // -------------------------------------------------------------------------
  // Transport
  // -------------------------------------------------------------------------
  //
  // Every call goes out through `send`, and `send` records it against the operation registry, which
  // is why the registry's `reached` status is a fact about the run rather than a sentence somebody
  // typed. It belongs here and not in a spec: a declaration a spec has to remember to write is one
  // that goes stale, and the same holds one layer down — a method of this class that reaches
  // `this.api` directly records nothing, which is how eleven imports and exports came to claim
  // coverage no run could prove. The record is taken at the send rather than at the answer, because
  // `reconcile()` reads the annotations of passing tests only, so a call that never answered is
  // dropped by the reader instead of by the transport.

  /** Everything `fetch` takes except the method, which is this transport's own argument. */
  private async send(
    method: string,
    path: string,
    options: Omit<NonNullable<Parameters<APIRequestContext["fetch"]>[1]>, "method"> = {},
  ): Promise<APIResponse> {
    const url = `${this.base}${path}`;
    noteReached("catalog", method, url);
    return await this.api.fetch(url, { method: method.toUpperCase(), ...options });
  }

  /** A call that must succeed, returning parsed JSON. A 204 answers `undefined`. */
  async call<T>(method: "get" | "post" | "put" | "patch" | "delete", path: string, data?: unknown): Promise<T> {
    const response = await this.send(method, path, data === undefined ? {} : { data: data as object });
    if (!response.ok()) await fail(response, method.toUpperCase(), `${this.base}${path}`);
    const text = await response.text();
    return (text.length ? JSON.parse(text) : undefined) as T;
  }

  /** A GET whose body is bytes rather than JSON: every export goes through here. */
  async download(path: string): Promise<Buffer> {
    const response = await this.send("get", path);
    if (!response.ok()) await fail(response, "GET", `${this.base}${path}`);
    return Buffer.from(await response.body());
  }

  /** Whether the catalog holds what a GET of `path` reads: 200 is yes, 404 is no, and anything else throws. */
  async holds(path: string): Promise<boolean> {
    const response = await this.send("get", path);
    if (response.status() === 404) return false;
    if (!response.ok()) await fail(response, "GET", `${this.base}${path}`);
    return true;
  }

  /** The raw response, for a spec asserting a status or a failure body. */
  raw(method: string, path: string, data?: unknown): Promise<APIResponse> {
    return this.send(method, path, data === undefined ? {} : { data: data as object });
  }

  /**
   * The raw response of a call the shape above cannot make: a multipart upload, or a body that is
   * not JSON. A spec reaches for this rather than for its own `request` fixture, which would leave
   * the operation unrecorded.
   */
  upload(
    method: string,
    path: string,
    options: Omit<NonNullable<Parameters<APIRequestContext["fetch"]>[1]>, "method">,
  ): Promise<APIResponse> {
    return this.send(method, path, options);
  }

  // -------------------------------------------------------------------------
  // Folders
  // -------------------------------------------------------------------------

  createFolder(name: string, parentId?: string, description?: string): Promise<Named> {
    return this.call("post", "/v1/folders", { name, parentId, description });
  }

  /** Cascades: chains in the folder go with it, and with them everything chain-scoped. */
  async deleteFolder(id: string): Promise<void> {
    await this.call("delete", `/v1/folders/${id}`);
  }

  getFolder(id: string): Promise<FolderView> {
    return this.call("get", `/v1/folders/${id}`);
  }

  /**
   * Renames a folder, or re-parents it when `parentId` is given.
   *
   * Measured: a request omitting `parentId` leaves the folder where it is rather than lifting it
   * to the root, so a rename is a rename.
   */
  updateFolder(id: string, name: string, description?: string, parentId?: string): Promise<FolderView> {
    return this.call("put", `/v1/folders/${id}`, { name, description, parentId });
  }

  /** Re-parents a folder with everything under it. No target id means the root. */
  moveFolder(id: string, targetFolderId?: string): Promise<FolderView> {
    return this.call("post", `/v1/folders/${id}/move${targetQuery(targetFolderId)}`);
  }

  /** Every chain **beneath** the folder, nested ones included — not only its direct children. */
  listNestedChains(folderId: string): Promise<Named[]> {
    return this.call("get", `/v1/folders/${folderId}/chains`);
  }

  /** The folders and chains under a folder, the folder itself included. */
  listNestedElements(folderId: string): Promise<FolderItem[]> {
    return this.call("get", `/v1/folders/${folderId}/elements`);
  }

  listRootItems(): Promise<Array<Named & { itemType: string }>> {
    return this.call("get", "/v1/folders");
  }

  /**
   * The chains and folders a search condition matches, flat, plus every folder above them.
   *
   * A chain matches when any column `ChainFilterSpecificationBuilder.buildSearch` ORs together
   * contains the condition, a folder when its name does, and a matched folder brings in every chain
   * beneath it.
   */
  searchFolders(searchCondition: string): Promise<FolderSearchItem[]> {
    return this.call("post", "/v1/folders/search", { searchCondition });
  }

  /** The chains the filter clauses match, flat, plus every folder above them. Folders are not filtered. */
  filterFolders(filters: FilterRequest[]): Promise<FolderSearchItem[]> {
    return this.call("post", "/v1/folders/filter", filters);
  }

  // -------------------------------------------------------------------------
  // Chains
  // -------------------------------------------------------------------------

  createChain(name: string, parentId?: string, description?: string): Promise<Named> {
    return this.call("post", "/v1/chains", { name, description, parentId, labels: [] });
  }

  getChain(id: string): Promise<ChainView> {
    return this.call("get", `/v1/chains/${id}`);
  }

  listChains(): Promise<Named[]> {
    return this.call("get", "/v1/chains");
  }

  /**
   * Replaces the chain's own fields. `parentId` moves it, so a caller renaming a nested chain has
   * to send the folder it is already in or the chain lands in the root.
   */
  updateChain(id: string, name: string, description?: string, parentId?: string): Promise<Named> {
    return this.call("put", `/v1/chains/${id}`, { name, description, parentId, labels: [] });
  }

  /** A new chain in `targetFolderId`, elements and all. No target id means the root. */
  copyChain(id: string, targetFolderId?: string): Promise<Named & { parentId?: string }> {
    return this.call("post", `/v1/chains/${id}/copy${targetQuery(targetFolderId)}`);
  }

  /** A copy beside the original, named `<name> (1)`. */
  duplicateChain(id: string): Promise<Named & { parentId?: string }> {
    return this.call("post", `/v1/chains/${id}/duplicate`);
  }

  moveChain(id: string, targetFolderId?: string): Promise<Named & { parentId?: string }> {
    return this.call("post", `/v1/chains/${id}/move${targetQuery(targetFolderId)}`);
  }

  listChainElements(chainId: string): Promise<ChainElement[]> {
    return this.call("get", `/v1/chains/${chainId}/elements`);
  }

  async deleteChain(id: string): Promise<void> {
    await this.call("delete", `/v1/chains/${id}`);
  }

  /** The number of chains on the whole stack. */
  async chainsCount(): Promise<number> {
    return (await this.call<{ chainsCount: number }>("get", "/v1/chains/count")).chainsCount;
  }

  /** Chain id to name, for the ids the catalog holds. An unknown id is left out. */
  chainNames(ids: readonly string[]): Promise<Record<string, string>> {
    return this.call("get", `/v1/chains/names?chainIds=${ids.join(",")}`);
  }

  /** The chain an element belongs to, without its elements. */
  chainOfElement(elementId: string): Promise<Named & { parentId?: string }> {
    return this.call("get", `/v1/chains/find-by-element/${elementId}`);
  }

  /** The services and specifications the chains' elements reference. */
  usedSystems(chainIds: readonly string[]): Promise<UsedSystem[]> {
    return this.call("get", `/v1/chains/used-systems?chainIds=${chainIds.join(",")}`);
  }

  /** The element differences between two chains, two snapshots, or a chain and a snapshot. */
  compareChains(request: ChainDifferenceRequest): Promise<ChainDifference> {
    return this.call("post", "/v1/chains/diff", request);
  }

  /** Replaces the chain's deprecated containers with their successors. */
  migrateChain(chainId: string): Promise<MigratedChainView> {
    return this.call("patch", `/v1/chains/${chainId}/migrate`);
  }

  // -------------------------------------------------------------------------
  // Elements
  // -------------------------------------------------------------------------

  /** `POST .../elements` answers `{createdElements: [...]}`, not the element. */
  async createElement(
    chainId: string,
    type: string,
    into: { parentElementId?: string; swimlaneId?: string } = {},
  ): Promise<ChainElement> {
    const diff = await this.createElementDiff(chainId, type, into);
    const created = diff.createdElements?.[0];
    if (!created) throw new Error(`creating a ${type} in ${chainId} created no element`);
    return created;
  }

  /**
   * The same call, kept whole.
   *
   * A create is rarely only a create: putting a child in a container reports the container under
   * `updatedElements`, and the chain's **first** swimlane reports every top-level element it
   * adopted plus a `createdDefaultSwimlaneId`. A caller asserting either needs the diff.
   */
  createElementDiff(
    chainId: string,
    type: string,
    into: { parentElementId?: string; swimlaneId?: string } = {},
  ): Promise<ChainDiff> {
    return this.call("post", `/v1/chains/${chainId}/elements`, { type, ...into });
  }

  getElement(chainId: string, elementId: string): Promise<ChainElement> {
    return this.call("get", `/v1/chains/${chainId}/elements/${elementId}`);
  }

  /**
   * Moves elements into a container, into a swimlane, or into both.
   *
   * `parentId` decides the swimlane when it names a container that has one, so a request carrying
   * both is not a request that applies both: the container wins.
   */
  transferElements(
    chainId: string,
    request: { parentId?: string; swimlaneId?: string; elements: string[] },
  ): Promise<ChainDiff> {
    return this.call("post", `/v1/chains/${chainId}/elements/transfer`, request);
  }

  /** The plural delete, whose ids travel as a query parameter rather than as a body. */
  deleteElements(chainId: string, elementIds: string[]): Promise<ChainDiff> {
    const query = new URLSearchParams({ elementsIds: elementIds.join(",") });
    return this.call("delete", `/v1/chains/${chainId}/elements?${query}`);
  }

  /** The singular delete. `@Deprecated` on the controller, and still the only one the UI's canvas uses. */
  deleteElement(chainId: string, elementId: string): Promise<ChainDiff> {
    return this.call("delete", `/v1/chains/${chainId}/elements/${elementId}`);
  }

  /** Wraps elements in a `container`, answering the container rather than a diff. */
  groupElements(chainId: string, elementIds: string[]): Promise<ChainElement> {
    return this.call("post", `/v1/chains/${chainId}/elements/groups`, elementIds);
  }

  /** Removes the container and lifts its children to where it was. Answers the freed children. */
  ungroupElements(chainId: string, groupId: string): Promise<ChainElement[]> {
    return this.call("delete", `/v1/chains/${chainId}/elements/groups/${groupId}`);
  }

  /** Copies elements into a container. `parent` is required per request; the clones get new ids. */
  cloneElements(
    chainId: string,
    requests: Array<{ id: string; parent: string }>,
  ): Promise<ChainElement[]> {
    return this.call("post", `/v1/chains/${chainId}/elements/clone`, requests);
  }

  /** The chain's elements as the YAML document the code view edits. */
  async elementsCode(chainId: string): Promise<string> {
    return (await this.call<{ code: string }>("get", `/v1/chains/${chainId}/elements/code`)).code;
  }

  /** Writes that document back. `@Deprecated(forRemoval)` since 24.2, and still served. */
  saveElementsCode(chainId: string, code: string): Promise<ChainElement[]> {
    return this.call("post", `/v1/chains/${chainId}/elements/code`, { code });
  }

  /**
   * Elements of one type — in this chain, or across every chain.
   *
   * Which of the two is decided by whether `chainId` **parses as a UUID**
   * (`ElementService.java:199-206`): one that does is a chain-scoped query, and one that does not
   * falls through to `findAllByTypeInAndChainNotNull`, the platform-wide list the UI asks for by
   * passing a non-UUID in the path.
   */
  elementsOfType(chainId: string, type: string): Promise<ElementWithChainName[]> {
    return this.call("get", `/v1/chains/${chainId}/elements/type/${type}`);
  }

  /** The exchange properties the chain's scripts read and write, found by pattern. */
  usedProperties(chainId: string): Promise<UsedProperty[]> {
    return this.call("get", `/v1/chains/${chainId}/elements/properties/used`);
  }

  /**
   * Points http triggers at a specification group, which is what "implemented" means for a trigger.
   *
   * Answers **204**. Everything it does is to the elements' properties: `systemType` becomes
   * `IMPLEMENTED`, three ids are written, and **`contextPath` is set to null** — the trigger's path
   * now comes from the specification rather than from the element.
   *
   * `chainId` is `@Deprecated` on the controller and unused by the service; it stays in the path
   * because the mapping does.
   */
  async modifyHttpTriggerProperties(
    chainId: string,
    specificationGroupId: string,
    httpTriggerIds: string[],
  ): Promise<void> {
    const query = new URLSearchParams({
      specificationGroupId,
      httpTriggerIds: httpTriggerIds.join(","),
    });
    await this.call("put", `/v1/chains/${chainId}/elements/properties-modification?${query}`);
  }

  /**
   * Merges into the element's current properties instead of replacing them.
   *
   * The endpoint takes the map it is given as the whole map, so a caller that sends one key loses
   * every default the element was created with — and finds out through a validation error naming a
   * property it never touched.
   *
   * `parentElementId` is round-tripped for the same reason, and it is the sharper of the two:
   * `ElementController.patchElement` reads the request's `parentElementId` and re-parents the
   * element whenever it differs from the one it has, so a patch that omits the key **lifts a
   * nested element out of its container** to the chain's top level. The status is 200 either way.
   * `specs/api/elements.spec.ts` pins that as the endpoint's contract.
   *
   * It answers a diff rather than the element, and the element is the sole entry of
   * `updatedElements` unless the patch also re-parented it.
   */
  async patchElementProperties(
    chainId: string,
    elementId: string,
    properties: Record<string, unknown>,
    name?: string,
  ): Promise<ChainDiff> {
    const current = await this.getElement(chainId, elementId);
    return await this.call("patch", `/v1/chains/${chainId}/elements/${elementId}`, {
      name: name ?? current.name,
      type: current.type,
      parentElementId: current.parentElementId,
      properties: { ...current.properties, ...properties },
    });
  }

  // -------------------------------------------------------------------------
  // Element library
  // -------------------------------------------------------------------------

  /** The whole palette: groups, the ungrouped elements, and the child-only ones. */
  library(): Promise<LibraryElementsView> {
    return this.call("get", "/v1/library");
  }

  /**
   * One descriptor by name.
   *
   * `raw` rather than `call`, because the miss is half the contract: the controller answers
   * `ResponseEntity.notFound().build()`, which is a **404 with an empty body** and not the
   * `{serviceName, errorMessage, errorDate}` envelope every other catalog failure carries.
   */
  libraryElement(name: string): Promise<APIResponse> {
    return this.raw("get", `/v1/library/${name}`);
  }

  // -------------------------------------------------------------------------
  // Snapshots and deployments
  // -------------------------------------------------------------------------

  /**
   * Compiles the chain's current state into a snapshot, named `V1`, `V2`, … per chain.
   *
   * The build response carries no `xmlDefinition` — the controller nulls it before answering, the
   * serializer then drops the key, and the listing and a `light` read behave the same way. Only a
   * full `getSnapshot` has the XML.
   */
  createSnapshot(chainId: string): Promise<SnapshotView> {
    return this.call("post", `/v1/catalog/chains/${chainId}/snapshots`);
  }

  listSnapshots(chainId: string): Promise<SnapshotView[]> {
    return this.call("get", `/v1/catalog/chains/${chainId}/snapshots`);
  }

  getSnapshot(chainId: string, snapshotId: string, light = false): Promise<SnapshotView> {
    const query = light ? "?light=true" : "";
    return this.call("get", `/v1/catalog/chains/${chainId}/snapshots/${snapshotId}${query}`);
  }

  /** Without `labels`, the snapshot keeps the ones it has. */
  renameSnapshot(
    chainId: string,
    snapshotId: string,
    name: string,
    labels?: Array<{ name: string; technical: boolean }>,
  ): Promise<SnapshotView> {
    return this.call("put", `/v1/catalog/chains/${chainId}/snapshots/${snapshotId}`, { name, labels });
  }

  /** Replaces the chain's elements with the snapshot's, answering the snapshot without its XML. */
  revertSnapshot(chainId: string, snapshotId: string): Promise<SnapshotView> {
    return this.call("post", `/v1/catalog/chains/${chainId}/snapshots/${snapshotId}/revert`);
  }

  /**
   * Deploys a snapshot to an engine domain. Two things are refused or defaulted here rather than
   * at the service, because both fail in a way that names nothing:
   *
   * - A missing snapshot id. `JSON.stringify({snapshotId: undefined})` drops the key, and the
   *   catalog answers a bare `500 The given id must not be null`.
   * - A missing `domain`. Measured: the deployment is created and answers 200, the row lands in
   *   `catalog.deployments` with an **empty** domain, and no engine ever sees it —
   *   `POST /v1/catalog/domains/default/deployments/update` does not list it,
   *   `GET /v1/catalog/runtime-deployments` never keys the chain, and the route stays 404. A poll
   *   written against that state times out with nothing to say. The UI always sends the domain;
   *   a hand-written client is what forgets it.
   *
   * `async` rather than plain, so the refusal reaches a caller's `.catch()` instead of being thrown
   * at the call site of a method declared to answer a promise.
   */
  async deploy(
    chainId: string,
    snapshotId: string,
    domain: string = DEFAULT_DOMAIN,
  ): Promise<DeploymentView> {
    if (!snapshotId) {
      throw new Error(
        `deploying chain ${chainId} needs a snapshot id: create one with POST ` +
          `/v1/catalog/chains/${chainId}/snapshots first. Sending none answers ` +
          `500 "The given id must not be null".`,
      );
    }
    return this.call("post", `/v1/catalog/chains/${chainId}/deployments`, { snapshotId, domain });
  }

  /** The chain's deployment rows, each carrying the engine-reported `runtime.states` per host. */
  listDeployments(chainId: string): Promise<DeploymentView[]> {
    return this.call("get", `/v1/catalog/chains/${chainId}/deployments`);
  }

  getDeployment(chainId: string, deploymentId: string): Promise<DeploymentView> {
    return this.call("get", `/v1/catalog/chains/${chainId}/deployments/${deploymentId}`);
  }

  async undeploy(chainId: string, deploymentId: string): Promise<void> {
    await this.call("delete", `/v1/catalog/chains/${chainId}/deployments/${deploymentId}`);
  }

  async undeployAll(chainId: string): Promise<void> {
    await this.call("delete", `/v1/catalog/chains/${chainId}/deployments`);
  }

  /**
   * Deploys the chain to every domain in `requests` at once. Every deployment takes the first
   * request's snapshot, whatever snapshot the others name.
   */
  deployAll(chainId: string, requests: Array<{ snapshotId: string; domain: string }>): Promise<DeploymentView[]> {
    return this.call("post", `/v1/catalog/chains/${chainId}/deployments/all`, requests);
  }

  /**
   * (Re)deploys chains in one call, taking the snapshot itself.
   *
   * The status code carries information the body repeats and a caller usually drops: **200** when
   * every row succeeded, **207** as soon as one did not. An unknown chain id is neither an error
   * nor a row — it is dropped, and the call answers 200 with an empty list.
   */
  async bulkDeploy(
    chainIds: string[],
    options: { domains?: string[]; snapshotAction?: BulkSnapshotAction } = {},
  ): Promise<{ status: number; rows: BulkDeploymentRow[] }> {
    const response = await this.raw("post", "/v1/catalog/chains/deployments/bulk", {
      chainIds,
      domains: options.domains ?? [DEFAULT_DOMAIN],
      snapshotAction: options.snapshotAction ?? "CREATE_NEW",
    });
    if (response.status() !== 200 && response.status() !== 207) {
      await fail(response, "POST", `${this.base}/v1/catalog/chains/deployments/bulk`);
    }
    return { status: response.status(), rows: (await response.json()) as BulkDeploymentRow[] };
  }

  /**
   * A dict keyed by chain id, not a list — anything phrased as a filter over rows is wrong.
   *
   * Answers **204** while nothing at all is deployed on the stack, which reads as an empty dict
   * rather than as `undefined`.
   */
  async runtimeDeployments(): Promise<Record<string, RuntimeDeployment[]>> {
    const deployments = await this.call<Record<string, RuntimeDeployment[]> | undefined>(
      "get",
      "/v1/catalog/runtime-deployments",
    );
    return deployments ?? {};
  }

  /** The chain's runtime rows, or none while the engine has not reported it. */
  async runtimeDeploymentsOf(chainId: string): Promise<RuntimeDeployment[]> {
    return (await this.runtimeDeployments())[chainId] ?? [];
  }

  /**
   * The same view, reduced to the fields named.
   *
   * `fields` is a Jackson `SimpleBeanPropertyFilter.filterOutAllExcept` over two filters, not a
   * projection the query knows about: a name under `deploymentInfo.` selects inside the nested
   * object and every other name selects at the row's top level. A name nothing carries is not
   * refused — it selects nothing, and each row comes back as `{}`.
   */
  async runtimeDeploymentFields(
    fields: string[],
  ): Promise<Record<string, Array<Record<string, unknown>>>> {
    const query = new URLSearchParams({ fields: fields.join(",") });
    const rows = await this.call<Record<string, Array<Record<string, unknown>>> | undefined>(
      "get",
      `/v1/catalog/runtime-deployments?${query}`,
    );
    return rows ?? {};
  }

  // -------------------------------------------------------------------------
  // Engine domains
  // -------------------------------------------------------------------------

  /** The engine domains the catalog can see. Compose serves exactly one, `default`. */
  listDomains(): Promise<EngineDomainView[]> {
    return this.call("get", "/v1/catalog/domains");
  }

  /**
   * Snapshots the chains and deploys them to every listed domain as one camel-k Integration per
   * micro domain.
   *
   * A domain name the catalog does not list is taken as a new micro domain. A micro deployment
   * writes no row to `catalog.deployments`, so the answer is the only record of it, and an unknown
   * chain id is dropped from it with no error: a caller compares the rows with the ids it sent.
   */
  deployChains(chainIds: string[], domains: string[]): Promise<BulkDeploymentRow[]> {
    return this.call("post", "/v1/cr/deploy-chains", { chainIds, domains });
  }

  /** Deletes a micro domain: its Integration, Service, ConfigMaps, and HTTPRoutes. */
  async deleteCustomResource(name: string): Promise<void> {
    await this.call("delete", `/v1/cr/${encodeURIComponent(name)}`);
  }

  /**
   * The Kubernetes resources a micro domain `name` would run the snapshots in, as multi-document
   * YAML. Nothing is written to the cluster. The build takes only the options sent, so the
   * Integration's image is empty, where a deploy fills it from the catalog's configuration.
   */
  async buildCustomResources(name: string, snapshotIds: string[]): Promise<string> {
    const path = "/v1/cr";
    const response = await this.send("post", path, { data: { options: { name }, snapshotIds } });
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return await response.text();
  }

  /** Deploys the snapshots to micro domain `name`, creating the domain when it does not exist. */
  async deployCustomResource(
    name: string,
    snapshotIds: string[],
    mode: CustomResourceMode = "REWRITE",
  ): Promise<void> {
    await this.call("post", "/v1/cr/deploy", { name, snapshotIds, mode });
  }

  /** Removes one chain snapshot from micro domain `name`, which keeps running the others. */
  async deleteSnapshotFromCustomResource(name: string, snapshotId: string): Promise<void> {
    await this.call(
      "delete",
      `/v1/cr/${encodeURIComponent(name)}/${encodeURIComponent(snapshotId)}`,
    );
  }

  // -------------------------------------------------------------------------
  // Service discovery
  // -------------------------------------------------------------------------

  /**
   * Starts a discovery of the Services in the catalog's namespace; it answers 202 and runs on.
   * A service it finds a specification on becomes a catalog service whose id is the Service name,
   * and a later run skips a Service that already has one.
   */
  async runDiscovery(): Promise<void> {
    await this.call("post", "/v1/systems/discovery");
  }

  /** The percentage the running discovery has reached, `"100"` once it is done or none ran. */
  discoveryProgress(): Promise<string> {
    return this.call<number | string>("get", "/v1/systems/discovery/progress").then(String);
  }

  discoveryResult(): Promise<DiscoveryResult> {
    return this.call("get", "/v1/systems/discovery/result");
  }

  /** Every service a discovery created, whichever run created it. */
  discoveredServices(): Promise<DiscoveredServiceView[]> {
    return this.call("get", "/v1/systems/discovery");
  }

  /** Engine pod ip addresses per domain, read from what the engines registered in Consul. */
  engineHosts(): Promise<Record<string, string[]>> {
    return this.call("get", "/v1/catalog/domains/hosts");
  }

  /**
   * The pods of one domain.
   *
   * **Always empty under Compose**, and not because nothing is running: the `development` profile
   * wires `DevModeDomainSource`, whose `getDomainPods` is `return List.of()` whatever it is asked.
   * `engineHosts()` is the reading that answers on this stack.
   */
  domainEngines(domain: string): Promise<EnginePodView[]> {
    return this.call("get", `/v1/catalog/domains/${domain}/engines`);
  }

  /** What one engine pod reports it is running, by the ip `engineHosts()` gave. */
  engineDeployments(domain: string, host: string): Promise<EngineDeploymentView[]> {
    return this.call("get", `/v1/catalog/domains/${domain}/engines/${host}/deployments`);
  }

  /** Rows in `catalog.deployments` for the domain. A domain nothing deploys to counts 0. */
  deploymentsCount(domain: string): Promise<number> {
    return this.call("get", `/v1/catalog/domains/${domain}/deployments/count`);
  }

  /**
   * The engine-private update feed, called the way the engine calls it.
   *
   * `excludeDeployments` is what the engine already runs, so an **empty** list asks for everything
   * and a populated one asks for the delta. The empty form is not free: `getDeploymentsForDomain`
   * stores its answer in `DeploymentService`'s `private static fullDeploymentsUpdateCache`, shared
   * by every caller and cleared only by a deployment modification. That static is why the spec
   * driving this lives in `specs/global/`.
   */
  deploymentsUpdate(
    domain: string,
    excludeDeployments: Array<{ deploymentId: string }> = [],
  ): Promise<DeploymentsUpdate> {
    return this.call("post", `/v1/catalog/domains/${domain}/deployments/update`, {
      excludeDeployments,
    });
  }

  // -------------------------------------------------------------------------
  // Events
  // -------------------------------------------------------------------------

  /**
   * The change stream the UI polls, from `lastEventId` onward.
   *
   * Two different requests with one answer: `getEvents` declares `defaultValue = ""`, so omitting
   * the parameter and sending it blank both mean "everything still inside the window". The spec
   * sends both forms, which is why the cursor is optional here rather than defaulted.
   */
  events(lastEventId?: string): Promise<EventsUpdate> {
    const query = lastEventId === undefined ? "" : `?lastEventId=${encodeURIComponent(lastEventId)}`;
    return this.call("get", `/v1/catalog/events${query}`);
  }

  // -------------------------------------------------------------------------
  // Live exchanges
  // -------------------------------------------------------------------------

  /**
   * The top N exchanges in flight across every engine, or an empty list.
   *
   * **204 on empty**, like the engine endpoint it fans out to, so this unwraps it rather than
   * leaving a caller to parse an empty body.
   */
  async liveExchanges(limit?: number): Promise<LiveExchangeExtView[]> {
    const query = limit === undefined ? "" : `?limit=${limit}`;
    const response = await this.raw("get", `/v1/catalog/live-exchanges${query}`);
    if (response.status() === 204) return [];
    if (!response.ok()) await fail(response, "GET", `${this.base}/v1/catalog/live-exchanges`);
    return (await response.json()) as LiveExchangeExtView[];
  }

  /** The raw answer of the same read, for the case that asserts the 204 itself. */
  liveExchangesResponse(limit?: number): Promise<APIResponse> {
    const query = limit === undefined ? "" : `?limit=${limit}`;
    return this.raw("get", `/v1/catalog/live-exchanges${query}`);
  }

  /**
   * The same list, filtered in the catalog after the fan-out.
   *
   * The filter key is `column`, the same as the diagnostic filters take, because
   * `FilterRequestDTO` is shared and its `feature` field carries `@JsonProperty("column")`.
   * `StringFieldFilter` handles `SESSION_ID`, `CHAIN_NAME`, `MAIN_THREAD` and `POD_IP`;
   * `LongFieldFilter` handles `SESSION_STARTED`, `SESSION_DURATION` and `EXCHANGE_DURATION`. A
   * column neither of them handles is a clause that matches everything rather than an error.
   */
  async filterLiveExchanges(filters: LiveExchangeFilter[]): Promise<LiveExchangeExtView[]> {
    const response = await this.raw("post", "/v1/catalog/live-exchanges", { filters });
    if (response.status() === 204) return [];
    if (!response.ok()) await fail(response, "POST", `${this.base}/v1/catalog/live-exchanges`);
    return (await response.json()) as LiveExchangeExtView[];
  }

  /**
   * Asks the engine holding the exchange to terminate it.
   *
   * The raw response, because the status is the finding: the catalog does **not** answer 202
   * regardless of the outcome. `LiveExchangesService.sendKillExchangeRequest` calls
   * Since #848 a kill aimed at nothing answers **404** rather than 500, and the catalog refuses an
   * address that is not a registered engine host with a 404 of its own.
   */
  killLiveExchange(podIp: string, deploymentId: string, exchangeId: string): Promise<APIResponse> {
    return this.raw("delete", `/v1/catalog/live-exchanges/${podIp}/${deploymentId}/${exchangeId}`);
  }

  /** The edge, for a caller that wants it drawn rather than described. */
  async connectElements(chainId: string, from: string, to: string): Promise<void> {
    await this.createDependency(chainId, from, to);
  }

  // -------------------------------------------------------------------------
  // Dependencies
  // -------------------------------------------------------------------------

  /** `POST /v1/chains/{c}/dependencies` answers `{createdDependencies: [...]}`. */
  createDependency(chainId: string, from: string, to: string): Promise<ChainDiff> {
    return this.call("post", `/v1/chains/${chainId}/dependencies`, { from, to });
  }

  /**
   * Every edge of the chain, in **unstable order** — the mapper folds input and output
   * dependencies through a `HashSet`, so a caller compares sorted.
   *
   * Chain-scoped, unlike the three operations below it: the listing resolves the chain's elements
   * first and answers 404 for a chain id nothing answers to.
   */
  listDependencies(chainId: string): Promise<DependencyView[]> {
    return this.call("get", `/v1/chains/${chainId}/dependencies`);
  }

  getDependency(chainId: string, dependencyId: string): Promise<DependencyView> {
    return this.call("get", `/v1/chains/${chainId}/dependencies/${dependencyId}`);
  }

  /** The `@Deprecated` singular delete. 404 for an id nothing answers to. */
  deleteDependency(chainId: string, dependencyId: string): Promise<ChainDiff> {
    return this.call("delete", `/v1/chains/${chainId}/dependencies/${dependencyId}`);
  }

  /** The plural delete. `dependenciesIds` is required — omitting it is a 400, not "delete none". */
  deleteDependencies(chainId: string, dependencyIds: string[]): Promise<ChainDiff> {
    const query = new URLSearchParams({ dependenciesIds: dependencyIds.join(",") });
    return this.call("delete", `/v1/chains/${chainId}/dependencies?${query}`);
  }

  // -------------------------------------------------------------------------
  // Compiled libraries
  // -------------------------------------------------------------------------

  /**
   * The DTO jar codegen produced for a specification, raw because every status carries meaning:
   * **200** with the bytes, **204** for a specification whose protocol has no code generator, and
   * **404** for a specification id nothing answers to.
   */
  compiledLibrary(modelId: string): Promise<APIResponse> {
    return this.raw("get", `/v1/models/${modelId}/dto/jar`);
  }

  // -------------------------------------------------------------------------
  // Design generation
  // -------------------------------------------------------------------------
  //
  // Two controllers over one generator. `chain-design-controller` answers sequence diagrams for a
  // chain or for one of its snapshots; `detailed-design-controller` renders a FreeMarker template
  // over the same chain and returns markdown. Both `GET` forms are `@Deprecated(since = "24.3")`
  // and both are still served, so both are driven here.

  /** The deprecated single-mode form: always `FULL`, and the mode is not a parameter. */
  chainDesign(chainId: string): Promise<SequenceDiagram> {
    return this.call("get", `/v1/design-generator/chains/${chainId}`);
  }

  /**
   * One diagram per requested mode, keyed by the mode.
   *
   * The body is required and `diagramModes` with it: `{}` is a `500` on a null dereference and a
   * zero-byte body is a `400`. Both are filed in `docs/product-defects.md`, and the spec pins them,
   * so this method never defaults the key — a caller sending nothing means to send nothing.
   */
  chainDesigns(
    chainId: string,
    diagramModes: readonly DiagramMode[],
  ): Promise<Partial<Record<DiagramMode, SequenceDiagram>>> {
    return this.call("post", `/v1/design-generator/chains/${chainId}`, { diagramModes });
  }

  /**
   * The same diagram off a snapshot's elements rather than the chain's.
   *
   * `chainId` decides only the participant label — the elements come from `snapshotId` alone — so
   * any chain's URL renders any snapshot. Filed in `docs/product-defects.md` and carried as a
   * `test.fail()` in `specs/api/design.spec.ts`.
   */
  snapshotDesign(chainId: string, snapshotId: string): Promise<SequenceDiagram> {
    return this.call("get", `/v1/design-generator/chains/${chainId}/snapshots/${snapshotId}`);
  }

  snapshotDesigns(
    chainId: string,
    snapshotId: string,
    diagramModes: readonly DiagramMode[],
  ): Promise<Partial<Record<DiagramMode, SequenceDiagram>>> {
    return this.call("post", `/v1/design-generator/chains/${chainId}/snapshots/${snapshotId}`, {
      diagramModes,
    });
  }

  /** The rendered document. `templateId` is required: without it the answer is a 400. */
  detailedDesign(chainId: string, templateId: string): Promise<DetailedDesign> {
    const query = new URLSearchParams({ templateId });
    return this.call("get", `/v1/detailed-design/chains/${chainId}?${query}`);
  }

  /** Custom templates first, then the built-in ones, each flagged by `builtIn`. */
  listDesignTemplates(includeContent = true): Promise<DesignTemplateView[]> {
    const query = includeContent ? "" : "?includeContent=false";
    return this.call("get", `/v1/detailed-design/templates${query}`);
  }

  getDesignTemplate(templateId: string): Promise<DesignTemplateView> {
    return this.call("get", `/v1/detailed-design/templates/${templateId}`);
  }

  /**
   * Creates a template. Despite the service method's name it never updates one: a name the store
   * already holds is a `400`, so a run-token name is safe to reuse across workers.
   *
   * The id is the name lowercased, which is also what makes a name colliding with a built-in id a
   * `409` rather than a second row.
   */
  createDesignTemplate(name: string, content: string): Promise<DesignTemplateView> {
    return this.call("put", "/v1/detailed-design/templates", { name, content });
  }

  /** 204, and 204 again for an id nothing answers to. `ids` is required — none is a 400. */
  async deleteDesignTemplates(ids: readonly string[]): Promise<void> {
    if (!ids.length) return;
    const query = new URLSearchParams({ ids: ids.join(",") });
    await this.call("delete", `/v1/detailed-design/templates?${query}`);
  }

  // -------------------------------------------------------------------------
  // Export and import
  // -------------------------------------------------------------------------

  async exportSystems(ids: string[]): Promise<Buffer> {
    return await this.download(`/v1/export/system?systemIds=${ids.join(",")}`);
  }

  /**
   * The chain export, which lives under `/v1/catalog/export` while the service export lives under
   * `/v1/export/system`. The two prefixes are not a typo and neither answers on the other's path.
   */
  async exportChains(ids: string[]): Promise<Buffer> {
    return await this.download(`/v1/catalog/export/chains?chainIds=${ids.join(",")}`);
  }

  /** One chain, through its own path. The archive is the same shape as the plural export's. */
  async exportChain(id: string): Promise<Buffer> {
    return await this.download(`/v1/catalog/export/chain/${id}`);
  }

  /** Every chain on the stack. A spec asserts its own entry is present, never the entry count. */
  async exportAllChains(): Promise<Buffer> {
    return await this.download("/v1/catalog/export");
  }

  /**
   * The specification export, whose response shape depends on what was asked for.
   *
   * **One** `specificationIds` entry whose specification has a single source answers the source
   * file itself, byte for byte, with its own name in `Content-Disposition`. Anything else needs
   * `specificationGroupId` and answers a zip of `source-{modelId}/{file}` entries — so two ids and
   * no group is a **404 `Can't find specification source`**, not an archive of two.
   */
  exportSpecifications(query: {
    specificationIds?: string[];
    specificationGroupId?: string;
  }): Promise<APIResponse> {
    const parameters = new URLSearchParams();
    if (query.specificationIds?.length) {
      parameters.set("specificationIds", query.specificationIds.join(","));
    }
    if (query.specificationGroupId) parameters.set("specificationGroupId", query.specificationGroupId);
    return this.send("get", `/v1/export/specifications?${parameters}`);
  }

  /** `path` is the import endpoint, because services and chains do not share one. */
  async importArchive(path: string, archive: Buffer, name = "fixture.zip"): Promise<APIResponse> {
    return await this.send("post", path, {
      multipart: { file: { name, mimeType: "application/zip", buffer: archive } },
    });
  }

  /**
   * Imports a chain archive, answering the raw response because the status carries meaning.
   *
   * 200 while every row succeeded, **207** as soon as one carries `ERROR` — and 200 with an empty
   * `chains` array for an archive whose entries sit at the zip root, which is the quiet way an
   * import that imported nothing reports success.
   */
  importChains(archive: Buffer, name = "chains.zip"): Promise<APIResponse> {
    return this.importArchive("/v1/catalog/import", archive, name);
  }

  /** What an import would write. Reads the archive and touches nothing. */
  async previewImport(archive: Buffer, name = "chains.zip"): Promise<ImportPreviewResult> {
    const path = "/v1/catalog/import/preview";
    const response = await this.importArchive(path, archive, name);
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return (await response.json()) as ImportPreviewResult;
  }

  // -------------------------------------------------------------------------
  // Import instructions
  // -------------------------------------------------------------------------

  listImportInstructions(): Promise<ImportInstructions> {
    return this.call("get", "/v1/catalog/import-instructions");
  }

  addImportInstruction(request: ImportInstructionRequest): Promise<ImportInstructionView> {
    return this.call("post", "/v1/catalog/import-instructions", request);
  }

  updateImportInstruction(request: ImportInstructionRequest): Promise<ImportInstructionView> {
    return this.call("patch", "/v1/catalog/import-instructions", request);
  }

  /** 204, and 204 again for an id that is already gone. A request with no body answers 415. */
  async deleteImportInstructions(request: DeleteInstructionsRequest): Promise<void> {
    await this.call("delete", "/v1/catalog/import-instructions", request);
  }

  /**
   * The search, which reads `ID` and `OVERRIDDEN_BY` and **nothing else**.
   *
   * `ImportInstructionsService.searchImportInstructions` builds those two filters and no more, so
   * searching for the entity name every response carries answers an empty result.
   */
  searchImportInstructions(searchCondition: string): Promise<ImportInstructions> {
    return this.call("post", "/v1/catalog/import-instructions/search", { searchCondition });
  }

  /** A column the builder cannot translate answers **500**, not 400. */
  filterImportInstructions(filters: FilterRequest[]): Promise<ImportInstructions> {
    return this.call("post", "/v1/catalog/import-instructions/filter", filters);
  }

  /**
   * Uploads an instruction configuration, which is **destructive and immediate**.
   *
   * Every id under a `delete` action is deleted from the platform as the file is read, and the
   * returned rows are those deletions — a stored `ignore` or `override` produces no row at all. The
   * upload **merges**: an instruction the file does not mention survives it.
   */
  async uploadImportInstructions(
    document: string,
    labels?: string[],
    name = "import-instructions.yaml",
  ): Promise<ImportInstructionResult[]> {
    const path = "/v1/catalog/import-instructions/upload";
    const response = await this.send("post", path, {
      ...(labels?.length ? { headers: { labels: labels.join(",") } } : {}),
      multipart: {
        file: { name, mimeType: "application/x-yaml", buffer: Buffer.from(document, "utf-8") },
      },
    });
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return (await response.json()) as ImportInstructionResult[];
  }

  /** The configuration as YAML. Served as `Content-Type: application/json` over a YAML body. */
  exportImportInstructions(): Promise<APIResponse> {
    return this.send("get", "/v1/catalog/import-instructions/export");
  }

  // -------------------------------------------------------------------------
  // The entities the folder cascade does not reach
  // -------------------------------------------------------------------------

  createSystem(name: string, type: string, description?: string): Promise<SystemView> {
    return this.call("post", "/v1/systems", { name, type, description });
  }

  listSystems(): Promise<SystemView[]> {
    return this.call("get", "/v1/systems");
  }

  getSystem(id: string): Promise<SystemView> {
    return this.call("get", `/v1/systems/${id}`);
  }

  /**
   * The services whose id or name equals the condition, or whose name or description contains it,
   * ignoring case. `includeChainUsage` adds the chains that reference each one.
   */
  searchSystems(searchCondition: string, includeChainUsage = false): Promise<SystemView[]> {
    return this.call("post", `/v1/systems/search?includeChainUsage=${includeChainUsage}`, { searchCondition });
  }

  filterSystems(filters: FilterRequest[]): Promise<SystemView[]> {
    return this.call("post", "/v1/systems/filter", filters);
  }

  /** Every element on the stack that calls a service of `type`, one row per element. */
  systemUsage(type: string): Promise<SystemUsage[]> {
    return this.call("get", `/v1/systems/usage?type=${encodeURIComponent(type)}`);
  }

  /** What an import of this service archive would do, without doing it. */
  previewSystemImport(archive: Buffer): Promise<ImportSystemResult[]> {
    return this.previewServiceImport("/v1/import/systemPreview", archive);
  }

  /**
   * Replaces the service's own fields, and it means replace.
   *
   * `SystemMapper.mergeWithoutLabels` carries no null-ignoring strategy, so a request omitting
   * `description` clears it and one omitting `activeEnvironmentId` **deactivates the environment**.
   * A caller changing one field sends the rest of the state with it, or uses `patchSystem`.
   */
  updateSystem(id: string, system: Partial<SystemView>): Promise<SystemView> {
    return this.call("put", `/v1/systems/${id}`, system);
  }

  /** Merges instead of replacing: `patchMergeWithoutLabels` ignores every key the body omits. */
  patchSystem(id: string, system: Partial<SystemView>): Promise<SystemView> {
    return this.call("patch", `/v1/systems/${id}`, system);
  }

  /** Takes the system's environments with it, which is the only thing that removes them. */
  async deleteSystem(id: string): Promise<void> {
    await this.call("delete", `/v1/systems/${id}`);
  }

  // -------------------------------------------------------------------------
  // Environments
  // -------------------------------------------------------------------------

  /**
   * Creates an environment under a service, answering **201**.
   *
   * Two rules are enforced here rather than at the caller. A label may name only one environment
   * per service — a second one answers `400 Label should be unique within single system` — and an
   * `INTERNAL` service refuses a second environment outright.
   *
   * The first environment of an `EXTERNAL` service with a non-empty address is activated on
   * creation (`EnvironmentBaseService.activateDefaultEnvForExternalSystem`); the second is not.
   */
  createEnvironment(systemId: string, environment: Partial<EnvironmentView>): Promise<EnvironmentView> {
    return this.call("post", `/v1/systems/${systemId}/environments`, environment);
  }

  listEnvironments(systemId: string): Promise<EnvironmentView[]> {
    return this.call("get", `/v1/systems/${systemId}/environments`);
  }

  getEnvironment(systemId: string, environmentId: string): Promise<EnvironmentView> {
    return this.call("get", `/v1/systems/${systemId}/environments/${environmentId}`);
  }

  /** An id the service does not know is not a 404 here: the controller creates instead. */
  updateEnvironment(
    systemId: string,
    environmentId: string,
    environment: Partial<EnvironmentView>,
  ): Promise<EnvironmentView> {
    return this.call("put", `/v1/systems/${systemId}/environments/${environmentId}`, environment);
  }

  /**
   * Activates one of a service's environments.
   *
   * There is no activation endpoint: activation is a field on the service, written by the same
   * wholesale `PUT` as everything else. So the current state is read first and sent back with it,
   * because a bare `{activeEnvironmentId}` would clear the name and the type on its way through.
   */
  async activateEnvironment(systemId: string, environmentId: string): Promise<SystemView> {
    const system = await this.getSystem(systemId);
    return await this.updateSystem(systemId, {
      name: system.name,
      type: system.type,
      description: system.description,
      activeEnvironmentId: environmentId,
    });
  }

  // -------------------------------------------------------------------------
  // Specification groups and specifications
  // -------------------------------------------------------------------------

  /**
   * An empty group, answering **201**.
   *
   * The id is derived rather than generated: `{systemId}-{name}`, so two groups of one name under
   * one service are the same group and a spec asserting on the id can build it.
   */
  createSpecificationGroup(
    systemId: string,
    name: string,
    options: { description?: string; url?: string; synchronization?: boolean } = {},
  ): Promise<SpecificationGroupView> {
    return this.call("post", "/v1/specificationGroups", {
      systemId,
      name,
      description: options.description,
      url: options.url,
      synchronization: options.synchronization ?? false,
    });
  }

  /** Requires the system id: `GET /v1/specificationGroups` alone answers 400. */
  listSpecificationGroups(systemId: string): Promise<SpecificationGroupView[]> {
    return this.call("get", `/v1/specificationGroups?systemId=${systemId}`);
  }

  /**
   * The synchronization toggle and the labels, and nothing else.
   *
   * The body binds to `SpecificationGroupRequestDTO`, which declares those two fields only, so a
   * `name` sent here is dropped by Jackson and the call answers 200 with the old name. A group id
   * nothing answers to is a 400, not a 404.
   */
  patchSpecificationGroup(
    id: string,
    group: { synchronization?: boolean; labels?: unknown[] },
  ): Promise<SpecificationGroupView> {
    return this.call("patch", `/v1/specificationGroups/${id}`, group);
  }

  async deleteSpecificationGroup(id: string): Promise<void> {
    await this.call("delete", `/v1/specificationGroups/${id}`);
  }

  /**
   * Creates a group **and** imports the first specification into it, answering **202**.
   *
   * The import is asynchronous: the body carries an `importId` and `done: false`, and the caller
   * polls `GET /v1/import/{importId}` — which belongs to a different controller than the `/v1/import`
   * that takes chain archives, and answers a status rather than an import result.
   */
  async importSpecificationGroup(
    systemId: string,
    name: string,
    file: { name: string; mimeType: string; buffer: Buffer },
    protocol?: string,
  ): Promise<StartedSpecificationImport> {
    const query = new URLSearchParams({ systemId, name, ...(protocol ? { protocol } : {}) });
    const path = `/v1/specificationGroups/import?${query}`;
    const response = await this.send("post", path, { multipart: { files: file } });
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    // The call creates the group, so whatever the wait reads back afterwards was written by it.
    return { ...((await response.json()) as SpecificationImportView), existingModelIds: [] };
  }

  /** A further version into a group that already exists. Asynchronous in the same way. */
  async importSpecification(
    specificationGroupId: string,
    file: { name: string; mimeType: string; buffer: Buffer },
  ): Promise<StartedSpecificationImport> {
    // Read before the POST, because the import is asynchronous and anything read after it may
    // already be its own output.
    const existingModelIds = (await this.listModels(specificationGroupId)).map((each) => each.id);
    const path = `/v1/import?specificationGroupId=${encodeURIComponent(specificationGroupId)}`;
    const response = await this.send("post", path, { multipart: { files: file } });
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return { ...((await response.json()) as SpecificationImportView), existingModelIds };
  }

  /** The import's own status. `done` flips once the specification and its operations exist. */
  specificationImport(importId: string): Promise<SpecificationImportView> {
    return this.call("get", `/v1/import/${importId}`);
  }

  /**
   * Waits for a 202 specification import and answers with the **artifact**, never the flag.
   *
   * The flag is the thing that lies. Two measured instances share the shape: a gRPC import reports
   * `done: true` with a null `compiled_library_id`, and a root-layout archive imports nothing and
   * reports success — so a poll that returns on `done` reports a green import over an empty group.
   * This one reads the specifications the import was supposed to produce and their operations, and
   * fails naming the group when there are none.
   *
   * `specificationGroupId` comes off the 202 for both endpoints; it is an argument only for a
   * caller holding a group the response did not name.
   */
  async awaitSpecificationImport(
    started: StartedSpecificationImport,
    options: { specificationGroupId?: string; timeout?: number } = {},
  ): Promise<ImportedSpecification> {
    const groupId = options.specificationGroupId ?? started.specificationGroupId;
    if (!groupId) {
      throw new Error(
        `the import ${started.id} named no specification group, so nothing can be read back: ` +
          JSON.stringify(started),
      );
    }
    const deadline = Date.now() + (options.timeout ?? 30_000);
    let view: SpecificationImportView = started;
    while (!view.done) {
      if (Date.now() > deadline) {
        throw new Error(
          `specification import ${started.id} into ${groupId} never reported done: ` +
            JSON.stringify(view),
        );
      }
      await sleep(250);
      view = await this.specificationImport(started.id);
    }

    // Against what the group held **before** the import, never against the group as a whole: a
    // second version goes into a group that already carries a specification with operations, so
    // "the group is not empty" is satisfied before this import has written anything.
    const before = new Set(started.existingModelIds);
    const specifications = await this.listModels(groupId);
    const written = specifications.filter((each) => !before.has(each.id));
    if (written.length === 0) {
      throw new Error(
        `specification import ${started.id} reported done and group ${groupId} holds no ` +
          `specification it did not already hold: the import answered success and produced ` +
          `nothing. The group carries ${specifications.length}, all of them older than this import`,
      );
    }
    const operations: OperationView[] = [];
    for (const specification of written) {
      operations.push(...(await this.listOperations(specification.id)));
    }
    if (operations.length === 0) {
      throw new Error(
        `specification import ${started.id} reported done and the ${written.length} ` +
          `specifications it wrote into ${groupId} carry no operation between them: the document ` +
          `was accepted and nothing was parsed out of it`,
      );
    }
    return { view, specifications, operations };
  }

  /**
   * The specifications of one group.
   *
   * **Not "every specification" when the argument is omitted.** `SystemModelController.getModels`
   * starts from an empty list and fills it only inside an `if`/`else if` over the two filters, so a
   * bare `GET /v1/models` answers `[]` — an empty catalog and a call that named no filter are the
   * same answer. `specs/api/system-models.spec.ts` pins that; nothing here should rely on it.
   */
  listModels(specificationGroupId?: string): Promise<SpecificationView[]> {
    const query = specificationGroupId
      ? `?specificationGroupId=${encodeURIComponent(specificationGroupId)}`
      : "";
    return this.call("get", `/v1/models${query}`);
  }

  /** The same listing filtered by service. `specificationGroupId` wins when both are sent. */
  listModelsOfSystem(systemId: string): Promise<SpecificationView[]> {
    return this.call("get", `/v1/models?systemId=${encodeURIComponent(systemId)}`);
  }

  getModel(modelId: string): Promise<SpecificationView> {
    return this.call("get", `/v1/models/${modelId}`);
  }

  /**
   * The service's **most recently created** specification, across all of its groups.
   *
   * `findFirstBySpecificationGroupSystemIdOrderByCreatedWhenDesc` — the order is `created_when`,
   * never the version, so the answer is not the highest `info.version` the service carries.
   * `systemId` is required and a service with no specification answers **200 with an empty body**,
   * which `call` reads back as `undefined`.
   */
  latestModel(systemId: string): Promise<SpecificationView | undefined> {
    return this.call("get", `/v1/models/latest?systemId=${encodeURIComponent(systemId)}`);
  }

  /**
   * Partially updates a specification. **The body's `id` decides which one**, not the path.
   *
   * `SystemModelController.partiallyUpdateSystemModel` binds `modelId` and never reads it; the
   * service resolves `systemModelMapper.asEntity(model).getId()`. A body without an `id` answers
   * `500 The given id must not be null` whatever the path says. That is a filed defect, and it is
   * why this signature takes the model rather than an id and a patch.
   */
  patchModel(model: { id: string } & Partial<SpecificationView>): Promise<SpecificationView> {
    return this.call("patch", `/v1/models/${model.id}`, model);
  }

  /**
   * The specification's operations, all of them. `modelId` is required — the bare call answers 400.
   *
   * `count=0` reads as "no window" rather than "no rows": `OperationController` defaults the
   * parameter to `20`, and `OperationService.getOperations` sends a `limit` of zero to the
   * unwindowed repository method instead of to `setMaxResults`. A request that omits it is served
   * twenty operations, and the answer carries neither a total nor a next-page marker, so a caller
   * cannot tell a truncated listing from a complete one.
   *
   * Omitting it would have gone red rather than green today, which is why the defect sat here: the
   * four call sites in `specs/api/specifications.spec.ts` assert with `toEqual` and `toHaveLength`
   * against three-operation fixtures, and the only other reader, `awaitSpecificationImport`, asks
   * the listing whether it is empty. It turns silent the day a fixture crosses twenty operations,
   * or the operations are read for anything but their count.
   */
  listOperations(modelId: string): Promise<OperationView[]> {
    return this.call("get", `/v1/operations?modelId=${encodeURIComponent(modelId)}&count=0`);
  }

  /** One operation, with the OpenAPI fragment it was parsed out of. An unknown id answers 404. */
  getOperation(operationId: string): Promise<OperationDetail> {
    return this.call("get", `/v1/operations/${operationId}`);
  }

  /** The fragment plus the derived schemas — what the service-call element's form reads. */
  operationInfo(operationId: string): Promise<OperationInfo> {
    return this.call("get", `/v1/operations/${operationId}/info`);
  }

  /**
   * The derived schemas alone, in one of two modes.
   *
   * `light` — the default — keeps the **keys** of both maps and replaces every schema with `{}`,
   * so the UI can list the content types and the response codes without transferring the bodies.
   * Anything else in `mode` is the full form; the controller compares against the literal `light`
   * and treats every other value as "everything".
   */
  operationSchemas(operationId: string, mode?: "light" | "full"): Promise<OperationSchemas> {
    const query = mode ? `?mode=${mode}` : "";
    return this.call("get", `/v1/operations/${operationId}/schemas${query}`);
  }

  /**
   * One entry of the request schema map, by content type.
   *
   * A content type the operation does not declare is not an error: the lookup misses, the
   * controller answers the null, and the body comes back **empty** with a 200 — which `call` reads
   * as `undefined`.
   */
  operationRequestSchema(operationId: string, contentType?: string): Promise<unknown> {
    const query = contentType ? `?contentType=${encodeURIComponent(contentType)}` : "";
    return this.call("get", `/v1/operations/${operationId}/schemas/request${query}`);
  }

  /**
   * One entry of the response schema map, by code and then by content type.
   *
   * A code or a content type the operation does not declare misses the way the request form does:
   * an empty body with a 200, which `call` reads as `undefined`.
   */
  operationResponseSchema(
    operationId: string,
    options: { contentType?: string; responseCode?: string } = {},
  ): Promise<unknown> {
    const query = new URLSearchParams();
    if (options.contentType) query.set("contentType", options.contentType);
    if (options.responseCode) query.set("responseCode", options.responseCode);
    const suffix = query.toString() ? `?${query}` : "";
    return this.call("get", `/v1/operations/${operationId}/schemas/response${suffix}`);
  }

  /** The OpenAPI fragment on its own — the same object `getOperation` carries under one key. */
  operationSpecification(operationId: string): Promise<Record<string, unknown>> {
    return this.call("get", `/v1/operations/${operationId}/specification`);
  }

  /**
   * Marks a specification deprecated.
   *
   * The body is the bare model id as `text/plain`, not JSON — a `@RequestBody String`. Sent as
   * JSON it arrives quoted and matches no specification.
   */
  async deprecateModel(modelId: string): Promise<SpecificationView> {
    const path = "/v1/models/deprecated";
    const response = await this.send("post", path, {
      headers: { "Content-Type": "text/plain" },
      data: modelId,
    });
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return (await response.json()) as SpecificationView;
  }

  // -------------------------------------------------------------------------
  // Chain roles and access control
  // -------------------------------------------------------------------------
  //
  // One controller, two paths — see `CHAIN_ROLES_PATHS`. Every method here takes the path, so a
  // spec can drive both and prove they are the same operation rather than assuming it.
  //
  // The search is spec-scopable and the roles update is element-scoped, so both live in `api`.
  // What the *redeploy* does is not scoped: `ChainRolesService.redeploy` builds a snapshot and
  // **creates a deployment for a chain that had none**, so a case calling it owns the deployment it
  // produced and has to undeploy it. It has no wrapper here, because every case that calls it
  // asserts the 204 and the empty body, which is what `raw` is for.

  /** The chains' HTTP triggers matching a filter, one row per trigger. */
  searchChainRoles(
    search: ChainElementSearch = {},
    options: { path?: ChainRolesPath; implementedOnly?: boolean } = {},
  ): Promise<ChainRolesPage> {
    const path = options.path ?? CHAIN_ROLES_PATHS[0];
    const query = options.implementedOnly ? "?isImplementedOnly=true" : "";
    return this.call("post", `${path}${query}`, { filters: [], ...search });
  }

  /**
   * Applies roles to HTTP triggers in bulk. **204 with an empty body**, and all or nothing.
   *
   * `ChainRolesService.resolveUpdates` resolves every element of the batch before applying any, so
   * one bad id refuses the whole request. It also rewrites `accessControlType`: `NONE` becomes
   * `RBAC` when the roles are non-empty, and `RBAC` becomes `NONE` when they are emptied.
   */
  async updateChainRoles(
    updates: ChainRolesUpdate[],
    path: ChainRolesPath = CHAIN_ROLES_PATHS[0],
  ): Promise<void> {
    await this.call("put", path, updates);
  }

  // -------------------------------------------------------------------------
  // Context services
  // -------------------------------------------------------------------------

  listContextSystems(): Promise<ContextSystemView[]> {
    return this.call("get", "/v1/catalog/context-system");
  }

  createContextSystem(name: string, description?: string): Promise<ContextSystemView> {
    return this.call("post", "/v1/catalog/context-system", { name, description });
  }

  getContextSystem(id: string): Promise<ContextSystemView> {
    return this.call("get", `/v1/catalog/context-system/${id}`);
  }

  /** Merges, unlike the service `PUT`: a key the body omits keeps the value it had. */
  updateContextSystem(id: string, context: Partial<ContextSystemView>): Promise<ContextSystemView> {
    return this.call("put", `/v1/catalog/context-system/${id}`, context);
  }

  patchContextSystem(id: string, context: Partial<ContextSystemView>): Promise<ContextSystemView> {
    return this.call("patch", `/v1/catalog/context-system/${id}`, context);
  }

  /** 204, and 404 the second time — the opposite of the MCP delete. */
  async deleteContextSystem(id: string): Promise<void> {
    await this.call("delete", `/v1/catalog/context-system/${id}`);
  }

  /** A substring search over the name. The body is `{searchCondition}`. */
  searchContextSystems(searchCondition: string): Promise<ContextSystemView[]> {
    return this.call("post", "/v1/catalog/context-system/search", { searchCondition });
  }

  /**
   * The same rows through the filter surface, whose body is a **bare list** of clauses.
   *
   * Unlike the MCP twin, which wraps them in `{searchString, filters}`: two controllers written
   * against the same `FilterRequestDTO` and disagreeing about the envelope.
   */
  filterContextSystems(filters: ContextSystemFilter[]): Promise<ContextSystemView[]> {
    return this.call("post", "/v1/catalog/context-system/filter", filters);
  }

  /**
   * The export archive, by either verb.
   *
   * One `@RequestMapping(method = {GET, POST})` serves both, so the method is the caller's argument
   * and the two are separate registry rows.
   */
  exportContextSystems(ids: readonly string[], method: "get" | "post" = "get"): Promise<APIResponse> {
    return this.raw(method, `/v1/catalog/context-system/export?systemIds=${ids.join(",")}`);
  }

  /** What an import of this archive would do to the context services, without doing it. */
  previewContextSystemImport(archive: Buffer): Promise<ImportSystemResult[]> {
    return this.previewServiceImport("/v1/catalog/context-system/import/preview", archive);
  }

  /** The three service families preview an archive alike; only the path differs. */
  private async previewServiceImport(path: string, archive: Buffer): Promise<ImportSystemResult[]> {
    const response = await this.importArchive(path, archive);
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return (await response.json()) as ImportSystemResult[];
  }

  // -------------------------------------------------------------------------
  // MCP services
  // -------------------------------------------------------------------------

  listMcpSystems(): Promise<McpSystemView[]> {
    return this.call("get", "/v1/catalog/mcp-system");
  }

  createMcpSystem(mcp: Partial<McpSystemView>): Promise<McpSystemView> {
    return this.call("post", "/v1/catalog/mcp-system", mcp);
  }

  getMcpSystem(id: string): Promise<McpSystemView> {
    return this.call("get", `/v1/catalog/mcp-system/${id}`);
  }

  /**
   * Merges the fields it is given — and **500s without a `labels` key**.
   *
   * `MCPSystemService.update` calls `request.getLabels().stream()` unguarded, so an omitted key is
   * an NPE reported as `500 Cannot invoke "java.util.List.stream()"`. The same shape the chain
   * create once had; here it is still live, so the key is sent rather than left to the caller.
   */
  updateMcpSystem(id: string, mcp: Partial<McpSystemView>): Promise<McpSystemView> {
    return this.call("put", `/v1/catalog/mcp-system/${id}`, { labels: [], ...mcp });
  }

  /** 204, and 204 again: the service deletes through `ifPresent` and reports nothing missing. */
  async deleteMcpSystem(id: string): Promise<void> {
    await this.call("delete", `/v1/catalog/mcp-system/${id}`);
  }

  /** The body is `{searchString, filters}` — a bare array answers 400 `Failed to read request`. */
  filterMcpSystems(searchString: string, filters: unknown[] = []): Promise<McpSystemView[]> {
    return this.call("post", "/v1/catalog/mcp-system/filter", { searchString, filters });
  }

  async exportMcpSystems(ids: readonly string[]): Promise<Buffer> {
    const response = await this.raw("post", "/v1/catalog/mcp-system/export", ids);
    if (!response.ok()) await fail(response, "POST", `${this.base}/v1/catalog/mcp-system/export`);
    return Buffer.from(await response.body());
  }

  /** What an import of this archive would do to the MCP services, without doing it. */
  previewMcpSystemImport(archive: Buffer): Promise<ImportSystemResult[]> {
    return this.previewServiceImport("/v1/catalog/mcp-system/import/preview", archive);
  }

  // -------------------------------------------------------------------------
  // Variables
  // -------------------------------------------------------------------------

  /** A flat map of name to value, so the run token is matched against the keys. */
  listCommonVariables(): Promise<Record<string, string>> {
    return this.call("get", "/v1/common-variables");
  }

  /** Answers the names it wrote, not the variables. A name off `^[-._a-zA-Z0-9]+$` answers 400. */
  addCommonVariables(variables: Record<string, string>): Promise<string[]> {
    return this.call("post", "/v1/common-variables", variables);
  }

  /**
   * Writes one variable, creating it when it does not exist: the PATCH is an upsert.
   *
   * The body is the bare value as `text/plain` — sent as JSON it is stored with its quotes — and
   * the answer is `{response: name}`, the **name** rather than the value, so a caller reading it
   * as a value round trip reads the name back and passes.
   */
  async updateCommonVariable(name: string, value?: string): Promise<StringResponse> {
    const path = `/v1/common-variables/${encodeURIComponent(name)}`;
    const response = await this.send("patch", path, {
      headers: { "content-type": "text/plain" },
      ...(value === undefined ? {} : { data: value }),
    });
    if (!response.ok()) await fail(response, "PATCH", `${this.base}${path}`);
    return (await response.json()) as StringResponse;
  }

  /** A batch by exact name — there is no delete by prefix anywhere in this API. */
  async deleteCommonVariables(names: string[]): Promise<void> {
    if (!names.length) return;
    const query = names.map(encodeURIComponent).join(",");
    await this.call("delete", `/v1/common-variables?variablesNames=${query}`);
  }

  /**
   * The export, raw: it answers 204 when there is nothing to write and 400 when a name is unknown,
   * and both are contract rather than failure.
   */
  exportCommonVariables(names?: string[], asArchive = false): Promise<APIResponse> {
    const query = new URLSearchParams({ asArchive: String(asArchive) });
    if (names?.length) query.set("variablesNames", names.join(","));
    return this.send("get", `/v1/common-variables/export?${query}`);
  }

  /** Both the YAML the export writes and the archive form of it are accepted here. */
  async importCommonVariables(
    file: Buffer,
    options: { names?: string[]; fileName?: string } = {},
  ): Promise<ImportVariableResult[]> {
    const fileName = options.fileName ?? "common-variables.yaml";
    const query = options.names?.length ? `?variablesNames=${options.names.join(",")}` : "";
    const path = `/v1/common-variables/import${query}`;
    const response = await this.send("post", path, { multipart: { file: variablesUpload(fileName, file) } });
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return (await response.json()) as ImportVariableResult[];
  }

  /** The same file, read and reported against what is stored, without writing anything. */
  async previewCommonVariables(file: Buffer, fileName = "common-variables.yaml"): Promise<ImportVariablePreview[]> {
    const path = "/v1/common-variables/preview";
    const response = await this.send("post", path, { multipart: { file: variablesUpload(fileName, file) } });
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return (await response.json()) as ImportVariablePreview[];
  }

  /** Every secret with the names it holds. Values are never in this answer, by design. */
  listSecrets(): Promise<SecretView[]> {
    return this.call("get", "/v2/secured-variables");
  }

  securedVariablesInSecret(secretName: string): Promise<string[]> {
    return this.call("get", `/v2/secured-variables/${encodeURIComponent(secretName)}`);
  }

  /** Writes new variables into a secret and answers every secret it touched, names only. */
  addSecuredVariables(secretName: string, variables: Record<string, string>): Promise<SecretView[]> {
    return this.call("post", "/v2/secured-variables", { secretName, variables });
  }

  /** Rewrites variables that already exist: a name the secret does not hold answers 404. */
  updateSecuredVariables(secretName: string, variables: Record<string, string>): Promise<SecretView> {
    return this.call("patch", "/v2/secured-variables", { secretName, variables });
  }

  async deleteSecuredVariablesFromSecret(secretName: string, names: string[]): Promise<void> {
    if (!names.length) return;
    const query = names.map(encodeURIComponent).join(",");
    await this.call("delete", `/v2/secured-variables/${encodeURIComponent(secretName)}?variablesNames=${query}`);
  }

  /**
   * Every secured variable the suite could have written, as `{secret, name}` pairs.
   *
   * Read from `/v2` rather than `/v1` on purpose. `GET /v1/secured-variables` answers **410** on
   * this stack — it addresses the default secret, and `cip.variables.default-secret.enabled` is
   * false — so a sweep built on it finds nothing however much residue there is, which is the
   * shape of a cleanup that looks like it works. The disabled secrets are skipped because the
   * delete on them answers 410 too, and they hold nothing this suite could have written.
   */
  async listSecuredVariables(): Promise<SecuredVariableRef[]> {
    const secrets = await this.listSecrets();
    return secrets
      .filter((secret) => !secret.disabled)
      .flatMap((secret) => (secret.variablesNames ?? []).map((name) => ({ secret: secret.secretName, name })));
  }

  /** Deletes by secret, because the delete takes one in the path and the names in the query. */
  async deleteSecuredVariables(refs: SecuredVariableRef[]): Promise<void> {
    const bySecret = new Map<string, string[]>();
    for (const ref of refs) bySecret.set(ref.secret, [...(bySecret.get(ref.secret) ?? []), ref.name]);
    for (const [secret, names] of bySecret) await this.deleteSecuredVariablesFromSecret(secret, names);
  }

  // -------------------------------------------------------------------------
  // Audit log
  // -------------------------------------------------------------------------

  /**
   * The audit-log search over an explicit time window.
   *
   * The window is not optional in practice: the criteria default to `offsetTime: 0`, which reads as
   * the epoch, and the query is `offsetTime - rangeTime < actionTime <= offsetTime`, so a caller
   * that sends neither gets an empty page and no indication why.
   */
  searchActionsLog(criteria: {
    offsetTime: number;
    rangeTime: number;
    filters?: ActionLogFilter[];
  }): Promise<ActionLogPage> {
    return this.call("post", "/v1/catalog/actions-log", {
      offsetTime: criteria.offsetTime,
      rangeTime: criteria.rangeTime,
      filters: criteria.filters ?? [],
    });
  }

  /**
   * The same search over a window ending a minute in the future and reaching `windowMs` back.
   *
   * The forward minute is deliberate. `actionTime` is stamped by the catalog and the upper bound is
   * built here, so a window ending at this process's `Date.now()` races the row it is looking for
   * whenever the two clocks disagree by milliseconds, and the failure reads as "the action was
   * never logged".
   */
  recentActions(filters: ActionLogFilter[], windowMs = 600_000): Promise<ActionLogPage> {
    return this.searchActionsLog({
      offsetTime: Date.now() + CLOCK_SKEW,
      rangeTime: windowMs + CLOCK_SKEW,
      filters,
    });
  }

  /**
   * The audit log as a spreadsheet, raw: the response is asserted rather than parsed here.
   *
   * It takes a window and **no filters** — the file holds every action in it, whoever caused them —
   * so a caller asserts that its own rows are present and never that they are all there is.
   */
  exportActionsLog(actionTimeFrom: number, actionTimeTo: number): Promise<APIResponse> {
    const query = new URLSearchParams({
      actionTimeFrom: String(actionTimeFrom),
      actionTimeTo: String(actionTimeTo),
    });
    return this.send("get", `/v1/catalog/actions-log/export?${query}`);
  }

  // -------------------------------------------------------------------------
  // Maintenance
  // -------------------------------------------------------------------------

  /**
   * Fires the snapshot prune and answers the raw 202, which is all the endpoint ever says.
   *
   * The work runs on a `CompletableFuture`, so the response carries no artifact and a caller polls
   * for the effect. Destructive across the whole platform — see
   * `specs/global/maintenance.spec.ts`, which is the one caller and says why that is accepted.
   *
   * `requestId` is not decoration. The prune's own audit rows are the only record it leaves, the
   * audit table is global, and `MDCInterceptor` takes `X-Request-ID` off the request verbatim while
   * `pruneSnapshotsAsync` carries the value onto the future — so a caller that supplies one can
   * pick its own prune out of a table every worker writes into, the asynchronous deletions
   * included.
   */
  pruneSnapshots(options: {
    olderThanDays: number;
    chunk?: number;
    requestId?: string;
  }): Promise<APIResponse> {
    const query = new URLSearchParams({ olderThanDays: String(options.olderThanDays) });
    if (options.chunk !== undefined) query.set("chunk", String(options.chunk));
    return this.send("post", `/v1/catalog/maintenance/snapshots/prune?${query}`, {
      headers: options.requestId ? { "X-Request-ID": options.requestId } : {},
    });
  }

  // -------------------------------------------------------------------------
  // Masked fields
  // -------------------------------------------------------------------------

  /** Unwraps `{fields: [...]}`, which is a set on the server and arrives in no particular order. */
  async listMaskedFields(chainId: string): Promise<MaskedFieldView[]> {
    const response = await this.call<{ fields: MaskedFieldView[] }>(
      "get",
      `/v1/chains/${chainId}/masking`,
    );
    return response.fields ?? [];
  }

  /** A second field of the same name on one chain answers **409**, not 200 and not 400. */
  createMaskedField(chainId: string, name: string): Promise<MaskedFieldView> {
    return this.call("post", `/v1/chains/${chainId}/masking`, { name });
  }

  /** Merges into the field, so the body carries only what changes. An unknown id is a 404. */
  updateMaskedField(chainId: string, fieldId: string, name: string): Promise<MaskedFieldView> {
    return this.call("put", `/v1/chains/${chainId}/masking/field/${fieldId}`, { name });
  }

  // -------------------------------------------------------------------------
  // Chain logging properties
  // -------------------------------------------------------------------------

  /**
   * The chain's logging layers.
   *
   * The read is served from a cache the catalog refills from Consul on a 1 s tick
   * (`TasksScheduler.checkRuntimeDeploymentProperties`), so a write is **not** visible to the next
   * read. Anything asserting over `custom` polls.
   */
  getLoggingProperties(chainId: string): Promise<LoggingPropertiesSet> {
    return this.call("get", `/v1/chains/${chainId}/properties/logging`);
  }

  /** Writes the chain's custom properties into Consul and answers 200 with no body. */
  async saveLoggingProperties(chainId: string, properties: Partial<LoggingProperties>): Promise<void> {
    await this.call("post", `/v1/chains/${chainId}/properties/logging`, properties);
  }

  /** Drops the custom layer, so the chain falls back to the default. 204, and 204 again. */
  async deleteLoggingProperties(chainId: string): Promise<void> {
    await this.call("delete", `/v1/chains/${chainId}/properties/logging`);
  }

  // -------------------------------------------------------------------------
  // Route collisions
  // -------------------------------------------------------------------------
  //
  // `element-validation-controller` answers one question — "would an HTTP trigger on this path
  // collide with a route already deployed" — and not "is this chain valid". Chain-rule validation
  // is `diagnostic-controller`, further down.
  //
  // `excludeChainId` is required on both calls: the UI is asking on behalf of a chain it is
  // editing, and a chain always collides with itself. There is no "check against everything" form,
  // so a spec that wants one passes `ABSENT_UUID`.

  /** The query string both route calls take. `excludeChainId` is required by the controller. */
  private routeQuery(uri: string, excludeChainId: string, route: RouteQuery): string {
    const query = new URLSearchParams({ uri, excludeChainId });
    if (route.httpMethods?.length) query.set("httpMethods", route.httpMethods.join(","));
    if (route.isExternalRoute !== undefined) query.set("isExternalRoute", String(route.isExternalRoute));
    if (route.isPrivateRoute !== undefined) query.set("isPrivateRoute", String(route.isPrivateRoute));
    return query.toString();
  }

  /** Whether any **deployed** trigger outside `excludeChainId` already answers on this path. */
  checkRouteExists(uri: string, excludeChainId: string, route: RouteQuery = {}): Promise<boolean> {
    return this.call("get", `/v1/catalog/validation/routes?${this.routeQuery(uri, excludeChainId, route)}`);
  }

  /** The same question, answered with the deployments rather than with a boolean. */
  findRouteDeployments(
    uri: string,
    excludeChainId: string,
    route: RouteQuery = {},
  ): Promise<RouteDeployment[]> {
    return this.call(
      "get",
      `/v1/catalog/validation/findRouteDeployments?${this.routeQuery(uri, excludeChainId, route)}`,
    );
  }

  // -------------------------------------------------------------------------
  // Diagnostics
  // -------------------------------------------------------------------------
  //
  // Whole-catalog by construction: one run rewrites the alert table for every chain on the stack,
  // and the status map is a singleton. Every caller of these three lives in `specs/global/`.

  /** The rules and their last run. A body with neither a search nor a filter lists all of them. */
  listValidations(filter: DiagnosticFilterRequest = {}): Promise<DiagnosticValidation[]> {
    return this.call("post", "/v1/catalog/diagnostic/validations", filter);
  }

  /** One rule by id, with the entities its last run flagged. An unknown id answers **404**. */
  getValidation(validationId: string): Promise<DiagnosticValidation> {
    return this.call("get", `/v1/catalog/diagnostic/validations/${validationId}`);
  }

  /**
   * Starts a validation run and answers **202** — the work runs on a `CompletableFuture`.
   *
   * Raw rather than parsed, because the status is the whole answer and two of them matter: 202
   * when the run started, and **409** while another run holds the lock. An empty id set runs every
   * rule; an id no rule has is dropped rather than refused, so the call still answers 202.
   */
  runValidations(validationIds: string[] = []): Promise<APIResponse> {
    const query = validationIds.length ? `?validationIds=${validationIds.join(",")}` : "";
    return this.raw("patch", `/v1/catalog/diagnostic/validations${query}`);
  }

  // -------------------------------------------------------------------------
  // Folders, `/v2`
  // -------------------------------------------------------------------------
  //
  // The surface the UI's tree is drawn from — `ui/src/api/rest/restApi.ts:1090-1164` reaches for
  // `/v2` on every folder operation except the root listing and the nested-chain read. These are
  // deliberately **not** folded into the `/v1` methods above: the two answer different documents,
  // and a spec asserting over one has to say which.

  createFolderV2(name: string, parentId?: string, description?: string): Promise<CatalogItemV2> {
    return this.call("post", "/v2/folders", { name, parentId, description });
  }

  getFolderV2(id: string): Promise<CatalogItemV2> {
    return this.call("get", `/v2/folders/${id}`);
  }

  /** The folder's ancestors **and the folder itself**, root first. */
  folderPathV2(id: string): Promise<CatalogItemV2[]> {
    return this.call("get", `/v2/folders/${id}/path`);
  }

  /** The same list addressed by name. A name nothing carries is `200 []`, never a 404. */
  folderPathByNameV2(name: string): Promise<CatalogItemV2[]> {
    return this.call("get", `/v2/folders/path?name=${encodeURIComponent(name)}`);
  }

  /** Rename or re-parent. The whole request replaces the folder's own fields. */
  updateFolderV2(
    id: string,
    request: { name: string; description?: string; parentId?: string },
  ): Promise<CatalogItemV2> {
    return this.call("put", `/v2/folders/${id}`, request);
  }

  async deleteFolderV2(id: string): Promise<void> {
    await this.call("delete", `/v2/folders/${id}`);
  }

  /**
   * One level of the tree: the folders and chains whose parent is `folderId`, in one answer.
   *
   * The controller also declares an `id` **query** parameter and never reads it — `folderId` in the
   * body is the only thing that selects a folder — so `query` exists here to let a case pin that,
   * and no other caller passes it.
   */
  listFolderV2(request: ListFolderRequestV2 = {}, query?: string): Promise<CatalogItemV2[]> {
    const suffix = query === undefined ? "" : `?id=${encodeURIComponent(query)}`;
    return this.call("post", `/v2/folders/list${suffix}`, {
      folderId: request.folderId,
      searchString: request.searchString,
      filters: request.filters ?? [],
    });
  }

  /** Re-parents through a body rather than a query parameter, which is the `/v1` move's shape. */
  moveFolderV2(id: string, targetId?: string): Promise<CatalogItemV2> {
    return this.call("post", "/v2/folders/move", { id, targetId });
  }

  /** Raw: the batch is all or nothing, and the failure status is what a case asserts. */
  bulkDeleteFoldersV2(ids: readonly string[]): Promise<APIResponse> {
    return this.raw("post", "/v2/folders/bulk-delete", ids);
  }

  // -------------------------------------------------------------------------
  // Snapshots, `/v2`
  // -------------------------------------------------------------------------

  /**
   * A snapshot by its own id, with the element graph and **without** the Camel XML.
   *
   * The complement of `getSnapshot`, not a replacement for it: the `/v1` read needs the chain in
   * the path and answers `xmlDefinition` with no elements, this one needs no chain and answers the
   * elements with no XML.
   */
  snapshotFull(snapshotId: string): Promise<SnapshotFullV2> {
    return this.call("get", `/v2/catalog/snapshots/${snapshotId}/full`);
  }

  /** Deletes each id it can find and answers 204; an id nothing answers to is logged and skipped. */
  async bulkDeleteSnapshots(ids: readonly string[]): Promise<void> {
    await this.call("post", "/v2/catalog/snapshots/bulk-delete", ids);
  }

  // -------------------------------------------------------------------------
  // Audit log, `/v2`
  // -------------------------------------------------------------------------

  /**
   * The audit log paged by offset and limit, newest first, over **no time window at all**.
   *
   * That is the whole difference from `searchActionsLog`: the `/v1` form is bounded by
   * `offsetTime`/`rangeTime` and unpaged, this one is bounded by nothing and paged. Both read one
   * table through one filter vocabulary, so a case can hold them against each other.
   */
  searchActionsLogV2(criteria: {
    limit?: number;
    offset?: number;
    filters?: ActionLogFilter[];
  } = {}): Promise<ActionLogPageV2> {
    return this.call("post", "/v2/catalog/actions-log", {
      limit: criteria.limit ?? 100,
      offset: criteria.offset ?? 0,
      filters: criteria.filters ?? [],
    });
  }

  /** The same call raw, for the two refusals the filter vocabulary produces. */
  searchActionsLogV2Raw(body: unknown): Promise<APIResponse> {
    return this.raw("post", "/v2/catalog/actions-log", body);
  }

  // -------------------------------------------------------------------------
  // Common variables, `/v2`
  // -------------------------------------------------------------------------

  /**
   * The import the UI calls (`ui/src/api/rest/restApi.ts:414`), where the `/v1` one is
   * `@Deprecated(since = "24.4")`.
   *
   * Always 200, and the body is the wrapper `{variables, instructions}` — which serializes to `{}`
   * when nothing was written, because `ImportVariablesResult` is `@JsonInclude(NON_EMPTY)`. The
   * `/v1` form answers the bare list and **204** for the same nothing.
   */
  async importCommonVariablesV2(
    file: Buffer,
    options: { names?: string[]; fileName?: string } = {},
  ): Promise<ImportVariablesResultV2> {
    const fileName = options.fileName ?? "common-variables.yaml";
    const query = options.names?.length ? `?variablesNames=${options.names.join(",")}` : "";
    const path = `/v2/common-variables/import${query}`;
    const response = await this.send("post", path, {
      multipart: { file: variablesUpload(fileName, file) },
    });
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return (await response.json()) as ImportVariablesResultV2;
  }

  // -------------------------------------------------------------------------
  // Secured variables across secrets, `/v2`
  // -------------------------------------------------------------------------

  /**
   * Deletes named variables from several secrets at once, and answers three ways.
   *
   * 204 when every named secret was found, **207** carrying one `{secretName, errorMessage}` row
   * per secret that was not, and **500** when *no* named secret was found —
   * `SecuredVariableService.deleteVariablesForMultipleSecrets` throws once the failures cover the
   * whole request. Raw, because which of the three is the assertion.
   */
  deleteSecuredVariablesAcross(request: Record<string, string[]>): Promise<APIResponse> {
    return this.raw("delete", "/v2/secured-variables", request);
  }

  // -------------------------------------------------------------------------
  // Import, `/v2` — deprecated since 2023.4
  // -------------------------------------------------------------------------

  /** 202 with `{importId, href}`, a `Location` header and `Retry-After: 60`. */
  importChainsV2(archive: Buffer, name = "chains.zip"): Promise<APIResponse> {
    return this.upload("post", "/v2/import", {
      multipart: { file: { name, mimeType: "application/zip", buffer: archive } },
    });
  }

  /**
   * The progress read, which **redirects** and so is made with redirects off.
   *
   * While the import is running it answers 200 with `Retry-After: 60`; the moment it is done it
   * answers **303** with `Location` naming the result. Playwright follows a 303 by default, so a
   * caller that did not set `maxRedirects: 0` would see the result body and never the redirect —
   * which is the contract this endpoint has and the reason it is not folded into `call`.
   *
   * `/v2/import/preview/{id}/status` is a second mapping on the same handler; `preview` selects it.
   */
  importStatusV2(importId: string, options: { preview?: boolean } = {}): Promise<APIResponse> {
    const path = options.preview
      ? `/v2/import/preview/${importId}/status`
      : `/v2/import/status/${importId}`;
    return this.upload("get", path, { maxRedirects: 0 });
  }

  /** The result rows once the import is done, or 404 for an id the session store never held. */
  importResultV2(importId: string): Promise<APIResponse> {
    return this.raw("get", `/v2/import/${importId}`);
  }

  // -------------------------------------------------------------------------
  // Import, `/v3` — the surface the UI drives
  // -------------------------------------------------------------------------

  /** What an import would write, read against the catalog: `exists` is a lookup, not archive data. */
  async previewImportV3(archive: Buffer, name = "chains.zip"): Promise<ImportPreviewV3> {
    const path = "/v3/import/preview";
    const response = await this.importArchive(path, archive, name);
    if (!response.ok()) await fail(response, "POST", `${this.base}${path}`);
    return (await response.json()) as ImportPreviewV3;
  }

  /**
   * Starts an import and answers **202** with the id to poll.
   *
   * `importRequest` selects what is committed: a `chainCommitRequests` list naming one chain of an
   * archive holding two imports that one and leaves the other out of the result entirely. Omitting
   * it commits everything.
   */
  importV3(archive: Buffer, options: { importRequest?: unknown } = {}): Promise<APIResponse> {
    const multipart: Record<string, string | { name: string; mimeType: string; buffer: Buffer }> = {
      file: { name: "chains.zip", mimeType: "application/zip", buffer: archive },
    };
    if (options.importRequest !== undefined) multipart.importRequest = JSON.stringify(options.importRequest);
    return this.upload("post", "/v3/import", { multipart });
  }

  /** Every import session the catalog still holds, newest first. A `/v2` import is in here too. */
  importSessionsV3(): Promise<ImportSessionSummaryV3[]> {
    return this.call("get", "/v3/import");
  }

  /** One session raw: 200 while it is clean, **207** once a row carries `ERROR`, 404 for no session. */
  importSessionV3(importId: string): Promise<APIResponse> {
    return this.raw("get", `/v3/import/${importId}`);
  }

  /**
   * The difference between a stored chain and the one in an archive.
   *
   * Left is the catalog's — the chain named by `leftChainId`, or the snapshot named by
   * `leftSnapshotId` when one is given. Right is always read out of the archive.
   */
  diffChainsV3(
    archive: Buffer,
    diffRequest: { leftChainId?: string; leftSnapshotId?: string; rightChainId?: string },
    name = "chains.zip",
  ): Promise<APIResponse> {
    return this.upload("post", "/v3/import/chains/diff", {
      multipart: {
        file: { name, mimeType: "application/zip", buffer: archive },
        diffRequest: JSON.stringify(diffRequest),
      },
    });
  }

  /** One chain read out of an archive and mapped, touching the catalog not at all. */
  extractChainV3(archive: Buffer, chainId: string, name = "chains.zip"): Promise<APIResponse> {
    return this.upload("post", "/v3/import/chains/extract", {
      multipart: {
        file: { name, mimeType: "application/zip", buffer: archive },
        chainId,
      },
    });
  }

  // -------------------------------------------------------------------------
  // Rollout import, `/v3`
  // -------------------------------------------------------------------------

  /**
   * Imports a package of configuration documents, given as JSON rather than as an archive.
   *
   * Answers **202 `{status: "Rollout In Progress"}`** and does the work on an `@Async` method, so
   * the outcome reaches the caller only through the `X-Callback-Url` header — a PATCH the catalog
   * sends, and one nothing on this stack listens for. No caller here sends one, so every case
   * asserts the effect instead.
   */
  rolloutImport(snapshotId: string, request: RolloutImportRequest): Promise<APIResponse> {
    return this.raw("put", `/v3/rollout-import/${encodeURIComponent(snapshotId)}`, request);
  }
}
