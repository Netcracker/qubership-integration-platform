/**
 * The response and request shapes the catalog answers with, and the three constants that are data
 * rather than transport.
 *
 * Split out of `support/catalog.ts`, which held both halves and had grown past three thousand
 * lines. Nothing here has behavior or knows about a request context: it is 91 interfaces, five
 * aliases and three constants, and the `Catalog` class beside it is what turns any of them into a
 * call. The split is a file boundary and not a module boundary — `support/catalog.ts` re-exports
 * every name below, so a spec still imports from `support/catalog.js` and nothing here changes what
 * `registry/reached.ts` counts as a transport.
 */

/**
 * How far apart the container's clock and this process's may sit before an assertion cares.
 *
 * `actionTime` is stamped inside the catalog and every bound an audit case builds comes from
 * `Date.now()` here, so a window that ends at this instant races the row it is looking for. A
 * minute is far wider than any drift measured on this stack and far narrower than the ten-minute
 * search window it widens.
 */
export const CLOCK_SKEW = 60_000;

export interface Named {
  id: string;
  name: string;
}

export interface FolderItem extends Named {
  itemType: string;
}

/**
 * One row of the flat list the folder search and filter answer. `items` is left out: a folder row
 * lists every child the folder holds, matched or not.
 */
export interface FolderSearchItem extends Named {
  itemType: "FOLDER" | "CHAIN";
  parentId?: string;
}

/** What `GET /v1/folders/{id}` answers: the folder plus the items directly inside it. */
export interface FolderView extends Named {
  parentId?: string;
  description?: string;
  items: FolderItem[];
  navigationPath: Record<string, string>;
}

/**
 * A chain as `GET /v1/chains/{id}` answers it.
 *
 * `unsavedChanges` is the flag the editor shows and the roles update sets: it means the graph has
 * moved since the current snapshot was built, and only a snapshot build clears it. `currentSnapshot`
 * is absent on a chain that has never been snapshotted, rather than null.
 */
export interface ChainView extends Named {
  parentId?: string;
  description?: string;
  unsavedChanges?: boolean;
  currentSnapshot?: SnapshotView;
  /** Whether an element of a type `PATCH /v1/chains/{id}/migrate` replaces is on the chain. */
  containsDeprecatedContainers?: boolean;
}

export interface ChainElement {
  id: string;
  name: string;
  type: string;
  properties: Record<string, unknown>;
  description?: string;
  /** The container this element sits in. Absent at the chain's top level. */
  parentElementId?: string;
  /** The swimlane it belongs to. Absent until the chain has one. */
  swimlaneId?: string;
  /** Present on a container, and on every element of the flat listing that has children. */
  children?: ChainElement[];
  mandatoryChecksPassed?: boolean;
}

/**
 * What every write on `/v1/chains/{c}/elements` answers: the three lists a change touched.
 *
 * A key is **absent** rather than empty when a change produced none of that kind, so a reader takes
 * `?? []` rather than trusting the shape. Deleting an element that is already gone answers `{}`.
 */
export interface ChainDiff {
  createdElements?: ChainElement[];
  updatedElements?: ChainElement[];
  removedElements?: ChainElement[];
  /** Set by the **first** swimlane a chain gets, which is created as the default one. */
  createdDefaultSwimlaneId?: string;
  createdReuseSwimlaneId?: string;
  createdDependencies?: DependencyView[];
  removedDependencies?: DependencyView[];
}

/**
 * The two sides `POST /v1/chains/diff` compares. A side is its chain's current state unless it
 * names a snapshot.
 */
export interface ChainDifferenceRequest {
  leftChainId: string;
  leftSnapshotId?: string;
  rightChainId: string;
  rightSnapshotId?: string;
}

/** An element as the diff carries it: under the id of the chain element it was copied from. */
export interface DifferenceElement {
  originalId: string;
  name: string;
  type: string;
  properties: Record<string, unknown>;
}

/**
 * One element pair. The field sets name `name`, `description`, `type`, and each property as
 * `properties.<key>`, and an element with no pair lists every field it carries on its own side.
 */
export interface ElementDifference {
  leftElement?: DifferenceElement;
  rightElement?: DifferenceElement;
  onlyOnLeft: string[];
  onlyOnRight: string[];
  differing: string[];
}

/** A side of the diff. A chain's current state reads as snapshot `Current`. */
export interface ChainDifferenceSide extends Named {
  currentSnapshotId: string;
  currentSnapshotName: string;
}

export interface ChainDifference {
  leftEntity: ChainDifferenceSide;
  rightEntity: ChainDifferenceSide;
  elementsDifferences: ElementDifference[];
}

