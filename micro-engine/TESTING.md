# Component tests for micro-engine snapshots

Run the catalog-to-micro-engine flow from the repository root:

```bash
bash scripts/test-micro-engine-snapshots.sh
```

Use Java 21, Docker, and Maven credentials for the private micro-engine dependencies.
The script builds the local integration-build-pipeline, generates a fresh bundle in
`runtime-catalog/target/snapshotbundle`, and executes its scenarios in micro-engine.
Set `SNAPSHOT_BUNDLE_DIRECTORY` to use another output directory outside
`micro-engine/target`, which the consumer build cleans. Additional arguments
are passed to each Maven invocation, for example `-s /path/to/settings.xml`.

The `snapshotTests` and `snapshotTestsShort` profiles run micro-engine scenarios in six separate JVMs by default.
This also applies to an ordinary build from the repository root:

```bash
mvn clean install -Dgpg.skip=true -PsnapshotTests
```

For the short suite, use:

```bash
mvn clean install -Dgpg.skip=true -PsnapshotTestsShort
```

`snapshotTests` runs both scopes. `snapshotTestsShort` runs only scenarios marked `scope: Short`.
Both profiles generate the snapshot bundle in runtime-catalog before executing micro-engine tests
when used in a sequential reactor build. With an existing bundle, run either profile with
`-pl micro-engine test`. If both profiles are active, the short scope applies.

Every scenario must declare `scope: Short` or `scope: Long`. Use `Short` for scenarios that
normally take at most one second, or that cover an element's basic behavior. Use `Long` for
the remaining scenarios. Classification is explicit in YAML; a run's timing does not change it.
The short suite includes basic checks for all 24 elements, including broker-backed senders
whose fixture startup takes longer than one second. It contains 56 scenarios;
the remaining 38 are `Long`.

```yaml
scenarios:
  - id: publishes-message
    scope: Short
    endpointUri: direct:example
    body: example
    expectedBody: example
```

The scope filter combines with `snapshot.target` and `snapshot.scenario`. An exact scenario
outside the selected scope produces a selection error. Scope filtering happens before scenarios
are divided between workers, so empty workers are allowed when the selection is small.

Maven compiles the tests once, then Surefire runs one generated test class per JVM worker.
Each JVM executes its scenarios sequentially. Scenarios are assigned to workers in turn after
applying the scope, target, and scenario filters; every selected scenario belongs to exactly one worker.
Set `-Dsnapshot.workers=1` for sequential execution, or choose another positive count.
The bundle producer and other Maven modules keep their existing execution settings.

To execute Kafka scenarios from an existing bundle with two workers:

```bash
./mvnw -pl micro-engine -PsnapshotTests -Dgpg.skip=true test \
  -Dsnapshot.target=kafka-sender -Dsnapshot.workers=2
```

The Python 3 launcher is an optional wrapper around the same Maven command:

```bash
python3 scripts/run-micro-engine-snapshots.py --workers 2 -- -Dsnapshot.target=kafka-sender
```

Pass Maven options after `--`, including `-s`, `-nsu`, and `-Dsnapshot.bundle.directory`.
The launcher starts one Maven process, which manages compilation and the worker JVMs.
The fresh-bundle script `scripts/test-micro-engine-snapshots.sh` also uses six workers by default;
set `SNAPSHOT_WORKERS` to change its count.

Surefire writes one XML report per generated test class under
`micro-engine/target/surefire-reports`. Scenario JSON reports are written under
`micro-engine/target/surefire-reports/snapshot-shards`: `shard-0.json`, `shard-1.json`,
and so on. `summary.json` contains the combined array of scenario results, and the console
prints one combined results table. A failed test or worker error makes the build fail.
Each fork uses `micro-engine/target/snapshot-workers/{forkNumber}` as its working directory.

A filtered selection can leave a worker with no scenarios. Scenarios with staged files or a
`file-output` fixture retain the shared filesystem lock; other scenarios can execute concurrently
in separate JVMs.

The reactor relies on the module order in the root POM: runtime-catalog generates the
bundle before micro-engine reads it. For parallel Maven builds with `-T`, use the script,
which keeps these stages sequential.
When using `-pl`, select both `runtime-catalog` and `micro-engine`; `-am` alone does not
include the producer in a micro-engine build.

## What the flow executes

`SnapshotBundleProducerIntegrationTest` generates the bundle. With the default worker count,
Maven generates `MicroEngineSnapshotShard0Test` through `MicroEngineSnapshotShard5Test`.
The `snapshot.workers` property controls how many classes it generates. They inherit the scenario runner from
`MicroEngineSnapshotRouteExecutionTest` and divide the selected manifest scenarios between them.
The other snapshot classes provide shared execution support.
The results table includes `Duration (s)` with millisecond precision for each scenario.
It measures fixture startup, route loading, invocations, verification, and cleanup inside
the test method. JUnit/CDI initialization outside the method is excluded. Scenarios that
do not enter the test method show `-`.

Runtime Catalog imports the configurations in
`integration-build-pipeline/src/test/resources/testConfigurations` and creates snapshots through
its production import services. It then uses `IntegrationSourceBuilderFactory` to
produce the XML used for micro-domains, including deployment metadata, element
metadata, and component beans. The bundle contains this XML and the mapping from
source element IDs to snapshot element IDs.

