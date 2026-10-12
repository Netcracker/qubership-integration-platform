/**
 * The coverage registry: one row per element family and per axis value the schemas declare.
 *
 * The registry is the suite's definition of done, so it has to be auditable in both
 * directions. `coverage.spec.ts` reads the schemas and fails when a row is missing or names an
 * axis value the platform no longer has; `reconcile()` reads the run's JSON report and fails when
 * a row claims `covered` with no passing test behind it. Neither check trusts the other, and
 * neither trusts a human's memory.
 *
 * The module imports nothing at runtime, on purpose: `registry/reconcile.mjs` loads it under
 * `node --experimental-strip-types` after the run, where a `@playwright/test` import would not
 * resolve — and where a `.js` specifier pointing at a `.ts` file resolves to nothing at all. That
 * second half is why the report walk lives here rather than beside the types in `json-report.ts`,
 * and why `reconcile()` takes the operation rows pre-keyed instead of importing `operations.ts`:
 * `support/report.ts` is passed its component tags for exactly the same reason.
 *
 * The two declaration helpers live where they can import Playwright: `covers()` in `covers.ts`,
 * and its operation-side twin `noteReached()` in `reached.ts`.
 */
import type { SchemaValue } from "./discriminators.js";
import type { JsonReport, JsonSuite, JsonSpec, JsonTest } from "./json-report.js";
import type { Target } from "../env/target.js";

export type { SchemaValue };

// The reporter's wire format moved to `json-report.ts`, where the case catalog reads it too.
// Re-exported so nothing that imports it from here has to move with it.
export type {
  JsonConfig,
  JsonReport,
  JsonResult,
  JsonSpec,
  JsonSuite,
  JsonTest,
} from "./json-report.js";

/** `1` is the release floor — every element at its defaults. `2` is everything else. */
export type Tier = 1 | 2;

/**
 * Two states, because the registry only ever used two.
 *
 * A `superseded` member was declared for deprecated elements whose successor carries the case, and
 * no row was ever filed under it while 18 deprecated schemas sat at `not-covered` with a reason.
 * A status nothing writes is a status nothing checks, so the reason on those rows is the record.
 */
export type Status = "covered" | "not-covered";

/** An `element` row has no `axisPath`; an `axis` row names one value of one property. */
export type Kind = "axis" | "element";

export interface RegistryEntry {
  family: string;
  kind: Kind;
  /** The normalized axis path, e.g. `idempotency/actionOnDuplicate`. Axis rows only. */
  axisPath?: string;
  /** The bare property name. Derived from `axisPath` when a row omits it — readability only. */
  axis?: string;
  value?: SchemaValue;
  tier: Tier;
  status: Status;
  tags: readonly string[];
  /** Required whenever `status` is `not-covered`. */
  reason?: string;
  /**
   * The one target whose run can prove the row. Absent means both, and reconciliation reads the
   * row only against a run on its target.
   */
  target?: Target;
}

const NOT_COVERED: Status = "not-covered";

// Proportional: the default plus one non-default value per axis, across the Kafka sender's two
// serializer axes and the trigger's two deserializer axes. The non-default value, ByteArray*, turned
// out indistinguishable from the String* default (R_KAFKA_SERIALIZER_INDISTINGUISHABLE), so only the
// default is covered on each axis. A case per remaining value mostly proves that a mismatched type
// fails to serialize rather than anything about the platform, at 44 cases across four axes.
const R_KAFKA_SERIALIZER_PROPORTIONAL =
  "proportional coverage: only the org.apache.kafka.common.serialization.String* default is covered " +
  "per axis, because the intended non-default case, ByteArray*, turned out indistinguishable from it " +
  "(R_KAFKA_SERIALIZER_INDISTINGUISHABLE), and most of the remaining (de)serializer values would only " +
  "prove that a mismatched type fails to serialize";

const R_BROKER_IDEMPOTENCY =
  "no case: the idempotency cases run on http-trigger (specs/runtime/http-trigger-idempotency.spec.ts), " +
  "and none was built for the broker triggers";

const R_PROPAGATE_CONTEXT =
  "no case: context-propagation.schema.yaml puts propagateContext on nine elements, and none was built " +
  "for the broker senders";

const R_KAFKA_CONSUMER_CONSISTENCY =
  "the two values select a NetCracker Cloud Blue-Green consumer mode " +
  "(com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode) with no counterpart on this stack";

const R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH =
  "exchangeType and deadLetterExchangeType are query: true in rabbitmq-trigger-2/description.yaml and " +
  "reach the compiled xmlDefinition through template.hbs's {{query}} call, but the brokers seed declares " +
  "the exchange itself, off the fixture YAML, so a runtime case cannot tell one value from another; a " +
  "case belongs on the compile (the registry's third shape, @catalog), and none was built";

const R_RABBITMQ_ACK_NONE =
  "RabbitMqTriggerProcessor.immediateAck special-cases only AcknowledgeMode.MANUAL (the positive ack " +
  "the MANUAL case exercises); NONE leaves acking to Spring's container, which never sends one, and no " +
  "case covers the redelivery contract that leaves";

const R_KAFKA_PLAINTEXT_ONLY =
  "the overlay's Kafka listener is PLAINTEXT (infrastructure/docker-compose.kafka.yml), so no SASL " +
  "mechanism and no securityProtocol or sslProtocol beyond PLAINTEXT is reachable locally; Kafka over " +
  "SSL was deferred on 2026-09-07";
const R_KAFKA_OFFSET_RESET_NONE =
  "measured: with a fresh consumer group and no backlog, autoOffsetReset=none reads a message the " +
  "same way earliest and latest do — the exception it alone can raise needs a record already on the " +
  "partition before the group's first subscribe, an ordering the shared brokers corpus cannot arrange";

const R_KAFKA_OFFSET_RESET_EARLIEST_INDISTINGUISHABLE =
  "measured, the same run R_KAFKA_OFFSET_RESET_NONE cites: with a fresh consumer group and no " +
  "backlog, autoOffsetReset=earliest reads a message the same way the schema's default, latest, " +
  "does — kafka-trigger-2's own main case already covers that default, so this axis follows " +
  "R_KAFKA_SERIALIZER_PROPORTIONAL's precedent: only the default is actually covered";

const R_KAFKA_SERIALIZER_INDISTINGUISHABLE =
  "the published message's bytes are valid UTF-8, so a case cannot tell ByteArray(De)serializer " +
  "apart from the String default it would fall back to if the axis were ignored; the case still runs " +
  "to prove the element does not throw, but carries no covers() call";

const R_PUBSUB_ACKMODE_NONE_INDISTINGUISHABLE =
  "measured: the embedded google-cloud-pubsub client's lease auto-extension suppresses any " +
  "observable redelivery within a bounded test window, so a case sees the same one session, no " +
  "failed elements AUTO's case already shows; the case still runs, without a covers() call";