/** `PATCH /v1/chains/{id}/migrate`: the chain as it stands after the migration. */
export interface MigratedChainView {
  chain: ChainView & { elements: ChainElement[] };
  groupsRemoved: boolean;
}

/** One edge of a chain's element graph. `from` and `to` are element ids, never element objects. */
export interface DependencyView {
  id: string;
  from: string;
  to: string;
}

/** One element of `GET /v1/chains/{c}/elements/type/{type}`, which adds the chain's name. */
export interface ElementWithChainName extends ChainElement {
  chainName: string;
}

/**
 * One exchange property `GET /v1/chains/{c}/elements/properties/used` found in the chain's scripts.
 *
 * `relatedElements` is keyed by element id rather than being a list, and `operations` is per
 * element rather than per property: an element that both reads and writes reports `["SET", "GET"]`
 * against every property it mentions.
 */
export interface UsedProperty {
  name: string;
  source: string;
  type: string;
  relatedElements: Record<string, { id: string; name: string; type: string; operations: string[] }>;
}

/**
 * One element descriptor, as `GET /v1/library` and `GET /v1/library/{name}` both serve it.
 *
 * The two answers are the same object: `lookupElementDescriptor` reads the map the hierarchy is
 * built from, so the single-element read is a projection of the tree rather than a second
 * rendering of it. `specs/global/element-library-endpoints.spec.ts` asserts that equality.
 *
 * Only the fields a spec reads are typed. The descriptor carries twenty-three keys on every
 * element and five more on some, and typing the tail here would be a second copy of
 * `ElementDescriptor.java` to keep in step for no reader's benefit.
 */
export interface ElementDescriptorView {
  name: string;
  title: string;
  /** The behavioral kind — `trigger`, `module`, `container`, `reuse`, … — and **not** the identity:
   * `catch-2` is served with `type: "module"`. The name is what addresses an element. */
  type: string;
  folder?: string;
  deprecated: boolean;
  unsupported: boolean;
  /** Non-empty exactly when the element is served under `childElements` rather than in a group. */
  parentRestriction: string[];
  allowedChildren: Record<string, string>;
  properties: Record<string, Array<{ name: string; mandatory?: boolean }>>;
}

/**
 * `GET /v1/library`: the element palette the editor draws, in three parts.
 *
 * A group is a `LibraryElements` with the folder's fields unwrapped onto it, so it carries `name`
 * and `title` beside its own `groups` / `elements` / `childElements`. That is why the interface
 * below is recursive rather than two shapes.
 */
export interface LibraryElementGroupView extends LibraryElementsView {
  name: string;
  title: string;
  /** Set when the folder nests under another one; absent at the top level. */
  parent?: string;
}

export interface LibraryElementsView {
  groups: LibraryElementGroupView[];
  elements: ElementDescriptorView[];
  /** Keyed by element name, and a dict rather than a list. */
  childElements: Record<string, ElementDescriptorView>;
}

/**
 * One row of `GET /v1/library/elements/types`: a title to show and the type behind it.
 *
 * There is no method for that endpoint here, and the absence is deliberate. It answers the types in
 * use across the **whole catalog** — `ElementRepository.findAllGroupByType` is
 * `SELECT e.type FROM elements e GROUP BY e.type` with no chain, snapshot or caller predicate — and
 * it answers 500 for a type the library cannot name, which is a defect
 * `docs/product-defects.md` carries. A caller therefore reads it through `raw` and asserts the
 * status itself; `specs/global/element-library-endpoints.spec.ts` is the one that does.
 */
export interface ElementTypeInUse {
  elementTitle: string;
  elementType: string;
}

/**
 * The engine domain a deployment goes to.
 *
 * `default` is the only one Compose runs, and it is not a default anywhere in the API: a
 * deployment created without it is stored with an empty domain and is invisible to every engine.
 */
export const DEFAULT_DOMAIN = "default";

/** `xmlDefinition` is present on a full `GET .../snapshots/{id}` and absent everywhere else. */
export interface SnapshotView extends Named {
  xmlDefinition?: string;
  labels?: unknown[];
}

export interface DeploymentView {
  id: string;
  chainId: string;
  snapshotId: string;
  name: string;
  domain?: string;
  /** Present on a read, absent on the create response: what each engine host reports. */
  runtime?: { states: Record<string, { status: string; suspended: boolean }> };
  serviceName?: string;
}