The shared bundle models and writer live in the `snapshotbundle` package under
`integration-build-pipeline/src/test-support/java`. Both modules compile this directory
as test sources. Runtime Catalog copies only `testConfigurations` from the sibling module's test
resources. The producer and its import wrapper remain in Runtime Catalog because they
use its production import services and snapshot persistence. The bundle is written to
`runtime-catalog/target/snapshotbundle` by default. Run this flow from a monorepo checkout
containing both modules; no additional test artifact needs to be installed.

Test configurations use the layout
`testConfigurations/<folder>/<name>/<chainId>/<chainId>.chain.qip.yaml`.
Use `folder` and `name` from the tested element's `description.yaml` or `description.yml`.
For example, the `choice` chain belongs under `routing/condition/<chainId>/`.
Keep each chain's `resources/` directory beside its YAML file. An element directory can
contain several chains; supporting chains belong beside the scenario's primary chain.
The `routing/chain-call-2/` directory groups the ordinary and nested async chain-call scenarios
with their subchains. Shared imported services stay under `testConfigurations/services/`.

The producer discovers chain files recursively and copies each complete chain directory into
a temporary `chains/<chainId>/` import tree. It also copies the shared services. This preserves
the catalog's import format and relative resource references while keeping the source files grouped by element.

`MicroEngineSnapshotRouteExecutionTest` reads the manifests in
`micro-engine/src/test/resources/testspecifications`, including their subdirectories.
Keep one scenario manifest per element type, including its broker failure and MaaS cases.
Group different chain configurations as deployments within the same manifest.

Each scenario gets a fresh Quarkus CDI component container and Camel context. All
its deployments share this context. The production `CustomXmlRoutesBuilderLoader`
creates the generated beans, preprocesses the source, and loads its routes.

Set a scenario's `deployment` to select its subject from the manifest's declared
deployments. The runner loads that deployment and its transitive `dependsOn`
dependencies, along with their fixtures. Declare interactions only for those fixtures.
Omitting `deployment` preserves the manifest's subject and loads all declared deployments.

Fixtures start external services or provide controlled collaborators. HTTP, GraphQL,
Kafka, RabbitMQ, and JMS fixtures retain the generated component beans. The JMS fixture
provides a test JNDI service that resolves the container's connection factory; the
production `JmsComponentBuilder` still creates the component.

Stateful fixtures bind their processors to the routes of their own deployment, so
log capture, checkpoints, and async completion records stay independent in the shared
context. Circuit breaker fixtures inspect only their deployment's breakers. HTTP
drivers select the subject deployment's routes and bind their processors under scoped
names, so CDI beans cannot override the driver's error and completion handling.
Pub/Sub fixtures use separate component
instances to keep publisher caches independent, even when deployments use the same topic.
The JMS fixture selects the connection factory before each deployment's XML is loaded;
the production destination resolver creates destinations through that factory's session.

The shared execution support checks each invocation's body, headers, properties, and
failure expectations, then verifies fixture interactions. After fixture completion, it
checks the expected properties on the same returned exchanges again to detect changes
from asynchronous branches. Body assertions run once because reading a stream body
can consume it. A sequence of invocations shares the scenario's state.

Fixture startup has a shared 120-second deadline per scenario. Set
`-Droute.contract.setup.timeout.seconds=180` to change it. This deadline covers
`fixture.start()`, including Docker container startup; route loading and CDI configuration
stay on the JUnit thread. Invocation deadlines remain separate.
On startup failure, cleanup runs in reverse order on the startup worker. The caller waits
up to five seconds for cleanup. If Docker ignores interruption, the test reports the timeout
and pending cleanup; the worker closes its fixtures after startup returns. It never stops
a container concurrently with its startup.
Progress logs identify the target, scenario, selected deployments, fixture startup,
each invocation, verification, and completion time.

Async fixtures release their gates together, await all
branches, and then check the shared session counter. This also supports an async branch
that calls another chain with its own async branch. Fixture verification uses the runner's
shared invocation deadline, configured by `route.contract.invocation.timeout.seconds`
(30 seconds by default). On timeout, the runner interrupts the wait and releases the
branch gates before stopping the context.

HTTP Service Call fixtures accept `response.delayMillis` to delay a response through
WireMock. The value is a nonnegative integer in milliseconds; omitting it adds no delay.
Keep delays below the HTTP and invocation timeouts when testing slow successful calls.

Kafka fixtures publish through the generated sender and its production client factory to
a real Kafka container using the JVM image `apache/kafka:3.8.0`. The native image crashed
during GraalVM startup with `SIGSEGV` and exit code 99.
The shared `kafka-sender.yml` specification contains all seven
Kafka chains and runs all 29 scenarios with `-Dsnapshot.target=kafka-sender`.
Each scenario selects one deployment and starts its broker. A full Kafka run starts 29 brokers
and executes 263 invocations: 18 in the Short scenario and 245 across 28 Long scenarios.
Compatible invocations share the producer configuration, partition count, and broker state.
Separate scenarios cover different producer options and synchronous modes. Initial connection
checks for untrusted, expired, and mismatched TLS certificates each use a fresh producer.
Within a combined scenario, offsets account for earlier records in each topic and partition,
including retries that produce duplicates and batches that fail after sending some records.
Unkeyed records with automatic partition selection follow checks that require exact offsets.
The fixture uploads the broker startup script to a temporary path and publishes it with an
atomic rename after the upload completes. This prevents `Text file busy` errors when the
container shell tries to execute a script that Docker is still writing. The plaintext and TLS
fixtures use the same publication step. A test with a paused upload checks this ordering:

```bash
./mvnw -pl micro-engine -Dtest=KafkaSnapshotContainerIT -Dgpg.skip=true test
```

The main chain uses StringSerializer, PLAINTEXT, a dynamic key,
and a request ID override. Invocations in a scenario share the Camel context and broker.
Using the same key keeps the dynamic endpoint URI unchanged for recovery checks.
The fixture registers the production request ID context provider and restores the
previous providers and context when the scenario ends.
Context assertions observe the route immediately after its production restore processor.
The asynchronous Kafka sender can resume the route on a different thread from the caller.

Compatible scalar, numeric, binary, batch, and typed-header cases share a scenario to reuse its
broker and route and verify stored records once. Each invocation retains its assertions and
recovery checks; expected offsets account for earlier records in the same topic and partition.
Invocation IDs identify individual cases in progress logs and assertion failures. An unexpected
failure stops the remaining invocations in that scenario. Security, broker mutation, and producer
configuration cases retain separate scenarios where they need different initial conditions.

The `kafka-sender-sasl-plain` chain uses SASL/PLAIN with a fixed key and credentials from
exchange properties. Its broker authenticates users and enforces topic ACLs with
`StandardAuthorizer`. The `writer` user starts with Describe and Write permissions;
`reader` starts with Describe only. Their passwords are `writer-password` and
`reader-password`. Fixture administration and record verification use a separate admin
principal, so sender permissions cannot prevent assertions from reading stored records.
These are isolated test credentials. This chain uses SASL_PLAINTEXT.

The `kafka-sender-no-context` deployment uses the exported chain with `propagateContext: false`.
Its seven scenarios check that Authorization and X-Request-Id stay absent from stored records,
while custom headers survive successful sends, batches, and retries. Context restoration still
runs after publication and failure. Successive calls use different incoming context values,
then omit them to check that a previous call's context does not leak. Missing request IDs are
generated at route entry and remain in the local context without being sent to Kafka.
Batch cases include a tombstone, an empty batch, and partial serialization failure. Authentication
failure, Write permission revocation, TCP reset, and lost acknowledgment cases also check recovery.
Recovery keeps the same producer when credentials remain unchanged. A wrong-password case
changes credentials and creates a new producer.

The `kafka-sender-sasl-ssl` chain retains SASL/PLAIN and adds TLS with hostname
verification. Its proxy terminates the client TLS connection and establishes a second
verified TLS connection to the real broker's SASL_SSL listener. It observes Kafka frames
between these connections to inject Produce failures and count authentication attempts.
The client-facing certificate can change independently of the broker's fixed certificate.
Admin and record verification use a separate authenticated broker listener, so a rejected
sender certificate cannot hide a message that was stored unexpectedly.

The fixture creates temporary PKCS12 stores from the test PEM resources and removes them
during cleanup. The sender trusts only the test CA, preserving leaf expiry validation.
Trust settings belong to the sender endpoint; the fixture does not change JVM trust settings.

Kafka `expectedRequest.count` counts stored records, not logical sends or network retries.
An invocation can send a batch, deliver no records after an error, or produce duplicates
after a lost acknowledgment. `response.properties.expectedRecords` specifies individual
record expectations: `topic`, `key`, `body`, `bodyRepeat`, `keyHex`, `bodyHex`, `headers`,
`partition`, `offset`, and `timestamp`. Fields inherit from `expectedRequest`; explicit null and empty
keys remain distinct. `keyHex` and `bodyHex` compare exact bytes without invoking a Kafka
serializer. They override inherited key/body expectations; do not combine a hex field with
its key/body field in the same record. An empty hex string expects zero bytes, while an
explicit null key/body expects null.
`bodyRepeat` repeats a string body for exact payload comparisons without storing large
strings in YAML. Automatic partitioning cases omit the partition header and check exact
partitions and offsets for repeated ASCII and Unicode keys. With four partitions, Kafka
hashes UTF-8 keys `order-a`, `order-b`, and `order-\u2615-202` to partitions 1, 3, and 0, respectively.
The YAML parser decodes `\u2615` to U+2615 before the key is encoded as UTF-8.
These fixed expectations apply to those keys and that partition count.
Assertions read records from Kafka and check order within each partition. They do not
claim a total order across partitions or exercise the QIP Kafka receiver.

Kafka `response.properties` also accepts these controls:

- `expectedSendCount` sets the expected number of logical sends for an invocation.
  It defaults to the invocation's repeat count. Set it to zero, along with
  `expectedRequest.count: 0`, when an invocation does not use a loaded Kafka fixture.
  A failed send can still require `expectedSendCount: 1` and `expectedRequest.count: 0`.
- `topicPartitions` sets the topic partition count for the scenario; the default is three.
  Topics are created explicitly with broker auto-creation disabled.
- `increaseTopicPartitionsTo` increases the total partition count before an invocation.
  The expansion scenario warms producer metadata, rejects a missing positive partition,
  creates that partition, and checks delivery to both the new and an original partition.
- `topicMaxMessageBytes` changes the topic's `max.message.bytes` limit before an invocation.
  Size scenarios distinguish client rejection from broker rejection, check recovery, and
  retry a rejected payload after raising the topic limit.