// `async-api-trigger`'s protocol axis is covered by one kafka and one amqp case in
// `specs/brokers/async-api.spec.ts`; the element row and its `systemType: EXTERNAL` row close as a
// byproduct of building those two chains. Its method, systemType, idempotency and acknowledgeMode
// axes have no case.
const R_ASYNC_API_TRIGGER_METHOD_V3 =
  "the two async-api-trigger cases exercise one AsyncAPI 2.6 document each, which resolves to " +
  "subscribe (kafka) and publish (amqp, via AMQPSpecificationResolver.getMethod: a channel's " +
  "subscribe: block with no v3 action reports PUBLISH, not SUBSCRIBE — measured, not a typo here); " +
  "send and receive need an AsyncAPI 3.0 document, and no case builds one for the trigger";
const R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE =
  "template.hbs never reads integrationOperationMethod and OperationElementPropertiesBuilder " +
  "branches only on integrationOperationProtocolType, so the trigger compiles to the same step " +
  "whatever this axis holds; the kafka case resolves to subscribe and its amqp case to " +
  "publish only because KafkaSpecificationResolver and AMQPSpecificationResolver invert getMethod " +
  "for the same subscribe-only channel shape, not because either case's route reads the value";
const R_ASYNC_API_TRIGGER_SYSTEM_TYPE =
  "EndpointHelperSource.integrationEndpoint, which kafka and amqp both resolve through, needs only " +
  "an activated environment with a non-blank address and never reads systemType, so INTERNAL and " +
  "IMPLEMENTED behave exactly like the EXTERNAL case; no case covers the other two values";
const R_ASYNC_API_TRIGGER_UNCOVERED = "no case: the async-api-trigger cases vary only the protocol axis";
// The `script` element has no axis row: it is covered at tier 1 by a fixture chain, and none of the
// six axes the script specs sweep is a discriminator the schemas declare, so the extractor produces
// no row for any of them. What those specs settled is written in their headers:
// `specs/api/script-source.spec.ts` and
// `specs/runtime/script-{exchange,libraries,failures,in-container,external-library}.spec.ts`. Two
// facts belong here rather than there:
//
// - `propertiesFilename`, with `exportFileExtension` and `propertiesToExportInSeparateFile`,
//   describes an archive and not a run, so it is not an axis even though it reads like one. The
//   exporter lifts `script` into `resources/script-<elementId>.groovy` and writes the name back;
//   the importer reads the file into `script` and drops the name. A chain on the platform always
//   carries the script inline. `specs/api/script-source.spec.ts` asserts that round trip and
//   declares no `covers()`, because the `script` row is `@engine` and that spec deploys nothing.
// - **No two scripts in one chain under `fixtures/script/` carry the same text**, and the comment
//   line at the top of each keeps them apart; `specs/schema/fixtures.spec.ts` refuses a duplicate.
//   Each chain's Camel context has its own compiled-script cache, keyed on the trimmed source, so
//   two identical scripts in one chain share one compiled class, and an assertion about one of them
//   can be satisfied by the compilation of the other. Do not deduplicate them.
//
// Two deliberate gaps, both decided rather than deferred.
const R_JMS =
  "JMS is out of scope: no broker in infrastructure/ and no provider client jar in the engine";
const R_MAIL = "not tested, decided 2026-09-07: the stack has no mail sink";

const R_MAAS_AGENT_ABSENT =
  "no MaaS agent reachable locally: maas.agent.url resolves empty (MAAS_AGENT_URL unset in every " +
  "compose env file), measured 500 on POST /v1/maas-actions/kafka and /rabbitmq against the running " +
  "stack";

// Value unions have no rows, and none should be added per element. A branch set with no `const`
// (a literal or a `#{variable}` placeholder) is not an axis, so the extractor skips it, and every
// such property resolves through one mechanism: the engine substitutes the route XML at deploy.
// `specs/runtime/placeholder.spec.ts` covers that class once — resolution, a change under a live
// chain, and a variable missing at deploy.
// The secured-variable placeholder is not covered, and the registry has no row to say so: engine's
// `VariablesService.pollSecuredVariables()` reads secrets through `KubeOperator`, which returns
// nothing on Compose, while the catalog keeps them in `LocalDevKubeSecretOperator`.

const ENGINE: readonly string[] = ["@engine"];
const CATALOG: readonly string[] = ["@catalog"];

// `http-trigger`'s access control rows are covered on the compile, by
// `specs/api/http-trigger-access-snapshot.spec.ts`, and tagged `@catalog` for that reason. Their
// runtime half is not covered, and a row holds one status: the engine is `anyRequest().permitAll()`;
// NONE, RBAC and ABAC give an identical response and an identical trace on this stack.
// Every value of its two handler axes, `script` included, belongs to
// `specs/runtime/http-trigger-handlers.spec.ts`.
// Its `systemType` EXTERNAL row is covered on the compile and tagged `@catalog`; INTERNAL and
// IMPLEMENTED compile alike, so neither is covered, for `http-trigger` or `service-call`.
// Its `correlationIdPosition` rows are covered by `specs/runtime/http-trigger-idempotency.spec.ts`,
// and `service-call`'s by `specs/runtime/service-call-axes.spec.ts`.
const R_RECEIVE_CORRELATION_ID =
  "no template and no engine class reads receiveCorrelationId: a correlationIdPosition and a correlationIdName " +
  "switch the correlation id on, so a case would pass for true and false alike";

// `service-call`'s http and graphql protocols belong to `specs/runtime/service-call-sync.spec.ts`.
// Its soap and grpc rows are gaps somebody decided on.
const R_SOAP =
  "a WSDL import creates an http service, so a SOAP call compiles as http and is covered there; " +
  "a soap value (written by the VS Code WSDL import) has no branch in service-call/template.hbs, and no case covers it";
const R_GRPC =
  "no answering endpoint: the testing service mocks HTTP only and no gRPC server runs in the stack; " +
  "the .proto import itself works";
// Its other axes belong to `specs/runtime/service-call-axes.spec.ts`, except kafka and amqp, which
// the broker sweep covers. Four more gaps somebody decided on:
const R_M2M = "needs an identity provider to issue the machine-to-machine token, and the stack has none";
const R_SERVICE_CALL_METHOD_INDISTINGUISHABLE =
  "for kafka and amqp, template.hbs writes this axis into one exchange property, serviceCallMethod, " +
  "then removes it again at the end of the step with nothing reading it in between — no Java source " +
  "under engine/, micro-engine/ or integration-build-pipeline/ references serviceCallMethod; the " +
  "kafka and amqp destination comes entirely from integrationOperationPath, so a case cannot tell " +
  "subscribe, publish, send or receive apart from one another";
const R_SYSTEM_TYPE_ALIKE =
  "EndpointHelperSource.integrationAddress yields the environment address for INTERNAL and IMPLEMENTED alike " +
  "(INTERNAL only refuses a blank one), so a case would pass for either value";
const R_SERVICE_CALL_IMPLEMENTED =
  "the element form offers a service call no implemented service (ServiceField.tsx), and service-call/template.hbs " +
  "tells EXTERNAL from the rest only, so the value compiles as INTERNAL and a case would pass for either";