/** `DeploymentStatus` on the engine side: `DEPLOYED`, `PROCESSING`, `FAILED`. */
export interface RuntimeDeployment {
  deploymentInfo: {
    deploymentId: string;
    chainId: string;
    chainName: string;
    snapshotId: string;
    snapshotName: string;
  };
  status: string;
  suspended: boolean;
  host?: string;
  /** Set on `FAILED` and on the retriable `PROCESSING`; absent on `DEPLOYED`. */
  errorMessage?: string;
}

/** One row of `GET /v1/catalog/domains`. `type` is `CLASSIC` or `MICRO`. */
export interface EngineDomainView extends Named {
  replicas: number;
  namespace: string;
  type: string;
  version?: string;
}

/** One row of `GET /v1/catalog/domains/{d}/engines`, which Compose never produces. */
export interface EnginePodView {
  id?: string;
  name?: string;
  ip?: string;
}

/** One row of `GET /v1/catalog/domains/{d}/engines/{host}/deployments`. */
export interface EngineDeploymentView {
  id: string;
  chainId: string;
  chainName: string;
  snapshotName: string;
  state: { status: string; suspended: boolean; errorMessage?: string };
}

/** What `POST /v1/catalog/domains/{d}/deployments/update` hands an engine. */
export interface DeploymentsUpdate {
  update: Array<{
    deploymentInfo: {
      deploymentId: string;
      chainId: string;
      chainName: string;
      snapshotId: string;
      snapshotName: string;
      createdWhen: number;
      containsCheckpointElements: boolean;
      containsSchedulerElements: boolean;
    };
    configuration?: { xml?: string };
  }>;
  stop: Array<{ deploymentInfo: { deploymentId: string; chainId: string } }>;
}

/**
 * One row of `GET /v1/catalog/events`.
 *
 * `data` is `Object` on the Java side and its shape follows `objectType`, so it is left untyped
 * here and narrowed by the one type that has a live publisher.
 */
export interface CatalogEvent {
  id: string;
  /** Epoch milliseconds, taken from the catalog's own clock when the event was queued. */
  time: number;
  /** Absent when the event was published for nobody in particular; see `EventsUpdate`. */
  userId?: string;
  objectType: "DEPLOYMENT" | "ENGINE" | "GENERIC_MESSAGE";
  data: unknown;
}

/** The `data` of a `DEPLOYMENT` event, which is the only kind this stack publishes. */
export interface DeploymentEventData {
  /** The deployment id, not the chain's and not the event's. */
  id: string;
  engineHost: string;
  state: { status: string; suspended: boolean; errorMessage?: string };
  snapshotId: string;
  chainId: string;
  chainName: string;
  domain: string;
  serviceName: string;
  /** Read off the catalog's own deployment row, so it is absent once that row is gone. */
  createdWhen?: number;
}

/**
 * The answer of `GET /v1/catalog/events`.
 *
 * `lastEventId` is the **tail of the queue**, not the last row of `events`: it is read after the
 * window filter, so it names an event this answer may well have dropped. A client polls with it
 * regardless, which is the behavior `specs/global/events.spec.ts` pins.
 */
export interface EventsUpdate {
  lastEventId: string;
  events: CatalogEvent[];
}

/** The catalog's view of one live exchange: the engine's row plus the pod and the chain name. */
export interface LiveExchangeExtView {
  exchangeId: string;
  deploymentId: string;
  sessionId: string;
  chainId: string;
  duration: number | null;
  sessionDuration: number | null;
  sessionStartTime: number | null;
  sessionLogLevel: string;
  main: boolean;
  podIp: string;
  chainName: string | null;
}

/**
 * One clause of `POST /v1/catalog/live-exchanges`.
 *
 * The JSON key is `column`, though the Java field is `feature`: `FilterRequestDTO` carries
 * `@JsonProperty("column")`. A body spelling `feature` is accepted and **silently ignored** —
 * Jackson leaves the field null, `isApplicable` is false for every filter, and the clause passes
 * every row.
 */
export interface LiveExchangeFilter {
  column: string;
  condition: string;
  value: string;
}

export type BulkSnapshotAction = "CREATE_NEW" | "LAST_CREATED";

export interface BulkDeploymentRow {
  chainId: string;
  chainName: string;
  status: "CREATED" | "IGNORED" | "FAILED_DEPLOY" | "FAILED_SNAPSHOT";
  errorMessage?: string;
  domain: { name: string; replicas: number; type: string };
}

/** `REWRITE` replaces the chains a micro domain runs; `APPEND` adds to them. */
export type CustomResourceMode = "REWRITE" | "APPEND";

