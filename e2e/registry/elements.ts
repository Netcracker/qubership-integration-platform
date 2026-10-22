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

/**
 * Two states, because the registry only ever used two.
 *
 * A `superseded` member was declared for deprecated elements whose successor carries the case, and
 * no row was ever filed under it while 18 deprecated schemas sat at `not-covered` with a reason.
 * A status nothing writes is a status nothing checks, so the reason on those rows is the record.
 */
export type Status = "covered" | "not-covered";

/**
 * An `element` row has no `axisPath`; an `axis` row names one value of one property. A `behaviour`
 * row names one behaviour of an element family that no axis value selects, such as a retry.
 */
export type Kind = "axis" | "element" | "behaviour";

/**
 * Why a `not-covered` row stays out of the coverage denominator. `out-of-scope` means no case on
 * this stack can prove the row; `shared` means another family's case proves the code the row runs.
 */
export type Scope = "out-of-scope" | "shared";

export interface RegistryEntry {
  family: string;
  kind: Kind;
  /** The normalized axis path, e.g. `idempotency/actionOnDuplicate`. Axis rows only. */
  axisPath?: string;
  /** The bare property name. Derived from `axisPath` when a row omits it — readability only. */
  axis?: string;
  value?: SchemaValue;
  /** The behaviour label, e.g. `retry`. Behaviour rows only. */
  behaviour?: string;
  status: Status;
  /** Not-covered axis and element rows only, and the reason says why. */
  scope?: Scope;
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

const R_RABBITMQ_CONTEXT_ALWAYS_SENT =
  "a defect: RabbitMqSenderProcessor copies the request context into the message headers on every send, so the " +
  "caller's X-Request-Id reaches the message with propagateContext false on both engines; the test.fail() case in " +
  "specs/brokers/rabbitmq.spec.ts carries it";

const R_KAFKA_CONSUMER_CONSISTENCY =
  "the two values select a NetCracker Cloud Blue-Green consumer mode " +
  "(com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode) with no counterpart on this stack";

// The security axes are covered by `specs/brokers/kafka-security.spec.ts`, one case per value on
// the listener of the Kafka overlay that serves it.
const R_KAFKA_SASL_UNSUPPORTED =
  "Kafka brokers implement GSSAPI, PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, and OAUTHBEARER only; any other " +
  "mechanism needs a custom SaslServer plugged into the broker, so a value outside those five has no " +
  "broker to connect to";
const R_KAFKA_GSSAPI_NO_KDC =
  "GSSAPI needs a Kerberos KDC and a keytab, and the stack runs no KDC";
const R_KAFKA_OAUTHBEARER =
  "no listener of the Kafka overlay enables OAUTHBEARER; Kafka's unsecured OAUTHBEARER login enabled on " +
  "the AUTH listener would make a case possible";
const R_KAFKA_TLSV11_DISABLED =
  "measured on both engines: the JDK disables TLSv1.1 (jdk.tls.disabledAlgorithms), so a TLSv1.1 " +
  "client fails with SSLHandshakeException \"No appropriate protocol\" before the broker answers";
const R_KAFKA_TLSV13_INDISTINGUISHABLE =
  "the TLS13 listener accepts the TLS default as well, and no listener accepts TLSv1.2 while it refuses " +
  "TLSv1.3, so a case cannot tell TLSv1.3 apart from TLS, and the value has no case";
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
  "apart from the String default it would fall back to if the axis were ignored, and the value has no case";

const R_PUBSUB_ACKMODE_NONE_INDISTINGUISHABLE =
  "measured on a chain that completes: the embedded google-cloud-pubsub client's lease auto-extension " +
  "suppresses any observable redelivery within a bounded test window, so a case sees the same one " +
  "session, no failed elements AUTO's case already shows, and the value has no case. " +
  "A failing chain would tell the two apart, since AUTO nacks it and the emulator delivers it again " +
  "(trigger-idempotency.spec.ts), but no case runs NONE on a failing chain";

// `async-api-trigger`'s protocol axis is covered by one kafka and one amqp case in
// `specs/brokers/async-api.spec.ts`; the element row and its `systemType: EXTERNAL` row close through
// the kafka case's seeded chain. A third kafka case validates messages against the operation's
// schema and declares no covers(). Its acknowledgeMode axis has amqp cases in the same file, and its
// throw-exception idempotency row an amqp case in `specs/brokers/trigger-idempotency.spec.ts`; its
// other idempotency rows are shared. Its method axis has a passing case per AsyncAPI version and no
// covers() call; its systemType axis has no case.
const R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE =
  "template.hbs never reads integrationOperationMethod and OperationElementPropertiesBuilder " +
  "branches only on integrationOperationProtocolType, so the trigger compiles to the same step " +
  "whatever this axis holds. The AsyncAPI 2.6 kafka case resolves to subscribe and its amqp case to " +
  "publish only because KafkaSpecificationResolver and AMQPSpecificationResolver invert getMethod " +
  "for the same subscribe-only channel shape. An AsyncAPI 3.0 document gives send and receive " +
  "(specs/api/specifications.spec.ts), and the amqp case on its receive operation consumes the same " +
  "way; no case's route reads the value";
const R_ASYNC_API_TRIGGER_SYSTEM_TYPE =
  "EndpointHelperSource.integrationEndpoint, which kafka and amqp both resolve through, needs only " +
  "an activated environment with a non-blank address and never reads systemType, so INTERNAL and " +
  "IMPLEMENTED behave exactly like the EXTERNAL case; no case covers the other two values";
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
//   line at the top of each keeps them apart; `specs/harness/fixtures.spec.ts` refuses a duplicate.
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
// Its `systemType` EXTERNAL row is covered on the compile by `specs/api/http-trigger-system-type.spec.ts`
// and tagged `@catalog`; INTERNAL and IMPLEMENTED compile alike, so neither is covered, for
// `http-trigger` or `service-call`.
// Its `correlationIdPosition` rows are covered by `specs/runtime/http-trigger-idempotency.spec.ts`,
// and `service-call`'s by `specs/runtime/service-call-axes.spec.ts`.
const R_RECEIVE_CORRELATION_ID =
  "no template and no engine class reads receiveCorrelationId: a correlationIdPosition and a correlationIdName " +
  "switch the correlation id on, so a case would pass for true and false alike";

// `service-call`'s http and graphql protocols belong to `specs/runtime/service-call-sync.spec.ts`.
// Its soap and grpc rows are gaps somebody decided on. No product path writes soap:
// `specs/ui/chain-graph.spec.ts` picks a WSDL service in the element form and the form saves http.
const R_SOAP =
  "unreachable: the catalog reports a WSDL service as http, so the element form writes http and a SOAP call " +
  "compiles as http, which the http case covers; the VS Code extension wrote soap up to v1.2.3 (#807); " +
  "soap has no branch in service-call/template.hbs";
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
// `context-storage` and `mcp-trigger`; `specs/runtime/mcp-trigger-idempotency.spec.ts` covers
// `actionOnDuplicate=throw-exception` on `mcp-trigger`, and its other idempotency rows are shared.
// `specs/brokers/trigger-idempotency.spec.ts` does the same for the four broker triggers.
// `swimlane` is covered on the catalog by `specs/api/elements.spec.ts`.
const R_IDEMPOTENCY_SHARED = "the shared idempotency.hbs partial is proven on http-trigger";
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
// `quartz-scheduler` is covered in `specs/runtime/scheduler.spec.ts`, and `script` by its fixture chain.

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
  { family: "async-api-trigger", kind: "element", status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  // MANUAL and NONE each differ from the AUTO default: a failed message is not dead-lettered. They do
  // not differ from each other on a failing chain, since MANUAL acks the delivery before the chain
  // runs and NONE consumes without acks, so both leave one session, an empty queue, and an empty
  // dead-letter queue. Only the consumer's ack_required flag in the RabbitMQ management API tells
  // them apart, and the chart exposes no management port.
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationAsyncProperties/acknowledgeMode", value: "NONE", status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationAsyncProperties/acknowledgeMode", value: "MANUAL", status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationAsyncProperties/acknowledgeMode", value: "AUTO", status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationMethod", value: "subscribe", status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationMethod", value: "publish", status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationMethod", value: "send", status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationMethod", value: "receive", status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationProtocolType", value: "kafka", status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "integrationOperationProtocolType", value: "amqp", status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "systemType", value: "INTERNAL", status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_SYSTEM_TYPE, tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "systemType", value: "EXTERNAL", status: "covered", tags: ENGINE },
  { family: "async-api-trigger", kind: "axis", axisPath: "systemType", value: "IMPLEMENTED", status: NOT_COVERED, reason: R_ASYNC_API_TRIGGER_SYSTEM_TYPE, tags: ENGINE },
  { family: "async-split-element", kind: "element", status: NOT_COVERED, reason: supersededReason("async-split-element"), tags: ENGINE },
  { family: "async-split-element-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "catch", kind: "element", status: NOT_COVERED, reason: supersededReason("catch"), tags: ENGINE },
  { family: "catch-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "chain-call-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "chain-call-2", kind: "axis", axisPath: "block", value: true, status: NOT_COVERED, reason: "The schema fixes this option; no live test asserts it yet.", tags: ENGINE },
  { family: "chain-call-2", kind: "axis", axisPath: "failIfNoConsumers", value: false, status: NOT_COVERED, reason: "The schema fixes this option; no live test asserts it yet.", tags: ENGINE },
  { family: "chain-trigger-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "checkpoint", kind: "element", status: "covered", tags: ENGINE },
  { family: "checkpoint", kind: "axis", axisPath: "externalRoute", value: false, status: NOT_COVERED, reason: R_CHECKPOINT_EXTERNAL_ROUTE, tags: ENGINE },
  { family: "checkpoint", kind: "axis", axisPath: "httpMethodRestrict", value: "POST", status: "covered", tags: CATALOG },
  { family: "choice", kind: "element", status: "covered", tags: ENGINE },
  { family: "circuit-breaker", kind: "element", status: NOT_COVERED, reason: supersededReason("circuit-breaker"), tags: ENGINE },
  { family: "circuit-breaker-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "circuit-breaker-configuration", kind: "element", status: NOT_COVERED, reason: supersededReason("circuit-breaker-configuration"), tags: ENGINE },
  { family: "circuit-breaker-configuration", kind: "axis", axisPath: "slidingWindowType", value: "COUNT_BASED", status: NOT_COVERED, reason: supersededReason("circuit-breaker-configuration"), tags: ENGINE },
  { family: "circuit-breaker-configuration", kind: "axis", axisPath: "slidingWindowType", value: "TIME_BASED", status: NOT_COVERED, reason: supersededReason("circuit-breaker-configuration"), tags: ENGINE },
  { family: "circuit-breaker-configuration-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "circuit-breaker-configuration-2", kind: "axis", axisPath: "slidingWindowType", value: "COUNT_BASED", status: "covered", tags: ENGINE },
  { family: "circuit-breaker-configuration-2", kind: "axis", axisPath: "slidingWindowType", value: "TIME_BASED", status: "covered", tags: ENGINE },
  { family: "condition", kind: "element", status: "covered", tags: ENGINE },
  { family: "container", kind: "element", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "context-storage", kind: "element", status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "operation", value: "SET", status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "operation", value: "GET", status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "operation", value: "DELETE", status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "target", value: "BODY", status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "target", value: "HEADER", status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "target", value: "PROPERTY", status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "useCorrelationId", value: false, status: "covered", tags: ENGINE },
  { family: "context-storage", kind: "axis", axisPath: "useCorrelationId", value: true, status: "covered", tags: ENGINE },
  { family: "else", kind: "element", status: "covered", tags: ENGINE },
  { family: "file-read", kind: "element", status: "covered", tags: ENGINE },
  { family: "file-write", kind: "element", status: "covered", tags: ENGINE },
  { family: "finally", kind: "element", status: NOT_COVERED, reason: supersededReason("finally"), tags: ENGINE },
  { family: "finally-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "element", status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "correlationIdPosition", value: "header", status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "correlationIdPosition", value: "body", status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "propagateContext", value: true, status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "propagateContext", value: false, status: "covered", tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "receiveCorrelationId", value: true, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "graphql-sender", kind: "axis", axisPath: "receiveCorrelationId", value: false, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "header-modification", kind: "element", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "element", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "correlationIdPosition", value: "header", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "correlationIdPosition", value: "body", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "POST", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "GET", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "PUT", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "DELETE", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "PATCH", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "HEAD", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "httpMethod", value: "OPTIONS", status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "propagateContext", value: true, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "propagateContext", value: false, status: "covered", tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "receiveCorrelationId", value: true, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "http-sender", kind: "axis", axisPath: "receiveCorrelationId", value: false, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "http-sender", kind: "behaviour", behaviour: "response-timeout", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "element", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "abacParameters/resourceDataType", value: "String", status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "abacParameters/resourceDataType", value: "Map", status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "accessControlType", value: "NONE", status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "accessControlType", value: "RBAC", status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "accessControlType", value: "ABAC", status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "correlationIdPosition", value: "header", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "correlationIdPosition", value: "body", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleChainFailureAction", value: "default", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleChainFailureAction", value: "script", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleChainFailureAction", value: "mapper-2", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleChainFailureAction", value: "chain-call", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleValidationAction", value: "default", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleValidationAction", value: "script", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "handleValidationAction", value: "mapper-2", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "receiveCorrelationId", value: true, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "receiveCorrelationId", value: false, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "http-trigger", kind: "axis", axisPath: "systemType", value: "INTERNAL", status: NOT_COVERED, reason: R_SYSTEM_TYPE_ALIKE, tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "systemType", value: "EXTERNAL", status: "covered", tags: CATALOG },
  { family: "http-trigger", kind: "axis", axisPath: "systemType", value: "IMPLEMENTED", status: NOT_COVERED, reason: R_SYSTEM_TYPE_ALIKE, tags: ENGINE },
  { family: "http-trigger", kind: "behaviour", behaviour: "concurrent-calls", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "behaviour", behaviour: "redeploy-under-traffic", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "behaviour", behaviour: "large-body", status: "covered", tags: ENGINE },
  { family: "http-trigger", kind: "behaviour", behaviour: "redeploy-under-traffic-micro", status: "covered", tags: ENGINE, target: "k8s" },
  { family: "if", kind: "element", status: "covered", tags: ENGINE },
  { family: "jms-sender", kind: "element", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "acknowledgmentMode", value: "AUTO_ACKNOWLEDGE", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "acknowledgmentMode", value: "CLIENT_ACKNOWLEDGE", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "acknowledgmentMode", value: "DUPS_OK_ACKNOWLEDGE", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "acknowledgmentMode", value: "SESSION_TRANSACTED", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "destinationType", value: "queue", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "destinationType", value: "topic", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Bytes", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Map", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Object", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Stream", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "jmsMessageType", value: "Text", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "propagateContext", value: true, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-sender", kind: "axis", axisPath: "propagateContext", value: false, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "element", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "acknowledgmentMode", value: "AUTO_ACKNOWLEDGE", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "acknowledgmentMode", value: "CLIENT_ACKNOWLEDGE", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "acknowledgmentMode", value: "DUPS_OK_ACKNOWLEDGE", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "acknowledgmentMode", value: "SESSION_TRANSACTED", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "destinationType", value: "queue", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "destinationType", value: "topic", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "jms-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, status: NOT_COVERED, reason: R_JMS, tags: ENGINE },
  { family: "kafka-sender-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "connectionSourceType", value: "maas", status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "connectionSourceType", value: "manual", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.ByteArraySerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.ByteBufferSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.BytesSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.DoubleSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.FloatSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.IntegerSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.LongSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.ShortSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.StringSerializer", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.UUIDSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "keySerializer", value: "org.apache.kafka.common.serialization.VoidSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "maasClassifierTenantEnabled", value: true, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "maasClassifierTenantEnabled", value: false, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "propagateContext", value: true, status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "propagateContext", value: false, status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-DSA-SHA1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-ECDSA-SHA1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-RSA-SHA1-ENC", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-DSA-SHA1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-ECDSA-SHA1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-RSA-SHA1-ENC", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "ANONYMOUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "CRAM-MD5", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "DIGEST-MD5", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "EAP-AES128", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "EAP-AES128-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "ECDH-X25519-CHALLENGE[1]", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "ECDSA-NIST256P-CHALLENGE[1]", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "EXTERNAL", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "GS2-KRB5", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "GS2-KRB5-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "GSS-SPNEGO", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "GSSAPI", status: NOT_COVERED, reason: R_KAFKA_GSSAPI_NO_KDC, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "KERBEROS_V4", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "KERBEROS_V5", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "LOGIN", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS_AUTHEN", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS_LOGIN", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS-SAMBA-AUTH", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "NTLM", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "OAUTH10A", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "OAUTHBEARER", status: NOT_COVERED, reason: R_KAFKA_OAUTHBEARER, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "OPENID20", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "OTP", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "PLAIN", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SAML20", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-1-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-256", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-256-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-512", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SECURID", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SKEY", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SPNEGO", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "SPNEGO-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "XOAUTH", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "saslMechanism", value: "XOAUTH2", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "securityProtocol", value: "PLAINTEXT", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "securityProtocol", value: "SASL_PLAINTEXT", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "securityProtocol", value: "SASL_SSL", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "securityProtocol", value: "SSL", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "sslProtocol", value: "TLS", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.1", status: NOT_COVERED, reason: R_KAFKA_TLSV11_DISABLED, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.2", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.3", status: NOT_COVERED, reason: R_KAFKA_TLSV13_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.ByteArraySerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.ByteBufferSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.BytesSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.DoubleSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.FloatSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.IntegerSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.LongSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.ShortSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.StringSerializer", status: "covered", tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.UUIDSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-sender-2", kind: "axis", axisPath: "valueSerializer", value: "org.apache.kafka.common.serialization.VoidSerializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "autoOffsetReset", value: "none", status: NOT_COVERED, reason: R_KAFKA_OFFSET_RESET_NONE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "autoOffsetReset", value: "earliest", status: NOT_COVERED, reason: R_KAFKA_OFFSET_RESET_EARLIEST_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "autoOffsetReset", value: "latest", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "connectionSourceType", value: "maas", status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "connectionSourceType", value: "manual", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "consumerConsistencyMode", value: "EVENTUAL", status: NOT_COVERED, reason: R_KAFKA_CONSUMER_CONSISTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "consumerConsistencyMode", value: "GUARANTEE_CONSUMPTION", status: NOT_COVERED, reason: R_KAFKA_CONSUMER_CONSISTENCY, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/enabled", value: true, status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "idempotency/enabled", value: false, status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.ByteArrayDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.ByteBufferDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.BytesDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.DoubleDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.FloatDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.IntegerDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.LongDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.ShortDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.StringDeserializer", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.UUIDDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "keyDeserializer", value: "org.apache.kafka.common.serialization.VoidDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "maasClassifierTenantEnabled", value: true, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "maasClassifierTenantEnabled", value: false, status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-DSA-SHA1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-ECDSA-SHA1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-M-RSA-SHA1-ENC", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-DSA-SHA1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-ECDSA-SHA1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "9798-U-RSA-SHA1-ENC", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "ANONYMOUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "CRAM-MD5", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "DIGEST-MD5", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "EAP-AES128", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "EAP-AES128-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "ECDH-X25519-CHALLENGE[1]", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "ECDSA-NIST256P-CHALLENGE[1]", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "EXTERNAL", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "GS2-KRB5", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "GS2-KRB5-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "GSS-SPNEGO", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "GSSAPI", status: NOT_COVERED, reason: R_KAFKA_GSSAPI_NO_KDC, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "KERBEROS_V4", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "KERBEROS_V5", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "LOGIN", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS_AUTHEN", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS_LOGIN", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "NMAS-SAMBA-AUTH", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "NTLM", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "OAUTH10A", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "OAUTHBEARER", status: NOT_COVERED, reason: R_KAFKA_OAUTHBEARER, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "OPENID20", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "OTP", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "PLAIN", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SAML20", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-1", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-1-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-256", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-256-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SCRAM-SHA-512", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SECURID", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SKEY", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SPNEGO", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "SPNEGO-PLUS", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "XOAUTH", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "saslMechanism", value: "XOAUTH2", status: NOT_COVERED, reason: R_KAFKA_SASL_UNSUPPORTED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "securityProtocol", value: "PLAINTEXT", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "securityProtocol", value: "SASL_PLAINTEXT", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "securityProtocol", value: "SASL_SSL", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "securityProtocol", value: "SSL", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "sslProtocol", value: "TLS", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.1", status: NOT_COVERED, reason: R_KAFKA_TLSV11_DISABLED, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.2", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "sslProtocol", value: "TLSv1.3", status: NOT_COVERED, reason: R_KAFKA_TLSV13_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.ByteArrayDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_INDISTINGUISHABLE, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.ByteBufferDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.BytesDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.DoubleDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.FloatDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.IntegerDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.LongDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.ShortDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.StringDeserializer", status: "covered", tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.UUIDDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "kafka-trigger-2", kind: "axis", axisPath: "valueDeserializer", value: "org.apache.kafka.common.serialization.VoidDeserializer", status: NOT_COVERED, reason: R_KAFKA_SERIALIZER_PROPORTIONAL, tags: ENGINE },
  { family: "log-record", kind: "element", status: "covered", tags: ENGINE },
  { family: "log-record", kind: "axis", axisPath: "logLevel", value: "Error", status: "covered", tags: ENGINE },
  { family: "log-record", kind: "axis", axisPath: "logLevel", value: "Warning", status: "covered", tags: ENGINE },
  { family: "log-record", kind: "axis", axisPath: "logLevel", value: "Info", status: "covered", tags: ENGINE },
  { family: "loop", kind: "element", status: NOT_COVERED, reason: supersededReason("loop"), tags: ENGINE },
  { family: "loop-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "loop-expression", kind: "element", status: NOT_COVERED, reason: supersededReason("loop-expression"), tags: ENGINE },
  { family: "mail-sender", kind: "element", status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "mail-sender", kind: "axis", axisPath: "contentType", value: "text/plain", status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "mail-sender", kind: "axis", axisPath: "contentType", value: "text/html", status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "mail-sender", kind: "axis", axisPath: "propagateContext", value: true, status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "mail-sender", kind: "axis", axisPath: "propagateContext", value: false, status: NOT_COVERED, reason: R_MAIL, tags: ENGINE },
  { family: "main-split-element", kind: "element", status: NOT_COVERED, reason: supersededReason("main-split-element"), tags: ENGINE },
  { family: "main-split-element-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "mapper-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "mcp-trigger", kind: "element", status: "covered", tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", status: "covered", tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, status: "covered", tags: ENGINE },
  { family: "mcp-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "on-fallback", kind: "element", status: NOT_COVERED, reason: supersededReason("on-fallback"), tags: ENGINE },
  { family: "on-fallback-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "otherwise", kind: "element", status: "covered", tags: ENGINE },
  { family: "pubsub-sender", kind: "element", status: "covered", tags: ENGINE },
  { family: "pubsub-sender", kind: "axis", axisPath: "messageOrderingEnabled", value: true, status: "covered", tags: ENGINE },
  { family: "pubsub-sender", kind: "axis", axisPath: "messageOrderingEnabled", value: false, status: "covered", tags: ENGINE },
  { family: "pubsub-sender", kind: "axis", axisPath: "propagateContext", value: true, status: "covered", tags: ENGINE },
  { family: "pubsub-sender", kind: "axis", axisPath: "propagateContext", value: false, status: "covered", tags: ENGINE },
  { family: "pubsub-trigger", kind: "element", status: "covered", tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "ackMode", value: "AUTO", status: "covered", tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "ackMode", value: "NONE", status: NOT_COVERED, reason: R_PUBSUB_ACKMODE_NONE_INDISTINGUISHABLE, tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", status: "covered", tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/enabled", value: true, status: "covered", tags: ENGINE },
  { family: "pubsub-trigger", kind: "axis", axisPath: "idempotency/enabled", value: false, status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "quartz-scheduler", kind: "element", status: "covered", tags: ENGINE },
  { family: "rabbitmq-sender-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "rabbitmq-sender-2", kind: "axis", axisPath: "connectionSourceType", value: "maas", status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "rabbitmq-sender-2", kind: "axis", axisPath: "connectionSourceType", value: "manual", status: "covered", tags: ENGINE },
  { family: "rabbitmq-sender-2", kind: "axis", axisPath: "propagateContext", value: true, status: "covered", tags: ENGINE },
  { family: "rabbitmq-sender-2", kind: "axis", axisPath: "propagateContext", value: false, status: NOT_COVERED, reason: R_RABBITMQ_CONTEXT_ALWAYS_SENT, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "element", status: "covered", tags: ENGINE },
  // MANUAL and NONE leave the same state on a failing chain; see the async-api-trigger rows.
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "acknowledgeMode", value: "NONE", status: "covered", tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "acknowledgeMode", value: "MANUAL", status: "covered", tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "acknowledgeMode", value: "AUTO", status: "covered", tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "connectionSourceType", value: "maas", status: NOT_COVERED, reason: R_MAAS_AGENT_ABSENT, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "connectionSourceType", value: "manual", status: "covered", tags: ENGINE },
  // The two exchange type axes are covered on the compile (specs/api/rabbitmq-trigger-snapshot.spec.ts):
  // the brokers seed declares every exchange itself, so a runtime case passes for any value.
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "deadLetterExchangeType", value: "direct", status: "covered", tags: CATALOG },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "deadLetterExchangeType", value: "fanout", status: "covered", tags: CATALOG },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "deadLetterExchangeType", value: "headers", status: "covered", tags: CATALOG },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "deadLetterExchangeType", value: "topic", status: "covered", tags: CATALOG },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "exchangeType", value: "direct", status: "covered", tags: CATALOG },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "exchangeType", value: "fanout", status: "covered", tags: CATALOG },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "exchangeType", value: "headers", status: "covered", tags: CATALOG },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "exchangeType", value: "topic", status: "covered", tags: CATALOG },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "ignore", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", status: "covered", tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/enabled", value: true, status: "covered", tags: ENGINE },
  { family: "rabbitmq-trigger-2", kind: "axis", axisPath: "idempotency/enabled", value: false, status: NOT_COVERED, scope: "shared", reason: R_IDEMPOTENCY_SHARED, tags: ENGINE },
  { family: "reuse", kind: "element", status: "covered", tags: ENGINE },
  { family: "reuse-reference", kind: "element", status: "covered", tags: ENGINE },
  { family: "script", kind: "element", status: "covered", tags: ENGINE },
  { family: "script", kind: "behaviour", behaviour: "large-body", status: "covered", tags: ENGINE },
  { family: "scs-sender", kind: "element", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "operation", value: "SET", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "operation", value: "GET", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "operation", value: "DELETE", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "propagateContext", value: true, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "propagateContext", value: false, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "target", value: "BODY", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "target", value: "HEADER", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "target", value: "PROPERTY", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "useCorrelationId", value: false, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "scs-sender", kind: "axis", axisPath: "useCorrelationId", value: true, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "sds-trigger", kind: "element", status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "sds-trigger", kind: "axis", axisPath: "prohibitParallelRun", value: true, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "sds-trigger", kind: "axis", axisPath: "prohibitParallelRun", value: false, status: NOT_COVERED, reason: R_NOT_OFFERED, tags: ENGINE },
  { family: "service-call", kind: "element", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "after/items/type", value: "none", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "after/items/type", value: "mapper-2", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "after/items/type", value: "script", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "afterValidation/items/type", value: "responseValidation", status: NOT_COVERED, reason: R_SERVICE_CALL_VALIDATION_TYPE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "inherit", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "none", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "basic", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "bearer", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "authorizationConfiguration/type", value: "m2m", status: NOT_COVERED, reason: R_M2M, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "before/type", value: "none", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "before/type", value: "mapper-2", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "before/type", value: "script", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "correlationIdPosition", value: "header", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "correlationIdPosition", value: "body", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "handleValidationAction", value: "default", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "handleValidationAction", value: "script", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "handleValidationAction", value: "mapper-2", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "subscribe", status: NOT_COVERED, reason: R_SERVICE_CALL_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "publish", status: NOT_COVERED, reason: R_SERVICE_CALL_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "send", status: NOT_COVERED, reason: R_SERVICE_CALL_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "receive", status: NOT_COVERED, reason: R_SERVICE_CALL_METHOD_INDISTINGUISHABLE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "POST", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "GET", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "PUT", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "DELETE", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "PATCH", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "HEAD", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationMethod", value: "OPTIONS", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "kafka", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "amqp", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "http", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "soap", status: NOT_COVERED, reason: R_SOAP, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "grpc", status: NOT_COVERED, reason: R_GRPC, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "integrationOperationProtocolType", value: "graphql", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "propagateContext", value: true, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "propagateContext", value: false, status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "receiveCorrelationId", value: true, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "receiveCorrelationId", value: false, status: NOT_COVERED, reason: R_RECEIVE_CORRELATION_ID, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "systemType", value: "INTERNAL", status: NOT_COVERED, reason: R_SYSTEM_TYPE_ALIKE, tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "systemType", value: "EXTERNAL", status: "covered", tags: ENGINE },
  { family: "service-call", kind: "axis", axisPath: "systemType", value: "IMPLEMENTED", status: NOT_COVERED, reason: R_SERVICE_CALL_IMPLEMENTED, tags: ENGINE },
  { family: "service-call", kind: "behaviour", behaviour: "retry", status: "covered", tags: ENGINE },
  { family: "sftp-download", kind: "element", status: "covered", tags: ENGINE },
  { family: "sftp-trigger-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "sftp-upload", kind: "element", status: "covered", tags: ENGINE },
  { family: "split", kind: "element", status: NOT_COVERED, reason: supersededReason("split"), tags: ENGINE },
  { family: "split-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "split-async", kind: "element", status: NOT_COVERED, reason: supersededReason("split-async"), tags: ENGINE },
  { family: "split-async-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "split-element", kind: "element", status: NOT_COVERED, reason: supersededReason("split-element"), tags: ENGINE },
  { family: "split-element-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "split-result", kind: "element", status: NOT_COVERED, reason: supersededReason("split-result"), tags: ENGINE },
  { family: "swimlane", kind: "element", status: "covered", tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Blue", status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Green", status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Yellow", status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Purple", status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Lagoon", status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "swimlane", kind: "axis", axisPath: "color", value: "Brown", status: NOT_COVERED, reason: R_SWIMLANE_COLOR, tags: CATALOG },
  { family: "sync-split-element", kind: "element", status: NOT_COVERED, reason: supersededReason("sync-split-element"), tags: ENGINE },
  { family: "try", kind: "element", status: NOT_COVERED, reason: supersededReason("try"), tags: ENGINE },
  { family: "try-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "try-catch-finally", kind: "element", status: NOT_COVERED, reason: supersededReason("try-catch-finally"), tags: ENGINE },
  { family: "try-catch-finally-2", kind: "element", status: "covered", tags: ENGINE },
  { family: "when", kind: "element", status: "covered", tags: ENGINE },
  { family: "xslt", kind: "element", status: "covered", tags: ENGINE },
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

/** The key `coversBehaviour()` declares for a behaviour row. */
export function behaviourKey(family: string, label: string): string {
  return coverageKey(family, "behaviour", label);
}

export function entryKey(entry: RegistryEntry): string {
  if (entry.kind === "behaviour") return behaviourKey(entry.family, entry.behaviour ?? "");
  return coverageKey(entry.family, entry.axisPath, entry.value);
}

/**
 * The keys of the rows whose element or axis value is missing from `declared`, the keys the
 * schemas declare. The schemas hold no key for a behaviour row, so it is stale only when its family is.
 */
export function staleRows(entries: readonly RegistryEntry[], declared: ReadonlySet<string>): string[] {
  return entries
    .filter((entry) => !declared.has(entry.kind === "behaviour" ? coverageKey(entry.family) : entryKey(entry)))
    .map(entryKey);
}

/**
 * The coverage numbers of the rows a run is read against, so pass them through `runsOn` first.
 * `inScope` is the denominator and leaves out every row that carries a `scope`.
 */
export function summarizeElements(rows: readonly RegistryEntry[]): {
  covered: number;
  inScope: number;
  outOfScope: number;
  shared: number;
} {
  return {
    covered: rows.filter((entry) => entry.status === "covered").length,
    inScope: rows.filter((entry) => entry.scope === undefined).length,
    outOfScope: rows.filter((entry) => entry.scope === "out-of-scope").length,
    shared: rows.filter((entry) => entry.scope === "shared").length,
  };
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
    if (entry.kind === "behaviour" && (entry.axisPath !== undefined || entry.value !== undefined)) {
      problems.push(`${key}: a behaviour row carries an axisPath or a value`);
    }
    if (entry.kind === "behaviour" && !entry.behaviour?.trim()) {
      problems.push(`${key}: a behaviour row needs a behaviour label`);
    }
    if (entry.scope !== undefined && entry.kind === "behaviour") {
      problems.push(`${key}: a behaviour row carries a scope`);
    }
    if (entry.scope !== undefined && entry.status === "covered") {
      problems.push(`${key}: a covered row carries a scope`);
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
 * The annotation `strikesAsKnown` writes when a `ConditionalDefect` strikes, naming the defect. The
 * case stopped at the step the defect names, so every call it recorded before that step was made.
 */
export const STRUCK_ANNOTATION = "struck";

/**
 * Every spec in the report, at any nesting depth, with its `title` set to the `describe` path and
 * the case title joined with ` › `, the way `eachTest` in `support/run-report.mjs` joins them. The
 * file suite's title is left out, because readers print `spec.file` beside the title.
 *
 * Suites nest arbitrarily — project, file, `describe` — and each reader that re-implemented the
 * recursion was a place the nesting could be got wrong silently: a walk that stops one level early
 * reports nothing rather than failing.
 */
export function* eachSpec(report: JsonReport): Generator<JsonSpec> {
  function* visit(suite: JsonSuite, titles: string[]): Generator<JsonSpec> {
    for (const spec of suite.specs ?? []) yield { ...spec, title: [...titles, spec.title ?? "?"].join(" › ") };
    for (const child of suite.suites ?? []) yield* visit(child, [...titles, child.title ?? "?"]);
  }
  for (const suite of report.suites ?? []) yield* visit(suite, []);
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
 * The `brokers`-family project names `env/target-setup.ts` declares unless `E2E_BROKERS=0`: the
 * first three on either target, the micro pair on `k8s` only.
 *
 * Named here rather than imported from the config, which reads `process.env.E2E_BROKERS` at import
 * time: `npm run reconcile` runs as a process separate from `npm test` and is not guaranteed to see
 * the same environment the run itself saw. The report's own `config.projects` is what actually
 * happened, so `runFilters` reads that instead of trusting the two invocations to agree.
 */
const BROKER_PROJECT_NAMES = ["brokers-seed", "brokers", "brokers-restart", "brokers-micro-seed", "brokers-micro"];

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
  if (projectNames.size > 0 && BROKER_PROJECT_NAMES.every((name) => !projectNames.has(name))) {
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
  "infra",
  "schema",
  "harness",
] as const;

export interface Declaration {
  key: string;
  test: string;
  passed: boolean;
  /** The tags of the declaring spec, with their leading `@` restored. */
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
 * Every spec in the report that carries no component tag.
 *
 * A tag is what makes `--grep @engine` and the case catalog possible, and a spec without one is
 * invisible to both — silently, since nothing else reads tags. So an untagged spec fails the
 * reconciliation the same way an unproven `covered` row does.
 */
export function untaggedSpecs(report: JsonReport): string[] {
  const components = new Set<string>(COMPONENT_TAGS);
  const problems: string[] = [];

  for (const spec of eachSpec(report)) {
    const tags = spec.tags ?? [];
    const where = `${spec.file ?? "?"} › ${spec.title ?? "?"}`;
    if (!tags.some((tag) => components.has(tag))) {
      problems.push(`${where}: no component tag (one of ${[...components].map((t) => `@${t}`).join(", ")})`);
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
 * a custom-resource row is not unproven on Compose, where no cluster exists. A row that names no
 * target runs on both.
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

/**
 * Whether a test's calls count as made: a passing test, or one a `ConditionalDefect` struck. A struck
 * case stopped at the step the defect names, so a race the pin carries turns no row red here. A plain
 * `test.fail()` case may have stopped anywhere, so its calls stay unread.
 */
function reaches(test: JsonTest): boolean {
  if (proves(test)) return true;
  return test.status === "expected" && (test.annotations ?? []).some((annotation) => annotation.type === STRUCK_ANNOTATION);
}

/** Every operation a passing or a struck test recorded, as registry keys. */
export function reachedFromReport(report: JsonReport): Set<string> {
  const reached = new Set<string>();
  for (const spec of eachSpec(report)) {
    for (const each of spec.tests ?? []) {
      if (!reaches(each)) continue;
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