const R_SERVICE_CALL_VALIDATION_TYPE =
  "the one value the service-call schema allows, and service-call/template.hbs reads only an entry's code, contentType " +
  "and schema, so no case can tell it from another value";

// `condition`, `try-catch-finally-2` and `circuit-breaker-2` branch on data, not on a discriminator,
// so which child runs is behavioral and has no row: `specs/runtime/control-flow.spec.ts` covers each
// child of a condition and each catch of a try-catch-finally, and closes their element rows. The
// breaker's one axis, `slidingWindowType`, is covered there too.

// `specs/runtime/misc-elements.spec.ts` covers `http-sender`, `graphql-sender`, `log-record`,
// `context-storage` and `mcp-trigger`. `swimlane` is covered on the catalog by `specs/api/elements.spec.ts`.
const R_MCP_IDEMPOTENCY =
  "not run on mcp-trigger: the shared idempotency partial branches on the element type (ignore answers 202 on http-trigger only), " +
  "and a tools/call carries no header for keyExpression, so the http-trigger cases do not prove these rows; no case covers them";
const R_SENDER_CORRELATION =
  "no case covers it: the sender reads correlationIdPosition through the same two processors as service-call, " +
  "and the cases in specs/runtime/service-call-axes.spec.ts cover the service-call rows only";
const R_CHECKPOINT_EXTERNAL_ROUTE =
  "the one value the checkpoint schema allows, and it does not reach the compiled retry route, " +
  "so no case can tell it from another value";
const R_SWIMLANE_COLOR =
  "design-only: swimlane has no template under elements/, nothing in the catalog or the engine reads color, and the UI draws it";

// The elements with no axis fall into three groups.
// - Run as a child of a covered parent, and covered through its trace: `if` and `else`, `when` and
//   `otherwise`, `main-split-element-2` and `split-element-2` (`specs/runtime/routing.spec.ts` and
//   `control-flow.spec.ts`); `try-2`, `catch-2`, `finally-2` and `on-fallback-2`
//   (`control-flow.spec.ts`); `async-split-element-2` (`plain-elements.spec.ts`).
// - A case of its own: `header-modification`, `mapper-2`, `reuse`, `reuse-reference`,
//   `chain-trigger-2` and `chain-call-2` at tier 1; `file-read`, `file-write`, `xslt` and
//   `split-async-2` in `specs/runtime/plain-elements.spec.ts`; `checkpoint` in
//   `specs/runtime/checkpoint-retry.spec.ts`, and its `httpMethodRestrict` on the compile in
//   `specs/api/checkpoint-snapshot.spec.ts`.
// - Not offered by the catalog, so no chain can place it: `container`, `scs-sender`, `sds-trigger`.
// A deprecated element is superseded by the current member of its pair, which carries the case. The
// deprecated flag is the library's own (`GET /v1/library`), and `SUPERSEDED` below is read against
// it in `specs/api/element-library.spec.ts`. `choice`, `when` and `otherwise` stay covered: tier 1
// runs them, and a row holds what a passing test proves.
// `quartz-scheduler` is covered in `specs/brokers/scheduler.spec.ts`, and `script` by its fixture chain.

/**
 * Every element the library flags deprecated, and its successor. The library publishes no successor
 * field, and six pairs are renames rather than versions, so the map is written by hand. `null` means
 * no current element replaces it: `loop-expression` and `split-result` are children neither `loop-2`
 * nor `split-2` allows, and `sync-split-element` has no counterpart under `split-async-2`.
 */
export const SUPERSEDED: Readonly<Record<string, string | null>> = {
  "async-split-element": "async-split-element-2",
  catch: "catch-2",
  "chain-call": "chain-call-2",
  "chain-trigger": "chain-trigger-2",
  choice: "condition",
  "circuit-breaker": "circuit-breaker-2",
  "circuit-breaker-configuration": "circuit-breaker-configuration-2",
  finally: "finally-2",
  kafka: "kafka-trigger-2",
  "kafka-sender": "kafka-sender-2",
  loop: "loop-2",
  "loop-expression": null,
  "main-split-element": "main-split-element-2",
  mapper: "mapper-2",
  "on-fallback": "on-fallback-2",
  otherwise: "else",
  rabbitmq: "rabbitmq-trigger-2",
  "rabbitmq-sender": "rabbitmq-sender-2",
  scheduler: "quartz-scheduler",
  "sftp-trigger": "sftp-trigger-2",
  split: "split-2",
  "split-async": "split-async-2",
  "split-element": "split-element-2",
  "split-result": null,
  "sync-split-element": null,
  try: "try-2",
  "try-catch-finally": "try-catch-finally-2",
  when: "if",
};

/** The reason a deprecated element's row carries. `specs/api/element-library.spec.ts` holds rows to it. */
export function supersededReason(family: string): string {
  const successor = SUPERSEDED[family];
  return successor
    ? `superseded: the library flags it deprecated, and ${successor} carries the case`
    : "superseded with no successor: the library flags it deprecated, and no current element offers it";
}

const R_NOT_OFFERED =
  "not offered by the catalog: GET /v1/library serves no such element, so no chain can place it (element-library.spec.ts)";

/**
 * Seeded from the schemas as they stand, every row `not-covered` with its reason.
 * A row moves to `covered` only together with a spec that declares it through `covers()`.
 */