/** One service `POST /v1/systems/discovery` created, as `GET /v1/systems/discovery` lists it. */
export interface DiscoveredServiceView {
  id: string;
  name: string;
  /** The Kubernetes Service the catalog found the specification on. */
  internalServiceName: string;
  serviceGroups: {
    id: string;
    name: string;
    synchronization: boolean;
    specificationId?: string;
    specificationName?: string;
  }[];
  createdWhen: number;
}

/**
 * What the last discovery run did. The group and specification ids list only what it added to
 * services an earlier run had discovered; a newly discovered service is named in
 * `discoveredSystemIds` alone.
 */
export interface DiscoveryResult {
  discoveredSystemIds: string[];
  discoveredGroupIds: string[];
  discoveredSpecificationIds: string[];
  updatedSystemsIds: string[];
  errorMessages: { serviceName: string; message: string }[];
}

/**
 * One row of what a chain import did. `status` is `CREATED`, `UPDATED`, `IGNORED` or `ERROR`.
 *
 * The name is what makes the row worth asserting: an import that imported nothing answers with an
 * **empty** `chains` array and HTTP 200, so "it completed" is not evidence that anything happened.
 */
export interface ImportChainResult {
  id: string;
  name?: string;
  status: string;
  errorMessage?: string;
  deployAction?: string;
}

/** `POST /v1/catalog/import`. 200, or **207** as soon as one row carries `ERROR`. */
export interface ImportResult {
  chains: ImportChainResult[];
}

/** `POST /v1/catalog/import/preview`: the same rows before anything is written. */
export interface ImportPreviewResult {
  chains: Array<{ id: string; name?: string; usedSystems?: unknown[]; deployAction?: string }>;
}

/** One import instruction. `name` is resolved from the live entity, so a dead id has none. */
export interface ImportInstructionView {
  id: string;
  name?: string;
  overriddenById?: string;
  overriddenByName?: string;
  labels: string[];
  modifiedWhen?: number;
  preview: boolean;
}

/** The instruction sections, each holding the actions its entity type supports. */
export interface ImportInstructionSection {
  delete?: ImportInstructionView[];
  ignore?: ImportInstructionView[];
  override?: ImportInstructionView[];
}

/**
 * `GET /v1/catalog/import-instructions`, and the answer to search and filter as well.
 *
 * Five sections, and the exported YAML carries **seven** — `contextServices` and `mcpServices` have
 * no field here — so the two readings of the same configuration disagree.
 */
export interface ImportInstructions {
  chains: ImportInstructionSection;
  services: ImportInstructionSection;
  specificationGroups: ImportInstructionSection;
  specifications: ImportInstructionSection;
  commonVariables: ImportInstructionSection;
}

/** The create and update body. `overriddenBy` is the id an `OVERRIDE` redirects the import to. */
export interface ImportInstructionRequest {
  id: string;
  entityType: "CHAIN" | "SERVICE" | "COMMON_VARIABLE" | "SECRET";
  action: "IGNORE" | "OVERRIDE";
  overriddenBy?: string;
}

/** What an upload **did**, which is only ever a delete: the stored actions produce no rows. */
export interface ImportInstructionResult {
  id: string;
  name?: string;
  entityType?: string;
  status?: string;
  errorMessage?: string;
}

/** The delete body. Every key is a set of entity ids, and an id nothing answers to is not an error. */
export interface DeleteInstructionsRequest {
  chains?: string[];
  services?: string[];
  commonVariables?: string[];
}

/** A filter row. The JSON key is `column`; sending Java's own `feature` answers **500**. */
export interface FilterRequest {
  column: string;
  condition: string;
  value: string;
}

/** What the sweep and the residue check found, in a form a failure message can print. */
export interface Residue {
  kind: string;
  id: string;
  name: string;
  /**
   * What the entity has to be deleted through, when its name is not enough.
   *
   * A secured variable is the case: it is addressed as `{secret}/{name}` and the delete takes the
   * secret in the path, so the sweep cannot reconstruct it from the name alone.
   */
  scope?: string;
}

/** A service, as `GET /v1/systems/{id}` answers it. */
export interface SystemView extends Named {
  type: string;
  description?: string | null;
  /**
   * The environment a chain calling this service resolves to.
   *
   * Set for an `EXTERNAL` service and stored on the row; **derived** for `INTERNAL` and
   * `IMPLEMENTED`, where `SystemMapper.getActiveEnvironmentId` answers the first environment
   * whatever the row carries.
   */
  activeEnvironmentId?: string | null;
  labels?: unknown[];
  /** The chains whose elements reference the service; only a search or filter asked for it carries them. */
  chains?: Named[];
}