- `expectedProduceAttempts` checks the number of Produce requests during an invocation.
  Client size rejection and missing partitions expect zero requests; broker size rejection expects one.
- `topicAction: delete` removes the topic before the invocation. `topicAction: recreate`
  creates it again; offsets belong to the new topic, so they restart at zero.
- `disconnectBeforeProduce: true` resets a connection before forwarding a Produce request.
  The Kafka client must reconnect and retry the send.
- `dropProduceResponses` discards successful Produce responses after the broker accepts
  records. This tests ambiguous delivery and SDK retries against actual stored records.
- `connectionAction: disconnect` keeps the producer connection unavailable until a later
  invocation declares `connectionAction: recover`.
  Combine it with `disconnectBeforeProduce: true` to start the outage when the next
  Produce request arrives. This tests delivery expiration after metadata lookup completes.
- `timingOverrides` bounds fault scenarios with `maxBlockMs`, `requestTimeoutMs`,
  `deliveryTimeoutMs`, and `retryBackoffMs`. These settings apply consistently throughout
  the scenario; they do not change acknowledgments, retries, or idempotence.
- `verifyMetadata: true` compares returned record metadata with the records stored in Kafka.
- `producerProperties` sets `enableIdempotence`, `requestRequiredAcks`, `compressionCodec`, or
  `recordMetadata` on the existing sender endpoint. Values remain fixed throughout a scenario.
  The boolean options take `true` or `false`; `requestRequiredAcks` takes a string (`all`, `-1`, `0`, or `1`);
  `compressionCodec` takes `none`, `gzip`, `snappy`, `lz4`, or `zstd`.
- `expectedAcks` checks the acknowledgment setting in received Produce requests (`-1`, `0`, or `1`).
  `expectedCompressionCodec` checks the codec on each observed record batch. The proxy reads Kafka
  request and batch headers before forwarding; it also counts attempts interrupted by an injected reset.
- `security: sasl-plain` enables the authenticated broker; `security: sasl-ssl` adds TLS. The fixture
  checks the generated sender's protocol, mechanism, and JAAS module; it preserves its credentials.
  The TLS profile also requires the generated `sslEndpointAlgorithm: https` setting.
- `tlsCertificate` selects `valid`, `rotated`, `untrusted`, `expired`, or `wrong-hostname`
  for the client-facing listener. Each change installs a fresh SSL context and resets
  existing connections, so the client must validate the presented certificate again.
- `minSuccessfulTlsHandshakes` and `minFailedTlsHandshakes` check client handshake counts.
  Every successful TLS publication must also use a SASL-authenticated TLS connection.
- `aclAction: deny` revokes Write permission; `aclAction: allow` grants it. Both retain
  Describe permission. `aclPrincipal` selects the username and defaults to `writer`.
- `minSuccessfulAuthentications` and `minFailedAuthentications` assert minimum counts
  of sender SASL responses during an invocation. Admin and verification consumer connections
  are excluded. Each successful publication must use a connection that authenticated successfully.

The TCP proxy is advertised as the broker address, so connections discovered through
Kafka metadata also pass through it. The fixture checks that each requested fault occurred.
SASL scenarios cover invalid credentials, credential changes after a successful send,
missing Write permission, permission revocation and restoration, and authentication
of new connections after resets and prolonged outages.
They also check delivery after a lost acknowledgment, including duplicates with the
chain's existing producer settings. Reconnecting authenticates a new connection; these
scenarios do not exercise periodic SASL reauthentication on an existing connection.
TLS scenarios cover trusted certificate rotation, untrusted issuers, expired certificates,
hostname mismatch, credential errors over TLS, connection resets, lost acknowledgments,
and prolonged outages. The seven Kafka chains do not cover other SASL mechanisms, mutual TLS,
or replica failover.
With cached topic metadata, a rejected certificate can cause delivery expiration first.
The warm-connection scenario checks that timeout, the rejected handshake, and the absence
of the rejected record. After certificate restoration, the SDK can surface a saved TLS
error before recovering; its timing depends on background metadata updates. Recovery
assertions use separate scenarios that start with an invalid certificate.

The test-only `repeatedBody` driver accepts `endpointUri` and a positive integer `repeat`.
It repeats the invocation's string body before sending it through the original chain.
The client size scenario sends a payload near the default 1 MiB request limit, rejects
a larger payload, and confirms recovery through the same producer.

The numeric chain uses IntegerSerializer for keys and LongSerializer for values over
SASL_SSL. Cases cover zero, negative values, both type boundaries, integers above 2^53,
null keys, tombstones, batches, serialization failures, and recovery after network faults.
Assertions check exact big-endian bytes, record metadata, and restored request context.

These serializers require Java Integer/Long inputs. The sender does not convert numeric
strings or Integer values to Long. A configured endpoint key is a String and takes
precedence over `kafka.KEY`; numeric-key cases leave the dynamic key empty and supply
an Integer header. The specification also checks the configured-key failure explicitly.
Ordinary batch entries retain their types and use null keys, even when the outer exchange
has a key. A serialization error stops the batch after already accepted entries.
The fixture waits for the authenticated Produce request before checking connection counters,
because an earlier batch entry can still be queued when serialization fails. It then checks
the stored records through the verification consumer.