export const elementRegistry: RegistryEntry[] = [
  { family: "async-api-trigger", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_UNCOVERED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_UNCOVERED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_UNCOVERED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_UNCOVERED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_UNCOVERED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationAsyncProperties/acknowledgeMode", value: "NONE", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_UNCOVERED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationAsyncProperties/acknowledgeMode", value: "MANUAL", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_UNCOVERED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationAsyncProperties/acknowledgeMode", value: "AUTO", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_UNCOVERED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationMethod", value: "subscribe", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationMethod", value: "publish", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationMethod", value: "send", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_METHOD_V3, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationMethod", value: "receive", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_METHOD_V3, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationProtocolType", value: "kafka", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationProtocolType", value: "amqp", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "async-api-trigger", kind: "axis", axisPath: "systemType", value: "INTERNAL", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_SYSTEM_TYPE, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "systemType", value: "EXTERNAL", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "async-api-trigger", kind: "axis", axisPath: "systemType", value: "IMPLEMENTED", tier: 2, status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_SYSTEM_TYPE, tags: ENGINE },
  { family: "async-split-element", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("async-split-element"), tags: ENGINE },
  { family: "async-split-element-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "catch", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("catch"), tags: ENGINE },
  { family: "catch-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "chain-call-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "chain-trigger-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "checkpoint", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "checkpoint", kind: "axis", axisPath: "externalRoute", value: false, tier: 2, status: NOT_COVERED, reason: R_CHECKPOINT_EXTERNAL_ROUTE, tags: ENGINE },
  { family: "checkpoint", kind: "axis", axisPath: "httpMethodRestrict", value: "POST", tier: 2, status: "covered", tags: CATALOG },
  { family: "choice", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "circuit-breaker", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("circuit-breaker"), tags: ENGINE },
  { family: "circuit-breaker-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "circuit-breaker-configuration", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("circuit-breaker-configuration"), tags: ENGINE },
  { family: "circuit-breaker-configuration", kind: "axis", axisPath: "slidingWindowType", value: "COUNT_BASED", tier: 2, status: NOT_COVERED, reason: supersededReason("circuit-breaker-configuration"), tags: ENGINE },
  { family: "circuit-breaker-configuration", kind: "axis", axisPath: "slidingWindowType", value: "TIME_BASED", tier: 2, status: NOT_COVERED, reason: supersededReason("circuit-breaker-configuration"), tags: ENGINE },
  { family: "circuit-breaker-configuration-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "circuit-breaker-configuration-2", kind: "axis", axisPath: "slidingWindowType", value: "COUNT_BASED", tier: 2, status: "covered", tags: ENGINE },
  { family: "circuit-breaker-configuration-2", kind: "axis", axisPath: "slidingWindowType", value: "TIME_BASED", tier: 2, status: "covered", tags: ENGINE },
  { family: "condition", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "container", kind: "element", tier: 1, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "context-storage", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "operation", value: "SET", tier: 2, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "operation", value: "GET", tier: 2, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "operation", value: "DELETE", tier: 2, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "target", value: "BODY", tier: 2, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "target", value: "HEADER", tier: 2, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "target", value: "PROPERTY", tier: 2, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "useCorrelationId", value: false, tier: 2, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "useCorrelationId", value: true, tier: 2, status: "covered", tags: ENGINE },
  { family: "else", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "file-read", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "file-write", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "finally", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("finally"), tags: ENGINE },
  { family: "finally-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "correlationIdPosition", value: "header", tier: 2, status: NOT_COVERED, reason: R_SENDER_CORRELATION, tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "correlationIdPosition", value: "body", tier: 2, status: NOT_COVERED, reason: R_SENDER_CORRELATION, tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "receiveCorrelationId", value: true, tier: 2, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "receiveCorrelationId", value: false, tier: 2, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "header-modification", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "correlationIdPosition", value: "header", tier: 2, status: NOT_COVERED, reason: R_SENDER_CORRELATION, tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "correlationIdPosition", value: "body", tier: 2, status: NOT_COVERED, reason: R_SENDER_CORRELATION, tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "POST", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "GET", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "PUT", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "DELETE", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "PATCH", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "HEAD", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "OPTIONS", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "receiveCorrelationId", value: true, tier: 2, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "receiveCorrelationId", value: false, tier: 2, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "http-trigger", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "abacParameters/resourceDataType", value: "String", tier: 2, status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "abacParameters/resourceDataType", value: "Map", tier: 2, status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "accessControlType", value: "NONE", tier: 2, status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "accessControlType", value: "RBAC", tier: 2, status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "accessControlType", value: "ABAC", tier: 2, status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "correlationIdPosition", value: "header", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "correlationIdPosition", value: "body", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleChainFailureAction", value: "default", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleChainFailureAction", value: "script", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleChainFailureAction", value: "mapper-2", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleChainFailureAction", value: "chain-call", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleValidationAction", value: "default", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleValidationAction", value: "script", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleValidationAction", value: "mapper-2", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, tier: 2, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "receiveCorrelationId", value: true, tier: 2, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "receiveCorrelationId", value: false, tier: 2, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "systemType", value: "INTERNAL", tier: 2, status: NOT_COVERED, reason: R_SYSTEM_TYPE_ALIKE, tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "systemType", value: "EXTERNAL", tier: 2, status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "systemType", value: "IMPLEMENTED", tier: 2, status: NOT_COVERED, reason: R_SYSTEM_TYPE_ALIKE, tags: ENGINE },
  { family: "if", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "jms-sender", kind: "element", tier: 1, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "acknowledgmentMode", value: "AUTO_ACKNOWLEDGE", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "acknowledgmentMode", value: "CLIENT_ACKNOWLEDGE", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "acknowledgmentMode", value: "DUPS_OK_ACKNOWLEDGE", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "acknowledgmentMode", value: "SESSION_TRANSACTED", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "destinationType", value: "queue", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "destinationType", value: "topic", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Bytes", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Map", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Object", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Stream", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Text", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "element", tier: 1, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "acknowledgmentMode", value: "AUTO_ACKNOWLEDGE", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "acknowledgmentMode", value: "CLIENT_ACKNOWLEDGE", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "acknowledgmentMode", value: "DUPS_OK_ACKNOWLEDGE", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "acknowledgmentMode", value: "SESSION_TRANSACTED", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "destinationType", value: "queue", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "destinationType", value: "topic", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, tier: 2, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "kafka-sender-2", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-sender-2", kind: "axis", axisPath: "connectionSourceType", value: "maas", tier: 2, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "connectionSourceType", value: "manual", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.ByteArraySerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.ByteBufferSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.BytesSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.DoubleSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.FloatSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.IntegerSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.LongSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.ShortSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.StringSerializer", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.UUIDSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.VoidSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "maasClassifierTenantEnabled", value: true, tier: 2, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "maasClassifierTenantEnabled", value: false, tier: 2, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: NOT_COVERED, reason: R_PROPAGATE_CONTEXT, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: NOT_COVERED, reason: R_PROPAGATE_CONTEXT, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-DSA-SHA1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-ECDSA-SHA1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-RSA-SHA1-ENC", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-DSA-SHA1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-ECDSA-SHA1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-RSA-SHA1-ENC", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "ANONYMOUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "CRAM-MD5", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "DIGEST-MD5", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "EAP-AES128", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "EAP-AES128-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "ECDH-X25519-CHALLENGE[1]", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "ECDSA-NIST256P-CHALLENGE[1]", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "EXTERNAL", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "GS2-KRB5", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "GS2-KRB5-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "GSS-SPNEGO", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "GSSAPI", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "KERBEROS_V4", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "KERBEROS_V5", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "LOGIN", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS_AUTHEN", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS_LOGIN", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS-SAMBA-AUTH", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "NTLM", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "OAUTH10A", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "OAUTHBEARER", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "OPENID20", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "OTP", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "PLAIN", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SAML20", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-1-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-256", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-256-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-512", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SECURID", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SKEY", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SPNEGO", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SPNEGO-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "XOAUTH", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "XOAUTH2", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "securityProtocol", value: "PLAINTEXT", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-sender-2", kind: "axis", axisPath: "securityProtocol", value: "SASL_PLAINTEXT", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "securityProtocol", value: "SASL_SSL", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "securityProtocol", value: "SSL", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "sslProtocol", value: "TLS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.2", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.3", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.ByteArraySerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.ByteBufferSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.BytesSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.DoubleSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.FloatSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.IntegerSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.LongSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.ShortSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.StringSerializer", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.UUIDSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.VoidSerializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "autoOffsetReset", value: "none", tier: 2, status: NOT_COVERED, reason: R_KAFKA_OFFSET_RESET_NONE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "autoOffsetReset", value: "earliest", tier: 2, status: NOT_COVERED, reason: R_KAFKA_OFFSET_RESET_EARLIEST_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "autoOffsetReset", value: "latest", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "connectionSourceType", value: "maas", tier: 2, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "connectionSourceType", value: "manual", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "consumerConsistencyMode", value: "EVENTUAL", tier: 2, status: NOT_COVERED, reason: R_KAFKA_CONSUMER_CONSISTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "consumerConsistencyMode", value: "GUARANTEE_CONSUMPTION", tier: 2, status: NOT_COVERED, reason: R_KAFKA_CONSUMER_CONSISTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/enabled", value: true, tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/enabled", value: false, tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.ByteArrayDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.ByteBufferDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.BytesDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.DoubleDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.FloatDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.IntegerDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.LongDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.ShortDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.StringDeserializer", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.UUIDDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.VoidDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "maasClassifierTenantEnabled", value: true, tier: 2, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "maasClassifierTenantEnabled", value: false, tier: 2, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-DSA-SHA1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-ECDSA-SHA1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-RSA-SHA1-ENC", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-DSA-SHA1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-ECDSA-SHA1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-RSA-SHA1-ENC", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "ANONYMOUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "CRAM-MD5", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "DIGEST-MD5", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "EAP-AES128", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "EAP-AES128-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "ECDH-X25519-CHALLENGE[1]", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "ECDSA-NIST256P-CHALLENGE[1]", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "EXTERNAL", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "GS2-KRB5", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "GS2-KRB5-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "GSS-SPNEGO", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "GSSAPI", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "KERBEROS_V4", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "KERBEROS_V5", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "LOGIN", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS_AUTHEN", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS_LOGIN", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS-SAMBA-AUTH", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "NTLM", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "OAUTH10A", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "OAUTHBEARER", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "OPENID20", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "OTP", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "PLAIN", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SAML20", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-1-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-256", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-256-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-512", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SECURID", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SKEY", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SPNEGO", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SPNEGO-PLUS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "XOAUTH", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "XOAUTH2", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "securityProtocol", value: "PLAINTEXT", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "securityProtocol", value: "SASL_PLAINTEXT", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "securityProtocol", value: "SASL_SSL", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "securityProtocol", value: "SSL", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "sslProtocol", value: "TLS", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.1", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.2", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.3", tier: 2, status: NOT_COVERED, reason: R_KAFKA_PLAINTEXT_ONLY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.ByteArrayDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.ByteBufferDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.BytesDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.DoubleDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.FloatDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.IntegerDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.LongDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.ShortDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.StringDeserializer", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.UUIDDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.VoidDeserializer", tier: 2, status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "log-record", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "log-record", kind: "axis", axisPath: "logLevel", value: "Error", tier: 2, status: "covered", tags: ENGINE },
  { family: "log-record", kind: "axis", axisPath: "logLevel", value: "Warning", tier: 2, status: "covered", tags: ENGINE },
  { family: "log-record", kind: "axis", axisPath: "logLevel", value: "Info", tier: 2, status: "covered", tags: ENGINE },
  { family: "loop", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("loop"), tags: ENGINE },
  { family: "loop-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "loop-expression", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("loop-expression"), tags: ENGINE },
  { family: "mail-sender", kind: "element", tier: 1, status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "mail-sender", kind: "axis", axisPath: "contentType", value: "text/plain", tier: 2, status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "mail-sender", kind: "axis", axisPath: "contentType", value: "text/html", tier: 2, status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "mail-sender", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "mail-sender", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "main-split-element", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("main-split-element"), tags: ENGINE },
  { family: "main-split-element-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "mapper-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "mcp-trigger", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", tier: 2, status: NOT_COVERED, reason: R_MCP_IDEMPOTENCY, tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", tier: 2, status: NOT_COVERED, reason: R_MCP_IDEMPOTENCY, tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", tier: 2, status: NOT_COVERED, reason: R_MCP_IDEMPOTENCY, tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, tier: 2, status: NOT_COVERED, reason: R_MCP_IDEMPOTENCY, tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, tier: 2, status: NOT_COVERED, reason: R_MCP_IDEMPOTENCY, tags: ENGINE },
  { family: "on-fallback", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("on-fallback"), tags: ENGINE },
  { family: "on-fallback-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "otherwise", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "pubsub-sender", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "pubsub-sender", kind: "axis", axisPath: "messageOrderingEnabled", value: true, tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "pubsub-sender", kind: "axis", axisPath: "messageOrderingEnabled", value: false, tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "pubsub-sender", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: NOT_COVERED, reason: R_PROPAGATE_CONTEXT, tags: ENGINE },
  { family: "pubsub-sender", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: NOT_COVERED, reason: R_PROPAGATE_CONTEXT, tags: ENGINE },
  { family: "pubsub-trigger", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "pubsub-trigger", kind: "axis", axisPath: "ackMode", value: "AUTO", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "pubsub-trigger", kind: "axis", axisPath: "ackMode", value: "NONE", tier: 2, status: NOT_COVERED, reason: R_PUBSUB_ACKMODE_NONE_INDISTINGUISHABLE, tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "quartz-scheduler", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "rabbitmq-sender-2", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "rabbitmq-sender-2", kind: "axis", axisPath: "connectionSourceType", value: "maas", tier: 2, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "rabbitmq-sender-2", kind: "axis", axisPath: "connectionSourceType", value: "manual", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "rabbitmq-sender-2", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: NOT_COVERED, reason: R_PROPAGATE_CONTEXT, tags: ENGINE },
  { family: "rabbitmq-sender-2", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: NOT_COVERED, reason: R_PROPAGATE_CONTEXT, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "acknowledgeMode", value: "NONE", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_ACK_NONE, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "acknowledgeMode", value: "MANUAL", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "acknowledgeMode", value: "AUTO", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "connectionSourceType", value: "maas", tier: 2, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "connectionSourceType", value: "manual", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "deadLetterExchangeType", value: "direct", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "deadLetterExchangeType", value: "fanout", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "deadLetterExchangeType", value: "headers", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "deadLetterExchangeType", value: "topic", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "exchangeType", value: "direct", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "exchangeType", value: "fanout", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "exchangeType", value: "headers", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "exchangeType", value: "topic", tier: 2, status: NOT_COVERED, reason: R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/enabled", value: true, tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/enabled", value: false, tier: 2, status: NOT_COVERED, reason: R_BROKER_IDEMPOTENCY, tags: ENGINE },
  { family: "reuse", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "reuse-reference", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "script", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "scs-sender", kind: "element", tier: 1, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "operation", value: "SET", tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "operation", value: "GET", tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "operation", value: "DELETE", tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "target", value: "BODY", tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "target", value: "HEADER", tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "target", value: "PROPERTY", tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "useCorrelationId", value: false, tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "useCorrelationId", value: true, tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "sds-trigger", kind: "element", tier: 1, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "sds-trigger", kind: "axis", axisPath: "prohibitParallelRun", value: true, tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "sds-trigger", kind: "axis", axisPath: "prohibitParallelRun", value: false, tier: 2, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "service-call", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "after/items/type", value: "none", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "after/items/type", value: "mapper-2", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "after/items/type", value: "script", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "afterValidation/items/type", value: "responseValidation", tier: 2, status: NOT_COVERED, reason: R_SERVICE_CALL_VALIDATION_TYPE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "inherit", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "none", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "basic", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "bearer", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "m2m", tier: 2, status: NOT_COVERED, reason: R_M2M, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "before/type", value: "none", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "before/type", value: "mapper-2", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "before/type", value: "script", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "correlationIdPosition", value: "header", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "correlationIdPosition", value: "body", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "handleValidationAction", value: "default", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "handleValidationAction", value: "script", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "handleValidationAction", value: "mapper-2", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "subscribe", tier: 2, status: NOT_COVERED, reason: R_SERVICE_CALL_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "publish", tier: 2, status: NOT_COVERED, reason: R_SERVICE_CALL_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "send", tier: 2, status: NOT_COVERED, reason: R_SERVICE_CALL_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "receive", tier: 2, status: NOT_COVERED, reason: R_SERVICE_CALL_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "POST", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "GET", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "PUT", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "DELETE", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "PATCH", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "HEAD", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "OPTIONS", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "kafka", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "amqp", tier: 2, status: "covered", tags: ENGINE, target: "compose" },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "http", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "soap", tier: 2, status: NOT_COVERED, reason: R_SOAP, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "grpc", tier: 2, status: NOT_COVERED, reason: R_GRPC, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "graphql", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "propagateContext", value: true, tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "propagateContext", value: false, tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "receiveCorrelationId", value: true, tier: 2, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "receiveCorrelationId", value: false, tier: 2, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "systemType", value: "INTERNAL", tier: 2, status: NOT_COVERED, reason: R_SYSTEM_TYPE_ALIKE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "systemType", value: "EXTERNAL", tier: 2, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "systemType", value: "IMPLEMENTED", tier: 2, status: NOT_COVERED, reason: R_SERVICE_CALL_IMPLEMENTED, tags: ENGINE },
  { family: "sftp-download", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "sftp-trigger-2", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "sftp-upload", kind: "element", tier: 1, status: "covered", tags: ENGINE, target: "compose" },
  { family: "split", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("split"), tags: ENGINE },
  { family: "split-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "split-async", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("split-async"), tags: ENGINE },
  { family: "split-async-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "split-element", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("split-element"), tags: ENGINE },
  { family: "split-element-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "split-result", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("split-result"), tags: ENGINE },
  { family: "swimlane", kind: "element", tier: 1, status: "covered", tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Blue", tier: 2, status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Green", tier: 2, status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Yellow", tier: 2, status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Purple", tier: 2, status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Lagoon", tier: 2, status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Brown", tier: 2, status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "sync-split-element", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("sync-split-element"), tags: ENGINE },
  { family: "try", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("try"), tags: ENGINE },
  { family: "try-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "try-catch-finally", kind: "element", tier: 1, status: NOT_COVERED, reason: supersededReason("try-catch-finally"), tags: ENGINE },
  { family: "try-catch-finally-2", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "when", kind: "element", tier: 1, status: "covered", tags: ENGINE },
  { family: "xslt", kind: "element", tier: 1, status: "covered", tags: ENGINE },
];

// ---------------------------------------------------------------------------
// Keys
// ---------------------------------------------------------------------------

/**
 * The key a spec's `covers()` declaration and a registry row have to agree on.
 *
 * An element row keys on the family alone; an axis row on family, normalized path and value.
 * The value is JSON-encoded because `false`, `"false"` and `0` are three different axis values.
 */
export function coverageKey(
  family: string,
  axisPath?: string,
  value?: SchemaValue,
): string {
  if (axisPath === undefined) return family;
  return `${family}::${axisPath}=${JSON.stringify(value)}`;
}

export function entryKey(entry: RegistryEntry): string {
  return coverageKey(entry.family, entry.axisPath, entry.value);
}

// ---------------------------------------------------------------------------
// Shape validation
// ---------------------------------------------------------------------------

/**
 * The problems in a registry's own shape, as human-readable lines. Empty means valid.
 *
 * Takes the entries rather than reading the module-level registry, so a spec can hand it one bad
 * row and assert on the message instead of corrupting the real registry to test the check.
 */
export function validateRegistry(entries: readonly RegistryEntry[]): string[] {
  const problems: string[] = [];
  const seen = new Set<string>();

  for (const entry of entries) {
    const key = entryKey(entry);
    if (seen.has(key)) problems.push(`duplicate row: ${key}`);
    seen.add(key);

    if (!entry.family) problems.push(`row with no family: ${key}`);
    // `tier` is not checked: `Tier` is `1 | 2`, so the compiler has already refused every other
    // value and the branch that tested for one could not run.
    if (entry.tags.length === 0) problems.push(`${key}: no tags`);

    if (entry.status === "not-covered" && !entry.reason?.trim()) {
      problems.push(`${key}: status is not-covered and no reason is given`);
    }
    if (entry.kind === "axis" && (entry.axisPath === undefined || entry.value === undefined)) {
      problems.push(`${key}: an axis row needs both an axisPath and a value`);
    }
    if (entry.kind === "element" && (entry.axisPath !== undefined || entry.value !== undefined)) {
      problems.push(`${key}: an element row carries an axisPath or a value`);
    }
    if (entry.axis !== undefined && entry.axis !== entry.axisPath?.split("/").pop()) {
      problems.push(`${key}: axis "${entry.axis}" is not the last segment of the path`);
    }
  }
  return problems;
}

// ---------------------------------------------------------------------------
// Reconciliation against the run
// ---------------------------------------------------------------------------

export const COVERS_ANNOTATION = "covers";

/** The annotation a transport writes for every API call it makes. `reached.ts` writes it. */
export const REACHED_ANNOTATION = "reached";

/**
 * Every spec in the report, at any nesting depth.
 *
 * Suites nest arbitrarily — project, file, `describe` — and each reader that re-implemented the
 * recursion was a place the nesting could be got wrong silently: a walk that stops one level early
 * reports nothing rather than failing.
 */
export function* eachSpec(report: JsonReport): Generator<JsonSpec> {
  function* visit(suite: JsonSuite): Generator<JsonSpec> {
    yield* suite.specs ?? [];
    for (const child of suite.suites ?? []) yield* visit(child);
  }
  for (const suite of report.suites ?? []) yield* visit(suite);
}

/**
 * CLI options that change how a run executes without changing which tests it selects.
 *
 * An allowlist rather than a list of filters, and deliberately so: an unrecognized option is read
 * as narrowing the run, so a Playwright release that adds a filter costs a spurious refusal rather
 * than a silent half-reconciliation.
 */
const NON_SELECTING_OPTIONS = new Map<string, "flag" | "value">([
  ["--config", "value"],
  ["-c", "value"],
  ["--reporter", "value"],
  ["--output", "value"],
  ["--workers", "value"],
  ["-j", "value"],
  ["--retries", "value"],
  ["--timeout", "value"],
  ["--global-timeout", "value"],
  ["--trace", "value"],
  ["--repeat-each", "value"],
  ["--headed", "flag"],
  ["--quiet", "flag"],
  ["--forbid-only", "flag"],
  ["--fully-parallel", "flag"],
  ["--ignore-snapshots", "flag"],
  ["--pass-with-no-tests", "flag"],
]);

/**
 * The `brokers`-family project names `playwright.config.ts` declares unless `E2E_BROKERS=0`.
 *
 * Named here rather than imported from the config, which reads `process.env.E2E_BROKERS` at import
 * time: `npm run reconcile` runs as a process separate from `npm test` and is not guaranteed to see
 * the same environment the run itself saw. The report's own `config.projects` is what actually
 * happened, so `runFilters` reads that instead of trusting the two invocations to agree.
 */
const BROKER_PROJECT_NAMES = ["brokers-seed", "brokers-seed-teardown", "brokers", "brokers-restart"];

/**
 * The target the run was made on, as `playwright.config.ts` records it in the report's metadata.
 *
 * A report with no target is read as Compose: every report written before the targets existed was
 * a Compose run, and so is every hand-built report in the schema specs that does not say otherwise.
 */
export function reportTarget(report: JsonReport): Target {
  return report.config?.metadata?.target === "k8s" ? "k8s" : "compose";
}

/** Whether a row with `target` is read against a run on `run`: its own target, or any for none. */
export function runsOn(target: Target | undefined, run: Target): boolean {
  return target === undefined || target === run;
}

/**
 * The arguments that narrowed the run. Empty means the whole suite ran.
 *
 * `config.grep` cannot answer this — a RegExp serializes to `{}` — and `--project`/`--grep`
 * narrowing is read off `config.argv`, the argument vector, since `config.projects` lists every
 * project the *config* declared whether or not a CLI flag selected one.
 *
 * `E2E_BROKERS=0` narrows a run the same way and leaves no trace in `argv` at all — it is an
 * environment variable read inside `playwright.config.ts` itself, before the process arguments are
 * even parsed, and it removes the brokers-family projects from the config's `projects` array before
 * Playwright ever runs. So `config.projects` *is* read here, for exactly the fact `argv` cannot
 * carry: whether those projects were declared for this run at all. A report with no `projects` list
 * — every hand-built report in this file's own tests — is read as "cannot tell" rather than as
 * evidence of narrowing, since a real report always populates it.
 */
export function runFilters(report: JsonReport): string[] {
  // The broker projects exist only on Compose, so their absence narrows a Compose run alone.
  const brokersDeclared = reportTarget(report) === "compose";
  const argv = report.config?.argv ?? [];
  const filters: string[] = [];
  const start = argv.indexOf("test");
  if (start >= 0) {
    for (let index = start + 1; index < argv.length; index++) {
      const argument = argv[index];
      if (!argument.startsWith("-")) {
        // A bare positional is a file-name filter.
        filters.push(argument);
        continue;
      }
      const kind = NON_SELECTING_OPTIONS.get(argument.split("=")[0]);
      if (kind === "flag") continue;
      const separate =
        !argument.includes("=") && index + 1 < argv.length && !argv[index + 1].startsWith("-");
      if (kind === "value") {
        if (separate) index++;
        continue;
      }
      filters.push(argument);
      if (separate) filters.push(argv[++index]);
    }
  }

  const projectNames = new Set(
    (report.config?.projects ?? []).map((project) => project.name).filter((name): name is string => Boolean(name)),
  );
  if (
    brokersDeclared &&
    projectNames.size > 0 &&
    BROKER_PROJECT_NAMES.every((name) => !projectNames.has(name))
  ) {
    filters.push("E2E_BROKERS=0");
  }

  return filters;
}

/**
 * The component tags, one of which every test carries.
 *
 * `--grep @engine` then runs one component's cases after a change to that service, and the case
 * catalog groups by the same tags. Reported without the leading `@`, which is why they are stored
 * bare here and printed with one.
 */
export const COMPONENT_TAGS = [
  "catalog",
  "engine",
  "sessions",
  "testing-service",
  "ui",
  "extension",
  "infra",
] as const;

/** The tier tags. `@tier1` is the release floor; `@tier2` is everything else. */
const TIER_TAGS = ["tier1", "tier2"] as const;

export interface Declaration {
  key: string;
  test: string;
  passed: boolean;
  /** The component and tier tags of the declaring spec, with their leading `@` restored. */
  tags: string[];
}

/**
 * Whether a test proves anything.
 *
 * Not `status === "expected"` alone. Playwright reports a `test.fail()` case that failed as
 * `expected` — the run went the way the file declared — and that case stopped at the first
 * assertion that went red, so everything after it never ran. Two such cases exist in the suite
 * today, and a `covers()` or a transport call inside one would earn a registry row on an assertion
 * nobody made. `expectedStatus` is what tells the two apart.
 */
function proves(test: JsonTest): boolean {
  return test.status === "expected" && test.expectedStatus !== "failed";
}

/**
 * Every spec in the report that carries no component tag, or no tier tag.
 *
 * A tag is what makes `--grep @engine` and the case catalog possible, and a spec without one is
 * invisible to both — silently, since nothing else reads tags. So an untagged spec fails the
 * reconciliation the same way an unproven `covered` row does.
 */
export function untaggedSpecs(report: JsonReport): string[] {
  const components = new Set<string>(COMPONENT_TAGS);
  const tiers = new Set<string>(TIER_TAGS);
  const problems: string[] = [];

  for (const spec of eachSpec(report)) {
    const tags = spec.tags ?? [];
    const where = `${spec.file ?? "?"} › ${spec.title ?? "?"}`;
    if (!tags.some((tag) => components.has(tag))) {
      problems.push(`${where}: no component tag (one of ${[...components].map((t) => `@${t}`).join(", ")})`);
    }
    if (!tags.some((tag) => tiers.has(tag))) {
      problems.push(`${where}: no tier tag (@tier1 or @tier2)`);
    }
  }
  return problems;
}

/** Every `covers()` declaration in the report, with the outcome of the test that made it. */
export function declarationsFromReport(report: JsonReport): Declaration[] {
  const found: Declaration[] = [];

  for (const spec of eachSpec(report)) {
    for (const each of spec.tests ?? []) {
      for (const annotation of each.annotations ?? []) {
        if (annotation.type !== COVERS_ANNOTATION || !annotation.description) continue;
        found.push({
          key: annotation.description,
          test: `${spec.file ?? "?"} › ${spec.title ?? "?"}`,
          passed: proves(each),
          // Playwright reports tags without their leading `@`; the registry stores them with one.
          tags: (spec.tags ?? []).map((tag) => `@${tag}`),
        });
      }
    }
  }
  return found;
}

function countTests(report: JsonReport): number {
  let total = 0;
  for (const spec of eachSpec(report)) total += (spec.tests ?? []).length;
  return total;
}

/**
 * Diffs the run against both registries. Empty means they agree with it.
 *
 * Two failures, and they point in opposite directions. A row marked `covered` with no passing
 * declaration behind it is the one that matters: without it `covered` survives the spec being
 * deleted, renamed, skipped, or edited down to asserting a 200. A declaration naming a row that
 * does not exist is the other — a spec claiming coverage the registry never heard of. The
 * operation registry is checked the same way, against what the transports recorded rather than
 * against a `covers()` a spec wrote by hand.
 *
 * Two whole-run preconditions come first, because both make every row below unreadable rather than
 * wrong. A filtered run is refused outright: `--grep` or `--project` excludes rows the registry
 * still claims, and reporting each of them as unproven is a wall of noise that hides the one real
 * problem. A report with no tests in it fails for the same reason a green empty run does.
 *
 * `operations` defaults to empty so a caller reconciling only the element registry — the unit
 * cases in `coverage.spec.ts` — asks nothing of the transports.
 *
 * A row that names a target is read only against a run on that target, which the report records:
 * a broker row is not unproven on Kubernetes, where no broker project exists, and a custom-resource
 * row is not unproven on Compose, where no cluster does. A row that names no target runs on both.
 */
export function reconcile(
  report: JsonReport,
  entries: readonly RegistryEntry[] = elementRegistry,
  operations: readonly OperationClaim[] = [],
): string[] {
  const problems: string[] = [];

  const filters = runFilters(report);
  if (filters.length > 0) {
    return [
      `the run was narrowed by \`${filters.join(" ")}\`: reconciliation reads a whole run or none`,
    ];
  }

  if (countTests(report) === 0) {
    problems.push("the report holds no tests: nothing was reconciled");
  }

  problems.push(...untaggedSpecs(report));

  const target = reportTarget(report);
  const declarations = declarationsFromReport(report);
  const proven = new Set(declarations.filter((d) => d.passed).map((d) => d.key));
  const byKey = new Map(entries.map((entry) => [entryKey(entry), entry]));

  for (const entry of entries) {
    if (entry.status !== "covered" || !runsOn(entry.target, target)) continue;
    const key = entryKey(entry);
    if (!proven.has(key)) {
      problems.push(`${key}: marked covered, but no passing test declares it`);
    }
  }

  for (const declaration of declarations) {
    const entry = byKey.get(declaration.key);
    if (entry === undefined) {
      problems.push(
        `${declaration.test} declares ${declaration.key}, which is not a registry row`,
      );
      continue;
    }
    if (!runsOn(entry.target, target)) {
      problems.push(
        `${declaration.test} declares ${declaration.key}, a row of the ${entry.target} target, ` +
          `in a run on ${target}`,
      );
    }
    // `tags` says which component has to be exercised to prove the row. Without this reading the
    // field is written and never read, and an `@engine` row can be earned by a `@catalog` spec
    // that never went near the engine.
    const missing = entry.tags.filter((tag) => !declaration.tags.includes(tag));
    if (missing.length > 0) {
      problems.push(
        `${declaration.test} declares ${declaration.key}, which is tagged ` +
          `${entry.tags.join(", ")}; the spec carries ${declaration.tags.join(", ") || "no tags"}`,
      );
    }
  }

  problems.push(...reconcileOperations(report, operations, target));
  return problems;
}

/**
 * An operation row reduced to what reconciliation reads.
 *
 * `operations.ts` builds these — see `operationClaims()` there. The registry is not imported here,
 * because this module has to load outside Playwright and a `.js` specifier onto a `.ts` file does
 * not resolve there.
 */
export interface OperationClaim {
  /** `<service> <METHOD> <path template>`, the key `noteReached()` records. */
  key: string;
  /** All three states are claims about the run, and reconciliation checks each of them. */
  status: string;
  /** The one target whose run can reach the operation; absent means both. */
  target?: Target;
}

/** Every operation a **passing** test recorded, as registry keys. */
export function reachedFromReport(report: JsonReport): Set<string> {
  const reached = new Set<string>();
  for (const spec of eachSpec(report)) {
    for (const each of spec.tests ?? []) {
      if (!proves(each)) continue;
      for (const annotation of each.annotations ?? []) {
        if (annotation.type !== REACHED_ANNOTATION || !annotation.description) continue;
        reached.add(annotation.description);
      }
    }
  }
  return reached;
}

/**
 * The operation registry against what the run recorded, in **both** directions and in all three
 * states.
 *
 * A `covered` row nothing reached is the direction a proof loop is usually built for. The other one
 * matters just as much and was the one missing: a row saying `not-reached` that the run demonstrably
 * reached understates the suite and files finished work as a gap still open. Measured on one
 * run, 18 rows read that way. So a status is checked against the run whichever way it is wrong.
 *
 * `reached` sits between the two and was checked by neither, which left the middle state the one
 * place a claim could stand unverified: fifteen rows said a spec had called the operation and
 * nothing read the run to see whether one had. It is checked exactly like `covered` — the two
 * differ in what a run has to *assert*, not in whether the call happened.
 *
 * A run that recorded nothing at all reports that once rather than reporting every `covered` row as
 * unproven: the two readings are indistinguishable from the rows alone, and only one of them is a
 * registry problem. It still fails, because a proof loop nothing feeds proves nothing.
 */
export function reconcileOperations(
  report: JsonReport,
  operations: readonly OperationClaim[],
  target: Target = reportTarget(report),
): string[] {
  if (operations.length === 0) return [];

  const problems: string[] = [];
  const reached = reachedFromReport(report);
  const byKey = new Map(operations.map((claim) => [claim.key, claim]));

  for (const key of reached) {
    const claim = byKey.get(key);
    if (claim === undefined) problems.push(`${key} was reached, but it is not an operation row`);
    else if (!runsOn(claim.target, target)) {
      problems.push(`${key} was reached on ${target}, but its row names the ${claim.target} target`);
    }
  }

  if (reached.size === 0) {
    problems.push(
      "no API call was recorded: neither the transports in support/ — catalog.ts, sessions.ts, " +
        "engine.ts and testing-service.ts — nor recordingRequest around the request fixture " +
        "called noteReached, so no operation row can be checked against the run",
    );
    return problems;
  }

  for (const claim of operations) {
    if (!runsOn(claim.target, target)) continue;
    if (claim.status === "covered" && !reached.has(claim.key)) {
      problems.push(`${claim.key}: marked covered, but no passing test reached it`);
    }
    if (claim.status === "reached" && !reached.has(claim.key)) {
      problems.push(
        `${claim.key}: marked reached, but no passing test reached it. A call records one ` +
          `through a transport in support/ or through the \`request\` fixture on a service's own port`,
      );
    }
    if (claim.status === "not-reached" && reached.has(claim.key)) {
      problems.push(`${claim.key}: marked not-reached, but a passing test reached it`);
    }
  }

  return problems;
}