/** A service a chain's elements reference, with the specifications they name under it. */
export interface UsedSystem {
  systemId: string;
  usedSystemModelIds: string[];
}

/**
 * One element calling a service of the requested type. `service` and `version` are resolved from
 * the ids the element carries, and each is absent when the catalog holds no such row.
 */
export interface SystemUsage {
  service?: string;
  version?: string;
  method?: string;
  path?: string;
  chainId: string;
  chainName: string;
  elementId: string;
  elementName: string;
}

/** An environment under a service. Nothing but deleting its service removes it in bulk. */
export interface EnvironmentView extends Named {
  systemId: string;
  address?: string;
  labels?: string[];
  sourceType?: string;
  properties?: Record<string, unknown> | null;
  defaultProperties?: Record<string, unknown>;
}

/** One operation of an imported specification. */
export interface OperationView extends Named {
  method: string;
  path: string;
  modelId: string;
}

/** `GET /v1/operations/{id}`: the listing row plus the OpenAPI fragment the operation came from. */
export interface OperationDetail extends OperationView {
  specification: Record<string, unknown>;
}

/**
 * `GET /v1/operations/{id}/info`: the fragment, plus the JSON Schemas the catalog derived from it.
 *
 * `requestSchema` is keyed by **content type** and `responseSchemas` by **response code** and then
 * by content type. Neither is the OpenAPI fragment: the values are draft-07 documents the catalog
 * built, carrying a `$schema` and an `$id` the source document never had.
 */
export interface OperationInfo {
  id: string;
  specification: Record<string, unknown>;
  requestSchema: Record<string, unknown>;
  responseSchemas: Record<string, Record<string, unknown>>;
}

/** `GET /v1/operations/{id}/schemas`: the same two maps, on the operation's listing fields. */
export interface OperationSchemas extends Named {
  method: string;
  path: string;
  requestSchema: Record<string, unknown>;
  responseSchemas: Record<string, Record<string, unknown>>;
}

/** A specification: one version inside a group, with the operations the import produced. */
export interface SpecificationView extends Named {
  specificationGroupId: string;
  deprecated: boolean;
  version: string;
  source: string;
  systemId: string;
  operations?: OperationView[];
  /** Absent until a `PATCH` writes one; the catalog answers `[]` rather than omitting the key. */
  labels?: Array<{ name: string; technical?: boolean }>;
}

/** A specification group: the versions of one API under a service. */
export interface SpecificationGroupView extends Named {
  systemId: string;
  synchronization: boolean;
  specifications?: SpecificationView[];
}

/** `POST /v1/specificationGroups/import` and `POST /v1/import` both answer this. */
export interface SpecificationImportView {
  id: string;
  specificationGroupId?: string;
  done: boolean;
  warningMessage?: string;
}

/**
 * A started import, plus what its group already held when it was posted.
 *
 * `existingModelIds` is the client's own field and not the catalog's. Without it the wait cannot
 * tell an import that wrote a specification from one that wrote nothing into a group that was
 * already full, which is every import after the first into one group.
 */
export interface StartedSpecificationImport extends SpecificationImportView {
  existingModelIds: string[];
}

/** What a finished specification import actually produced, which is what the wait answers with. */
export interface ImportedSpecification {
  view: SpecificationImportView;
  specifications: SpecificationView[];
  /** Across every specification in the group: an import that parsed nothing has none. */
  operations: OperationView[];
}

/** One clause of `POST /v1/catalog/context-system/filter`, which takes them as a bare list. */
export interface ContextSystemFilter {
  column: string;
  condition: string;
  value: string;
}

/** A row of an import or an import preview: what the archive holds and what it would do to it. */
export interface ImportSystemResult {
  id: string;
  name: string;
  modified: number;
  requiredAction: string;
}

/** A context service. Its own entity family, not an `IntegrationSystem`. */
export interface ContextSystemView extends Named {
  description?: string | null;
  labels?: unknown[];
}

/** An MCP service. `identifier` is the MCP server name and is not the id. */
export interface McpSystemView extends Named {
  description?: string | null;
  identifier?: string;
  instructions?: string;
  labels?: unknown[];
}

/** `StringResponse`: the catalog's one-field answer to a write that has nothing else to say. */
export interface StringResponse {
  response: string;
}

/** What an import wrote, one row per variable in the file. `status` is `CREATED` or `UPDATED`. */
export interface ImportVariableResult {
  name: string;
  value: string;
  status: string;
}