The test-only `numericInput` driver prepares types that YAML cannot distinguish for small
numbers. Its parameters are `endpointUri`, `bodyType` (`integer`, `long`, or `long-list`),
and optional `headerTypes` mapping header names to `integer` or `long`. It preserves nulls
and prepares inputs before invoking the original chain. It does not alter sender behavior
or expected values. Invocations that need the original YAML types use the ordinary endpoint driver.

The binary chain uses StringSerializer for keys and ByteArraySerializer for values over
SASL_SSL. Cases compare all 256 byte values, invalid UTF-8, embedded zero bytes, empty
arrays, and tombstones against records read by a ByteArrayDeserializer. Empty arrays
produce one record; empty batches produce none. Connection resets and lost acknowledgments
must preserve the same bytes, including duplicate records after a lost acknowledgment.

Scalar strings use Camel conversion and honor `CamelCharsetName`; the header takes
precedence over the exchange property. The enabled Jackson fallback converts numeric,
boolean, and object bodies to JSON bytes. Ordinary batch entries bypass these conversions,
so a String inside a batch fails serialization even though a scalar String succeeds.
Batch cases check accepted entries, rejected entries, skipped trailing entries, and recovery.
The buffer scenario captures an existing conversion limitation: a partially consumed
ByteBuffer fails serialization, while a subsequent byte array succeeds through the same
producer. Buffers at position zero and input streams are converted successfully.

The test-only `binaryInput` driver accepts `endpointUri` and `bodyType`: `bytes`,
`byte-buffer`, `input-stream`, or `batch`. Scalar modes decode a hex string and preserve
null; buffer mode also accepts `bufferPosition`. Batch mode decodes `{hex: "00ff"}`
entries and preserves other values for mixed-type failure cases. Each call gets fresh
binary inputs. The driver invokes the original chain without changing its serializers.

Synchronous Kafka scenarios set `response.properties.synchronous: true` in the fixture. This setting
applies to the endpoint for the entire scenario, alongside the broker address and timeout settings.
The element descriptor does not expose this option, so it is configured by the fixture rather than
read from the exported chain. These scenarios execute the production synchronous producer through
the generated route. They check scalar and batch publication, tombstones, empty batches, serialization
failures, Write permission rejection, metadata, and context restoration after success and failure.

Producer option scenarios use the existing exports and production client factory. With idempotence
enabled, a lost acknowledgment causes two Produce attempts and one stored record. With `acks=1` and
idempotence disabled, the same fault produces two stored records. Both scenarios send a subsequent
message through the same producer. The `acks=0` scenario checks received requests and reads the stored
records separately; producer completion provides no broker acknowledgment. These tests use one broker.

Compression scenarios check `gzip`, `snappy`, `lz4`, and `zstd` on actual record batches, then compare
the consumed scalar and batch values, including Unicode, tombstones, and empty strings. Setting
`recordMetadata: false` checks that metadata is absent on the outer exchange and nested Message or
Exchange items. Scenarios cover asynchronous and synchronous publication, empty batches, partial
serialization failure, and recovery.

The `kafkaBatchInput` driver accepts `endpointUri`, `itemType` (`value`, `message`, or `exchange`),
and `containerType` (`list` or `iterator`). Message and Exchange entries use `{body, headers}`;
value entries retain their YAML types. The driver retains the created items in the test-only
`snapshot.kafkaBatchItems` exchange property so the fixture can count an Iterator without consuming
it and check metadata on each nested Message or Exchange. Wrapper scenarios use a chain without a configured key to check individual topic, key, partition,
and timestamp overrides, value conversion, partial failure, and recovery. Separate scenarios check
that a configured key overrides the key header on an inner message. Kafka
records receive the outer message's custom headers; headers specific to an inner item are omitted.

The `typedHeaders` driver accepts `endpointUri` and a `headerTypes` map. It prepares `bytes` from
hexadecimal strings and converts declared `integer`, `long`, and `double` headers to those Java types.
The `date` type converts epoch milliseconds to `java.util.Date` for AMQP timestamps.
Undeclared headers retain their input types. Kafka `expectedRecords.headersHex` maps header names to
exact hexadecimal bytes and requires one value per named header. The scenarios check binary values,
empty bytes, numeric boundaries, booleans, Unicode strings, and omission of unsupported object headers.
They also check the stored header bytes after a connection reset and a lost acknowledgment.

The `rabbitmq-sender.yml` manifest defines four scenarios and 89 invocations across three
exported chains. Functional scenarios cover bodies, headers, context overrides, disabled
context propagation, and configured routing keys. Broker failure checks cover credentials,
vhost access, connection loss, broker restart, and channel errors. The MaaS scenario resolves
a classifier through the production MaaS resolver and publishes with its returned credentials
and vhost. The fixture supplies only the external MaaS client response.

Invocations in a scenario share one broker and Camel context. The fixture runs the production
sender and context processors, registers request ID, version, and test-header context providers,
and restores the previous providers and context when the scenario ends.

The functional chain has four independent trigger-to-sender routes: default settings,
disabled context propagation, a context override, and a configured routing key.
Invocations select the existing entrypoint for the sender they exercise.
`publishes-messages-through-sender-variants` contains 18 Short invocations;
`publishes-messages-with-routing-and-context-and-recovers` contains 47 Long invocations.
The broker failure scenario, `recovers-after-credential-permission-and-broker-failures`,
contains 22 invocations. MaaS has its own scenario with two invocations.
A full RabbitMQ run starts four brokers. Select all four scenarios with
`-Dsnapshot.target=rabbitmq-sender`, or add `snapshot.scenario` to select one scenario.
Logs and failure messages identify each invocation; an unexpected failure stops the remaining
invocations in that scenario.