/** The same rows before anything is written, each carrying what is stored today. */
export interface ImportVariablePreview {
  name: string;
  value: string;
  /** The stored value, or `""` for a variable that does not exist yet — not `null` and not absent. */
  currentValue: string;
}

/** One secret as `GET /v2/secured-variables` reports it: names, never values. */
export interface SecretView {
  secretName: string;
  variablesNames: string[];
  /** The `qip-secured-variables-v2` secret the `/v1` surface addressed. */
  defaultSecret: boolean;
  /** True for the default secret while `cip.variables.default-secret.enabled` is false. */
  disabled: boolean;
}

/** A secured variable is addressed by its secret and its name. Its value is never readable. */
export interface SecuredVariableRef {
  secret: string;
  name: string;
}

/** One audit-log row. `entityName` is what the run token is matched against. */
export interface ActionLogEntry {
  id: string;
  actionTime: number;
  entityType?: string;
  entityId?: string;
  entityName?: string;
  parentType?: string;
  parentId?: string;
  parentName?: string;
  requestId?: string;
  operation: string;
  userId?: string;
  username?: string;
}

/** One filter clause. The column decides which conditions it accepts — see `ActionLogFilterColumn`. */
export interface ActionLogFilter {
  column: string;
  condition: string;
  value: string;
}

/**
 * What `POST /v1/catalog/actions-log` answers.
 *
 * `recordsAfterRange` counts the rows **older** than the window that the same filters match, so it
 * is a "there is more behind this page" hint rather than a total.
 */
export interface ActionLogPage {
  actionLogs: ActionLogEntry[];
  recordsAfterRange: number;
}

/** A masked field, as the create, the update, and the listing all report it. */
export interface MaskedFieldView extends Named {
  createdWhen?: number;
  modifiedWhen?: number;
}

/** The seven knobs a chain's logging carries. `sessionsLoggingLevel` is the one the seed raises. */
export interface LoggingProperties {
  sessionsLoggingLevel: string;
  logLoggingLevel: string;
  logPayload: string[];
  /** Deprecated since 24.4 and still serialized, so it is part of the shape a spec reads back. */
  logPayloadEnabled: boolean;
  dptEventsEnabled: boolean;
  maskingEnabled: boolean;
  sessionLogDetails: string;
}

/**
 * The three layers `GET /v1/chains/{id}/properties/logging` answers with.
 *
 * `fallbackDefault` is always present and is the compiled-in default. `consulDefault` appears only
 * while a `default-settings` key exists, and `custom` only while this chain has properties of its
 * own — both keys are **absent** rather than null, which is what a spec asserting a deletion reads.
 */
export interface LoggingPropertiesSet {
  fallbackDefault: LoggingProperties;
  consulDefault?: LoggingProperties;
  custom?: LoggingProperties;
}

/**
 * The two paths `ChainRolesController` is mapped at, which serve the same three operations.
 *
 * `@RequestMapping(value = {"/v1/catalog/chains/roles", "/v1/catalog/chains/access-control"})`, so
 * every one of its operations has **two** rows in the operation registry and a spec that reaches
 * only one path leaves the other three unreached whatever it asserts.
 */
export const CHAIN_ROLES_PATHS = [
  "/v1/catalog/chains/roles",
  "/v1/catalog/chains/access-control",
] as const;

export type ChainRolesPath = (typeof CHAIN_ROLES_PATHS)[number];

/**
 * One clause of the chain-element search.
 *
 * `column` and `condition` are both required and both validated: `ChainRolesService.validateFilters`
 * rejects a missing one, and rejects a condition the column cannot apply — `CHAIN` takes only
 * `CONTAINS`, `DOES_NOT_CONTAIN`, `STARTS_WITH` and `ENDS_WITH`. The values are the server's enum
 * names, matched case-insensitively by Spring's converter.
 */
export interface ChainElementFilter {
  column: string;
  condition: string;
  value: string;
}

/** The body `POST /v1/catalog/chains/roles` takes. Every field has a server-side default. */
export interface ChainElementSearch {
  offset?: number;
  limit?: number;
  filters?: ChainElementFilter[];
}

/**
 * One row of the search: an HTTP trigger, the chain holding it, and a **projection** of its
 * properties.
 *
 * `properties` is not the element's property map. `ElementFilterRepositoryImpl.filterElementProperties`
 * keeps eight keys — `roles`, `contextPath`, `privateRoute`, `externalRoute`,
 * `integrationOperationPath`, `integrationSpecificationId`, `accessControlType`, `abacParameters` —
 * and drops everything else, so a caller reading `connectTimeout` off a row reads `undefined`.
 */
export interface ChainRolesRow {
  chainId: string;
  chainName: string;
  elementId: string;
  elementName: string;
  /** One entry per engine pod holding the chain, or the single value `DRAFT` for a chain on none. */
  deploymentStatus: string[];
  unsavedChanges: boolean;
  properties: Record<string, unknown>;
  modifiedWhen: number;
}

/** `offset` is `request.offset + rows.length`, a cursor for the next page rather than a total. */
export interface ChainRolesPage {
  offset: number;
  roles: ChainRolesRow[];
}

/** One element of a bulk roles update. `roles` is required; omitting it answers 400. */
export interface ChainRolesUpdate {
  elementId: string;
  roles: string[];
}

/**
 * The route a collision check describes, minus the path, which is the call's own argument.
 *
 * `isExternalRoute` and `isPrivateRoute` are declared by the controller and read by nothing:
 * `ElementRouteUtils.intersects` compares the path and the methods and looks at neither flag.
 * They are here so a spec can send them and pin that they change no answer.
 */
export interface RouteQuery {
  /** Empty means every method — the server's own default, not a filter that matches nothing. */
  httpMethods?: string[];
  isExternalRoute?: boolean;
  isPrivateRoute?: boolean;
}

/** One row of `findRouteDeployments`: the trigger's path, and the deployment serving it. */
export interface RouteDeployment {
  path: string;
  deployment: DeploymentView;
}

/** `ValidationState`: a validation that has never run reports `NOT_STARTED` rather than nothing. */
export type ValidationState = "OK" | "NOT_STARTED" | "IN_PROGRESS" | "FAILED";

/** One entity a validation flagged. The element half is unset for a `CHAIN`-typed validation. */
export interface ValidationChainEntity {
  chainId: string;
  chainName: string;
  elementId?: string;
  elementName?: string;
  elementType?: string;
  properties?: Record<string, unknown>;
}

/**
 * A diagnostic rule, its last run, and what that run found.
 *
 * `chainEntities` is **not** the same thing in every answer: the unfiltered listing carries
 * `alertsCount` and an empty entity list, while a search or a filter carries the entities it
 * matched. `DiagnosticController.clearEntitiesByState` blanks both while the rule is anything but
 * `OK`, so a count read off a `FAILED` or `IN_PROGRESS` rule is zero by design.
 */
export interface DiagnosticValidation {
  id: string;
  title: string;
  description: string;
  suggestion: string;
  entityType: "CHAIN" | "CHAIN_ELEMENT";
  implementationType: "BUILT_IN" | "PLUGIN";
  severity: "WARNING" | "ERROR";
  properties: Record<string, unknown>;
  status: { state: ValidationState; startedWhen?: string; message?: string };
  alertsCount: number;
  chainEntities: ValidationChainEntity[] | null;
}

/** The diagnostic search body. `searchString` wins over `filters` when both are sent. */
export interface DiagnosticFilterRequest {
  searchString?: string;
  filters?: FilterRequest[];
}

/** The two languages every generated diagram comes back in, keys of `diagramSources`. */
export type DiagramLang = "PLANT_UML" | "MERMAID";

/** The two views the generator offers. `SIMPLE` drops the elements listed in the spec's header. */
export type DiagramMode = "FULL" | "SIMPLE";

/** One sequence diagram. `snapshotId` is absent on the chain form, where the key is null. */
export interface SequenceDiagram {
  chainId: string;
  snapshotId?: string;
  diagramSources: Record<DiagramLang, string>;
}

/**
 * A detailed design document, as `GET /v1/detailed-design/chains/{id}` answers it.
 *
 * `document` is the template rendered to markdown and then reformatted by flexmark, so it is
 * asserted by structure rather than byte for byte. The two diagram fields are the `SIMPLE` view,
 * built by the same generator the design endpoints serve.
 */
export interface DetailedDesign {
  document: string;
  simpleSeqDiagramPlantuml: string;
  simpleSeqDiagramMermaid: string;
  triggerSpecifications: Array<{
    serviceName: string;
    specificationName: string;
    specificationId: string;
    fileExtension: string;
    specificationContent: string;
  }>;
}

/**
 * A detailed-design template.
 *
 * `builtIn` is written by the **listing** and by nothing else — a create and a read-by-id leave the
 * key out — so a caller telling the two apart has to go through `listDesignTemplates`.
 */
export interface DesignTemplateView extends Named {
  content?: string | null;
  builtIn?: boolean;
  createdWhen?: number;
}