Sender fixtures in the same deployment share one observation queue per vhost. The queue
receives messages from the declared bindings for all senders, and the fixture reads it once
after each invocation. Each sender's prepared-send count identifies whether it was invoked.

RabbitMQ `expectedRequest.destination` specifies the exchange. `expectedRequest.key`
specifies the routing key, including an empty string; omitting it uses the generated endpoint's routing key.
`expectedRequest.count` counts messages delivered to the fixture's observation queue
across the invocation's repeats.
Use zero for local failures or sends with an unbound routing key. The generated `InOnly`
sender can complete successfully for an unbound key, so zero delivery alone does not
imply a route failure. Declare route failures separately with `expectedFailure`.
`expectedRequest.body` compares the received bytes as UTF-8 text,
`expectedRequest.headers` checks AMQP user headers. With `count: 0`, it checks prepared
Camel headers before the send. `expectedRequest.properties`
checks Camel exchange properties captured before the send. Binary user header values
use Base64 strings in expectations; nested maps and lists retain their structure.

RabbitMQ `response.properties` accepts these controls:

- `expectedSendCount` checks the number of attempted sends through a sender. It defaults
  to the invocation's repeat count, including sends that fail or use an unbound routing key.
  Set it to zero, with `expectedRequest.count: 0`, for each inactive sender in a chain
  with several entrypoints. A zero delivery count alone still expects a send attempt.
- `bindings` is a list of `{exchange, routingKey}` entries. Before any invocation, the
  fixture declares these direct exchanges and binds its observation queue to them.
  The original exchange and routing key are bound automatically. Declaring an expected
  destination or key does not create an additional binding.
- `expectedMessageProperties` checks AMQP fields: `contentType`, `contentEncoding`,
  `deliveryMode`, `priority`, `correlationId`, `replyTo`, `expiration`, `messageId`,
  `timestamp`, `type`, `userId`, `appId`, and `clusterId`. Delivery mode is numeric
  (`1` or `2`); timestamps use milliseconds since the Unix epoch.
- `expectedBodyBase64` checks exact body bytes, including empty or non-UTF-8 payloads.
  It takes precedence over the UTF-8 comparison in `expectedRequest.body`.
- `expectedBodyLength` and `expectedBodySha256` check the byte length and lowercase
  SHA-256 digest. They keep large payload assertions compact.

- `expectedContextHeaders` maps broker header names to initialized context header names.
  For example, `{X-Request-Id: X-Request-Id}` compares the published ID with the generated
  ID for that exchange, including repeated invocations.
- `expectedPreparedHeaders` checks Camel headers before the send for any message count.
- `brokerAction` selects a broker operation through its `action` field: `change-password`,
  `restore-password`, `delete-user`, `restore-user`, `deny-vhost`, `restore-vhost`, `deny-write`,
  `restore-write`, `close-connections`, `stop`, or `start`. Account operations also specify
  `username`, and `password` or `vhost` where required.
- `expectedChannelCloseCode` waits for a broker channel or connection close with the given
  AMQP code. Unconfirmed sends can complete before a broker rejects the publication.
- `maasClassifier` and `maasNamespace` specify the expected MaaS lookup. The fixture checks
  the resolved endpoint and preserves its address, credentials, and vhost during publication.