// ---------------------------------------------------------------------------
// The `/v2` and `/v3` surfaces
// ---------------------------------------------------------------------------
//
// These are separate controllers rather than a rewrite of the `/v1` ones, and `specs/api/
// v2-v3-controllers.spec.ts` says per family which of the two the product treats as authoritative.
// The types are named `*V2` / `*V3` for that reason: a `FolderItem` and a `FolderView` are not the
// same document, and one of the two is what the UI's tree is drawn from.

/** One row of `POST /v2/folders/list`, and what a v2 folder read answers. `itemType` tells them apart. */
export interface CatalogItemV2 extends Named {
  parentId?: string;
  itemType: "FOLDER" | "CHAIN";
  /** Chains only: a folder row leaves the key out entirely. */
  labels?: unknown[];
}

/** The body `POST /v2/folders/list` takes. `folderId` absent means the root, never "everything". */
export interface ListFolderRequestV2 {
  folderId?: string;
  searchString?: string;
  filters?: FilterRequest[];
}

/** A snapshot as `GET /v2/catalog/snapshots/{id}/full` answers it: the graph, and no Camel XML. */
export interface SnapshotFullV2 extends Named {
  elements: ChainElement[];
  dependencies: DependencyView[];
  labels?: unknown[];
  defaultSwimlaneId?: string;
  reuseSwimlaneId?: string;
  chain?: Named;
}

/** What `POST /v2/catalog/actions-log` answers: a page, and the offset the next page starts at. */
export interface ActionLogPageV2 {
  /** The request's own offset plus the number of rows returned — a cursor, not a total. */
  offset: number;
  actionLogs: ActionLogEntry[];
}

/** `POST /v2/common-variables/import`, which wraps what the deprecated `/v1` form answers bare. */
export interface ImportVariablesResultV2 {
  /** Absent rather than empty when nothing was written: the DTO serializes non-empty only. */
  variables?: ImportVariableResult[];
  instructions?: ImportInstructionResult[];
}

/** The 202 body of `POST /v2/import`. The id key is `importId`, and `href` repeats the `Location`. */
export interface ImportAcknowledgeV2 {
  importId: string;
  href: string;
}

/** The 202 body of `POST /v3/import`. No `href`: v3 has no redirect chain. */
export interface ImportCommitResponseV3 {
  importId: string;
}

/** One row of `GET /v3/import`. The same store the deprecated `/v2` import writes into. */
export interface ImportSessionSummaryV3 {
  id: string;
  modifiedWhen?: number;
  completion: number;
  done: boolean;
  error?: string;
}

/** `GET /v3/import/{importId}`. `result` appears only once the import produced one. */
export interface ImportSessionV3 {
  result?: ImportResult;
  completion: number;
  done: boolean;
  error?: string;
}

/** What `POST /v3/import/preview` answers. `exists` is read against the catalog, not the archive. */
export interface ImportPreviewV3 {
  chains?: Array<{ id: string; name: string; exists: boolean; deployAction?: string }>;
  systems?: unknown[];
  variables?: unknown[];
  instructions?: unknown;
}

/** One element pair of a v3 chain diff, and the three ways the two sides can disagree. */
export interface ElementDifferenceV3 {
  leftElement?: { originalId: string; type: string; name: string; properties?: Record<string, unknown> };
  rightElement?: { originalId: string; type: string; name: string; properties?: Record<string, unknown> };
  onlyOnLeft: string[];
  onlyOnRight: string[];
  differing: string[];
}

/** `POST /v3/import/chains/diff`. Left is what the catalog holds; right is what the archive carries. */
export interface EntityDifferenceV3 {
  leftEntity?: Named & { currentSnapshotId?: string; currentSnapshotName?: string };
  rightEntity?: Named & { currentSnapshotId?: string; currentSnapshotName?: string };
  elementsDifferences: ElementDifferenceV3[];
}

/** One configuration document of a rollout package — the same four keys an exported document has. */
export interface RolloutConfigurationItem {
  id: string;
  $schema: string;
  name: string;
  content: Record<string, unknown>;
}

/** One resource of a rollout package. `encoded` decides whether `resourceContent` is base64. */
export interface RolloutResourceItem {
  id?: string;
  name: string;
  resourceContent: string;
  encoded?: boolean;
}

/** The body `PUT /v3/rollout-import/{snapshotId}` takes. */
export interface RolloutImportRequest {
  id?: string;
  packageContent?: {
    name?: string;
    version?: string;
    configurations?: RolloutConfigurationItem[];
    resources?: RolloutResourceItem[];
  } | null;
}