The fixture verifies records after each invocation and checks for extra records at the end.
Durable observation queues survive `stop_app` and `start_app`. Failure cases are followed by
successful publication through the same sender. Context checks compare all serialized context
headers before and after each invocation. See the
[RabbitMQ coverage limits](SNAPSHOT-COVERAGE.md#additional-rabbitmq-chains) for cases outside
these generated scenarios.

`RabbitMqEndpointContainerIT` checks trusted and untrusted TLS certificates, publisher acks,
queue-overflow nacks with recovery, and mandatory returns with a subsequent routed publication.
These tests configure the endpoint and Spring template directly because the sender element does
not expose those options. Run them separately from the generated snapshot targets:

```bash
./mvnw -pl micro-engine -Dtest=RabbitMqEndpointContainerIT -Dgpg.skip=true test
```

The `pubsub-sender.yml` manifest defines three scenarios and 28 invocations for one
exported chain. The Short scenario contains two invocations. A Long scenario combines
25 invocations that share one emulator, OAuth issuer, and publisher. Token refresh runs
first so the initial send obtains a short-lived token from an empty credentials cache.
Later invocations reuse the refreshed token. Topic recreation precedes the final topic
deletion. The separate OAuth rejection scenario starts with an empty credentials cache
to check `invalid_grant`. Each scenario starts its own emulator.

Pub/Sub fixtures check each invocation's Publish requests, including the topic, payload,
attributes, and ordering key. `expectedRequest.key` specifies the ordering key; omitting
it requires an empty key. `expectedRequest.count` counts received RPCs, including rejected
requests and SDK retries, with one message per RPC. Each declared interaction expects
one logical send per invocation repeat, even when authentication or a paused ordering
key prevents an RPC. Expected publication failures come from the invocation's
`expectedFailure`, independently of injected errors.
Object payloads are deserialized independently of
the production serializer. The fixture runs the production context propagation and
restore processors with the standard request ID provider registered for the scenario.

The generated Pub/Sub sender uses its production publisher factory and OAuth credentials
over TLS. The fixture redirects the service account's token endpoint to a local HTTP
server that verifies the JWT signature, issuer, audience, and scopes. The gRPC endpoint
accepts only unexpired tokens issued by that server. The fixture installs a temporary test truststore
and restores the JVM properties during cleanup; the checked-in TLS key is for tests only.
The chain publishes to `sandbox-messaging` using a service account from `sandbox` to
exercise separate credential and topic projects. The fixture does not require them to match.

Pub/Sub `response.properties` accepts these controls:

- `grpcStatus` and optional `grpcMessage` reject a publication. Retryable statuses also
  require a positive `grpcFailureCount`; the fixture rejects that many requests, then
  accepts the retry. The production SDK performs the retries.
- `oauthError: invalid_grant` rejects the token exchange before any Publish RPC reaches
  the server. `expectedTokenRequests` checks the invocation's token-request count,
  including zero when a subsequent send reuses a cached token.
- `shortLivedAccessToken: true` issues a token with a two-second lifetime. Set
  `expireAccessToken: true` on the next invocation to await its actual expiration.
  The production SDK must obtain a new token before publishing; the fixture checks
  that the accepted Bearer token changes. A third send checks reuse of the refreshed token.
- `lostPublishResponseCount` accepts a positive number of publications in the emulator,
  then discards their responses and returns `UNAVAILABLE`. The SDK retries the same
  request, so the subscriber receives duplicate payloads with distinct message IDs.
- `disconnect: true` arms a TCP proxy immediately before the sender. The proxy drops
  the next client write, resets the established connection, and rejects the first
  reconnection. Later connections reach the same TLS server on the same proxy address.
  The fixture verifies the reset, rejected connection, and recovery; no gRPC error is injected.
- `topicAction: delete` deletes the topic in the emulator before an invocation.
  `topicAction: recreate` first verifies the old subscription's backlog, then creates
  the topic again with a new subscription. The Camel context and publisher remain alive.

Every successful publication reaches the official Pub/Sub emulator in a Docker container.
An ordering-enabled pull subscription is created before publication. Delivery verification
waits until all invocations finish, then pulls and acknowledges the backlog. It checks
message IDs, bodies, attributes, and the observed order within each nonempty key, without
sorting. Messages without a key are matched by ID without imposing an order.
Failures configured with `grpcStatus` occur before acceptance and require one delivery
per logical send. Lost responses occur after acceptance and require additional deliveries.
The fixture checks the total accepted publications separately from client acknowledgments;
the sender's message-ID header must match the final successful response.
Invocations therefore omit fixed message-ID header expectations.
The emulator does not exercise Google IAM or the live OAuth issuer.
The deletion invocations check the emulator's actual `NOT_FOUND` response. After topic
recreation, the failed ordering key remains paused and produces no Publish RPC; a
different key succeeds through the same publisher. The fixture does not call
`resumePublish` or recreate the publisher to hide this behavior.

Circuit breaker fixtures accept `awaitState` to wait up to 10 seconds before sending
an invocation. The fixture polls the state every 25 milliseconds in the invocation
thread and propagates interruption. It observes state without changing it; use it to
verify automatic recovery with `automaticTransitionFromOpenToHalfOpenEnabled` enabled.
If `transitionToState` is also specified, that transition runs before the wait.
`expectedState` still checks the state immediately after the invocation. Context cleanup
returns the breaker to `CLOSED`.

Each `async-flow` entry in `expectedExchanges` can declare `expectedFailure` with the
exact exception `type`, `message`, and complete `cause` chain. Omitting it requires that
branch to succeed. Expectations match completed branches independently of completion
order. Use `expectedExchanges: []` when a scenario does not invoke that fixture's deployment;
the fixture then verifies that no branches were dispatched or completed.

Failure expectations compare the exact exception type, message, and complete cause chain.
For `CamelExchangeException`, write `Exchange[<exchange-id>]` in the expected message:
the comparison replaces the exception's exchange ID with this placeholder. The rest of
the message and each cause are still checked exactly.
For a fixture URL with an ephemeral port, write `<loopback-port>` as the port in an
expected `http://` or `https://` URL on `localhost` or `127.0.0.1`. Only messages containing
that explicit placeholder normalize local numeric ports; the host, path, and other text
remain exact. Each nested cause opts in separately.
Kafka batch expiration messages can use `<elapsed-ms>` for the measured batch age,
for example `Expiring 1 record(s) for test-topic-1:<elapsed-ms> ms has passed since batch creation`.
This placeholder applies only to Kafka `TimeoutException`; the record count, topic,
partition, remaining message, and cause chain still match exactly.
For `CertificateExpiredException`, use `NotAfter: <certificate-expiry:2019-01-01T00:00:00Z>`
to assert a specific expiry instant independently of the JVM time zone. The helper
compares the actual message with that instant formatted in the current time zone;
different expiry dates, exception types, and causes still fail.

Parallel barrier fixtures in one deployment must have the same `expectedRequest.count`.
Set it to the invocation's `repeat` value to verify parallel execution, or set every
barrier's count to `0` when the invocation uses another deployment. Zero-count expectations
still reject unexpected requests to those barriers.

The `split-timeout` fixture holds a secondary branch before the Script identified by
`sourceElementId`. Use `expectedRequest` to check the exchange entering the gate and
`expectedExchanges` to check the branch after completion. The fixture releases the gate
after route result assertions and waits for the branch's unit of work to finish. It also
releases the gate during cleanup if an assertion fails. Each scenario supports one
invocation with `repeat: 1`; use `count: 0` and `expectedExchanges: []` for scenarios that
do not call the gated branch.

Checkpoint fixtures run the production context saver, loader, and checkpoint mapper.
The fixture replaces the storage service and bypasses retry authorization. Generated
scenarios cover ordinary HTTP saves, retry payload replacement, and the deliberate
omission of checkpoints during chain calls.

For HTTP drivers, `expectedHeaders` and `expectedAbsentHeaders` describe the response
received by the HTTP client. `CamelHttpResponseCode` contains the response status.
Use `expectedExchangeHeaders` and `expectedAbsentExchangeHeaders` to inspect the internal
Camel exchange. `expectedProperties` also describes that internal exchange. Each
invocation can set its own HTTP method and path; the driver installs each route's bridge
once. The component bridge sends the response body, status, and Content-Type.

The context and fixture resources are closed after each scenario.

The scenario suite tests generated micro-engine routes and their bean construction.
Session recording, checkpoint storage, and some propagation behavior remain controlled
by its fixtures.

## Run individual stages

After installing the local integration-build-pipeline, run these commands in order:

```bash
./mvnw -pl runtime-catalog -PsnapshotTests -Dgpg.skip=true clean test
./mvnw -pl micro-engine -PsnapshotTests -Dgpg.skip=true clean test
```

Both commands accept `-Dsnapshot.bundle.directory=/absolute/path`. The standalone
consumer does not compare the bundle with the current source configurations or generator
templates. Regenerate the bundle after changing those inputs; rebuild and install
integration-build-pipeline first when its code or templates change. The full script
and a sequential build from the root reactor perform these steps.

Select a generated target or scenario by its exact manifest ID:

```bash
./mvnw -pl micro-engine -PsnapshotTests -Dgpg.skip=true test \
  -Dsnapshot.target=checkpoint -Dsnapshot.scenario=retries-failed-session-from-checkpoint
```

The target ID is the manifest filename without `.yml`. Either filter can be omitted.
A selection retains the scenario's complete invocation sequence and deployment
dependencies. An unmatched ID fails the test run.

Ordinary `mvn test` runs the module's existing tests and excludes the generated component scenarios.
All component scenario manifests live directly under `src/test/resources/testspecifications`,
including those that use Kafka, JMS, RabbitMQ, or Pub/Sub fixtures. Their route XML comes
from the generated catalog bundle.

Supporting files live under `src/test/resources/snapshot-fixtures`, grouped by element:
`file-read/source.txt` supplies the file input, and `xslt/transform.xslt` supplies the style sheet.
Manifests refer to these files through `classpath:snapshot-fixtures/...` resources.

## Add a scenario

Manifest comments associate each YAML file with `testspecifications.schema.json` for
editor completion and structural diagnostics. Java validates provider parameters,
deployment references, route selectors, and matcher semantics.

1. Add an executable exported chain and its resources under
   `integration-build-pipeline/src/test/resources/testConfigurations/<folder>/<name>/<chainId>/`.
   Take `folder` and `name` from the element description. Give every chain a distinct ID and name
   across the bundle, including additional cases for the same element.
2. Add a manifest under `micro-engine/src/test/resources/testspecifications`. Its file
   name identifies the chain name unless it declares explicit deployments. An explicit
   deployment's `route` identifies the chain by name, regardless of its configuration directory.
3. Set each scenario's `scope` to `Short` or `Long` using the criteria above.
   Use `sourceElementId` to identify fixture nodes in generated snapshots. Use
   `dependsOn` for additional deployments and `invocations` for stateful sequences.
   Set each scenario's `deployment` when a manifest groups independent chains, so
   the runner loads only that chain, its dependencies, and their fixtures.
4. Declare inputs, result expectations, and expected fixture interactions. Keep
   external-system setup in a fixture and preserve the generated component beans.
   When adding a stateful fixture, use `configure(context, routes)` to limit its changes
   to the supplied deployment routes.
5. Run the complete script to verify generation and execution together.

Surefire reports are written under each module's `target/surefire-reports`. The CI flow
saves these with the generated bundle for diagnosis. See
[the scenario coverage matrix](SNAPSHOT-COVERAGE.md) for tested element types and remaining coverage gaps.

The generated scenario suite prints a table at the end of the Maven test output:

```text
Snapshot scenario results
+---------+-------------+---------+
| Element | Scenario    | Result  |
+---------+-------------+---------+
| choice  | choice-if   | PASSED  |
| choice  | choice-else | PASSED  |
+---------+-------------+---------+
Scenarios: 2 | PASSED: 2 | FAILED: 0 | ABORTED: 0 | SKIPPED: 0
```

The first two columns contain the manifest target ID and scenario ID, including composed
chain scenarios. Results come from JUnit after each test's cleanup. A scenario that
correctly verifies an expected route failure is `PASSED`. Filters limit the table to
the selected scenarios. If setup or discovery fails before any results are available,
the output states that no results were recorded; Surefire retains the failure details.
