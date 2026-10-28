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

The `snapshotTests` and `snapshotTestsShort` profiles run micro-engine scenarios in four separate JVMs by default.
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
The short suite includes scenarios from all 27 manifests, including broker-backed senders
whose fixture startup takes longer than one second. It contains 107 scenarios;
the remaining 76 are `Long`.

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

A local comparison ran the same 183 scenarios with one catalog bundle on a Docker
VM with four CPUs and 7.75 GiB of memory:

| Workers | Slowest Surefire worker (s) | Maven wall time (s) | Scenario results |
| --- | ---: | ---: | --- |
| 4 | 719.412 | 782.463 | 182 passed, 1 failed |
| 6 | 751.982 | 831.242 | 182 passed, 1 failed |
| 8 | 812.382 | 901.078 | 181 passed, 2 failed |

All three runs reproduced the known Pub/Sub connection leak. The eight-worker run
also exceeded Kafka's 25-second broker restart readiness timeout. That readiness
budget was increased to 45 seconds after the comparison, within the invocation's
60-second limit. A subsequent eight-worker run passed all 30 Kafka trigger
scenarios, including broker restart. Four workers gave the shortest test and Maven times in these
trials. More workers increased Docker VM CPU pressure and broker startup times;
GC pauses, memory pressure, and IO pressure remained small. Set `snapshot.workers`
explicitly when comparing a different Docker or CI environment.

Compatible JMS cases share broker startup while retaining all 118 trigger
invocations and 55 sender calls.

To execute Kafka scenarios from an existing bundle with two workers:

```bash
./mvnw -pl micro-engine -PsnapshotTests -Dgpg.skip=true test \
  -Dsnapshot.target=kafka-sender -Dsnapshot.workers=2
```

The Python 3 launcher is an optional wrapper around the same Maven command:

```bash
python3 scripts/run-micro-engine-snapshots.py --workers 2 -- -Dsnapshot.target=kafka-sender
```

The launcher prefers the repository's Maven Wrapper (`mvnw` or `mvnw.cmd`).
If the wrapper file is absent, it runs `mvn` from `PATH`.
Pass Maven options after `--`, including `-s`, `-nsu`, and `-Dsnapshot.bundle.directory`.
The launcher starts one Maven process, which manages compilation and the worker JVMs.
The fresh-bundle script `scripts/test-micro-engine-snapshots.sh` also uses four workers by default;
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
Maven generates `MicroEngineSnapshotShard0Test` through `MicroEngineSnapshotShard7Test`.
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
`testConfigurations/<folder>/<name>/<chainId>/<chainId>.chain.cip.yaml`.
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
the production destination resolver uses controlled JNDI bindings or creates destinations
through that factory's session.

The `jms-trigger.yml` manifest uses `artemis-jms-trigger` with the `jms-trigger`
driver. Fourteen scenarios contain 118 invocations across seven exports: the original
queue, three acknowledgment modes, two topic subscribers, three idempotency actions,
a separate transacted queue, and two exports combining redelivery with idempotency
or topic subscriptions. Ten scenarios are Short; broker restart and the three
transacted scenarios are Long. Each scenario shares one Artemis broker across its
invocations and generated consumers. Idempotency scenarios also share one PostgreSQL
store across their trigger branches.

`consumes-message-types-and-resumes-backlog-after-restart` checks message types and
headers before exercising restart and expiration on the same broker.
`recovers-after-script-failure-with-each-acknowledgment-mode` runs the AUTO, CLIENT,
and DUPS cases against their separate queues in one scenario. Each invocation
selects its generated trigger and checks zero deliveries to the other queues.

Artemis containers use `--no-autotune --no-web` when creating the broker instance.
This skips journal timing calibration and the unused web console on every startup.
Each scenario still starts a fresh broker with persistence enabled. Management
observations use the AMQP connection and `activemq.management` address.

The fixture registers `snapshot:jms-trigger` before XML loading, so the production
`JmsComponentBuilder` resolves its connection factory and destination through test
JNDI. Consumer URIs, acknowledgment settings, and `disableReplyTo=true` remain as
generated. Each trigger has its own observation binding. Inactive queues must receive
zero messages; both topic consumers must register before publication. Uncached
topic listeners use the actual subscriber count reported by Artemis management.
This observes broker state without changing generated consumer or transaction
settings. Completion checks match broker-assigned message IDs for every subscriber.

The driver selects the trigger through `sourceElementId`. Its `messageType` parameter
accepts `Text` (default), `Bytes`, `Map`, `Object`, `Stream`, or `Message`. `body`
supplies the payload. Bytes accepts UTF-8 text or integers from 0 to 255; Stream
also accepts a list of byte chunks. Stream observations read a copy of the broker
message so the route retains its original input stream. `headers` supplies raw JMS
application properties. Optional `jmsHeaders` sets `correlationId`, `type`,
`priority` (0 to 9), `deliveryMode` (`PERSISTENT` or `NON_PERSISTENT`), `replyTo`
(a queue name), and `timeToLiveMillis`. These are producer inputs, not trigger settings.
External producers cannot supply Camel exchange properties.

Declare the default publication parameters in the scenario's `driver`. An invocation's
own `driver` replaces those parameters for that invocation. Later invocations without
a `driver` use the scenario's settings, including `messageType`, `jmsHeaders`, and
`messages`. The two inheritance regressions begin with a scalar Bytes or batch override,
then check a scalar Text publication with the scenario's metadata and payload.

For a burst, set `driver.parameters.messages` to a list of objects with `body`,
`headers`, `messageType`, and `jmsHeaders`. The driver publishes all messages before
waiting for completion. The returned body contains the completed bodies in order;
the manifest checks every result. Fixture `expectedExchanges` checks the ordered
incoming bodies, headers, and properties individually. Equal priority and a single
producer/consumer make message order part of these scenarios.

Each trigger interaction sets `expectedRequest.count` to the total received messages,
including all `repeat` executions. Its `body`, `headers`, and `properties` inspect
the consumed exchange before the Script replaces the body with `Triggered`.
`destination` checks the generated queue or topic name.
`response.properties.expectedBodyType` checks the mapped Java type;
`expectedBodyBytes` compares exact bytes, and `expectedAbsentHeaders` checks header
filtering and isolation between messages. Camel filters application properties that
start with `Camel` or `camel`; encoded `org_DOT_apache_DOT_camel` names are decoded
and retained. Scalar invocation assertions inspect the exchange recorded by the
completion processor. Each completion must carry the sent message's `JMSMessageID`,
including when the consumer finishes before `send` returns.

Set `response.properties.consumerAction` for lifecycle scenarios:

| Value | Behavior |
| --- | --- |
| `stop-until-published` | Stops the generated route before publication, verifies that the queued messages have not reached it, then starts it and awaits the backlog. |
| `expire-before-start` | Stops the consumer, publishes messages with TTL and a fresh sentinel, waits for the assigned `JMSExpiration`, then starts the consumer. Only the fresh sentinel may execute. |
| `restart-broker` | Stops Artemis, observes the listener entering recovery, restarts Artemis, and waits for subscription registration. The generated consumer and listener container must retain their identities. Only the test publisher reconnects explicitly. |

Expired message IDs remain forbidden for the rest of the scenario. The basic queue,
backlog, and broker-restart checks publish 64 messages and require 63 receipts/completions,
excluding the deliberately expired message. The fixture verifies `InOnly` and bounded
completion, then stops the consumers and checks input and reply queues for residual
messages. Broker restart has a local 180-second deadline for shutdown and recovery.

`expectedAcknowledgmentMode` and `expectedTransacted` inspect the actual JMS session.
`expectedListenerTransacted` checks the generated listener before publication.
The acknowledgment export uses a Script that fails on the first delivery when
`snapshotFail=true` and succeeds on redelivery. AUTO_ACKNOWLEDGE and
DUPS_OK_ACKNOWLEDGE scenarios expect that failure followed by a fresh successful
message. CLIENT_ACKNOWLEDGE expects the same broker message ID twice, with
`JMSRedelivered=false` and then `true`. The isolated SESSION_TRANSACTED scenario
checks a transacted listener/session, successful commit, rollback with redelivery,
and successful processing after rollback. The template adds `transacted=true` for
this acknowledgment mode. The fixture accepts active queue polling when Spring
creates consumers per receive attempt without caching; topic publication waits
for the broker to report both subscribers. See the
[transactional queue regression](src/test/resources/testspecifications/jms-trigger.yml).

`expectedRedeliveryCount` specifies additional deliveries for each published message.
It requires `expectedCompletions`: an ordered list of body, header, property, and
exception assertions, including the first failed attempt. The driver returns the
last delivery. Topic scenarios use `expectedCompletions` to check each subscriber
independently; input assertions remain in `expectedExchanges`. The fixture also
supports `expectedCompletionBody`, `expectedCompletionHeaders`, and
`expectedCompletionProperties` when the same expectation applies to every completion.

The idempotency/redelivery export combines CLIENT_ACKNOWLEDGE and
SESSION_TRANSACTED with each duplicate action. A failed first attempt retains its
PostgreSQL key. Broker redelivery must skip the main Script and either complete
through `ignore`, run the duplicate subchain, or raise `DuplicateException`. For
`throw-exception`, `response.properties.maxDeliveryAttempts: 3` sets the external
Artemis address policy and the Qpid connection factory redelivery policy before
route startup. CLIENT_ACKNOWLEDGE recovery redelivers locally in Qpid; its limit
is two redeliveries with a REJECTED outcome so Artemis can move the message to
DLQ. These are external fixture policies. `expectedDeadLetterCount: 1` requires
the original JMS message ID, body, and application headers in the actual DLQ after
the third failed attempt. Each action is bracketed by fresh successful messages.
The tests check every completion, exact Script invocation counts, insertion attempts,
and persisted keys; they do not simulate redelivery by republishing a message.

The topic/redelivery export contains one pair of subscribers for each of those
two acknowledgment modes. Five sequential publications per mode exercise both
subscribers successfully, fail each subscriber in turn, require redelivery only
for the failed subscriber, and verify subsequent successful delivery to both.
For SESSION_TRANSACTED topics, the template adds `cacheLevelName=CACHE_CONSUMER`
to retain the non-durable subscription across local transactions. The scenario
checks rollback and redelivery of each subscriber, followed by successful
publications to both. See the
[transactional topic regression](src/test/resources/testspecifications/jms-trigger.yml).

The idempotency export has ignore, throw-exception, and execute-subchain branches.
All use header expressions for the business key and context, with a 600-second TTL.
The real PostgreSQL repository records insertion attempts and stored records;
separate Script counters prove that duplicates skip the main branch and execute the
configured duplicate handler. Cases check context isolation, shared keys between
branches, numeric/string keys, absent/empty keys, expiry/reacquisition, recovery after
rejection, and key retention after Script failure. Expiry changes one database record
through the fixture; the production repository performs reacquisition.

In-flight durability during broker failure, durable topic subscriptions, TLS,
JNDI authentication, and debugger/session context propagation remain outside this
coverage. Raw JMS header mapping does not establish debugger/session propagation.
The broker authenticates the connection factory; test JNDI does not authenticate
the exported username/password.

The `jms-sender.yml` manifest groups independent sender routes in one exported chain.
Its three scenarios contain 55 calls: nine Short calls and 46 Long calls.
Each scenario shares an Artemis broker and the generated components across its
invocations. The functional routes cover Bytes, Text, Map, Object, and Stream messages,
context settings, and a topic with two subscribers.
`recovers-from-conversion-and-broker-failures` checks conversion failures before
exercising authentication, permission, and connection failures on the same broker.
The JNDI lookup scenario starts with fresh connections and destination caches.
Every invocation checks inactive senders with zero send and delivery counts.

JMS fixtures use `expectedRequest.count` for the number of messages received by each
subscriber. `response.properties.expectedSendCount` checks entries into the producer
endpoint and defaults to the delivery count. A rejected send can therefore assert one
attempt and zero deliveries. Each invocation reads its messages and checks for extras;
the generated component and connection factory remain in use for recovery calls.

JMS response properties support these observations and controls:

| Property | Meaning |
| --- | --- |
| `expectedMessageType` | Checks Bytes, Text, Map, Object, or Stream. The default is Bytes. |
| `expectedDestinationType` | Checks `queue` or `topic`. The default is `queue`. |
| `expectedBodyHex` | Checks the exact bytes of Bytes or Stream messages. |
| `expectedJmsHeaders` | Checks JMS metadata such as `JMSCorrelationID`, `JMSType`, and `JMSReplyTo`. |
| `expectedRestoredContext` | Checks context headers after the production restore processor runs. |
| `subscriberCount` | Sets the number of topic subscribers, all opened before publication. It must stay constant across the scenario. |
| `brokerFault` | Accepts `AUTHENTICATION`, `PERMISSION`, or `CONNECTION_REFUSED`. Omitting it restores the producer credentials for the next invocation. |
| `jndiBinding` | Binds the generated destination name to `{type: Queue, destination: name}` or a Topic equivalent; `null` removes that binding. |
| `expectedJndiLookupCount` | Checks cumulative lookups of the generated destination name. Removing a binding leaves the production resolver's cache intact. |

`expectedRequest.destination` names the destination observed by the fixture. Each
binding observes one destination throughout a scenario. `expectedRequest.headers`
checks JMS user properties, including Camel's encoded names for dots and hyphens;
`expectedRequest.properties` checks the Camel exchange before the producer call.
Use `typedHeaders` with `jmsQueue` or `jmsTopic` for a real `JMSReplyTo` input.
The generated sender disables reply-to; the scenarios assert its absence on the wire.

Broker authentication faults change credentials on the connection factory returned
by test JNDI. Permission faults use an Artemis user without publication rights.
The fixture creates that user through the existing broker management connection,
with a five-second reply timeout.
An independent observer connection retains working credentials to check delivery.
Connection refusal stops and restarts the same container with a fixed host port; observers
reconnect before verifying that the failed call delivered no messages. This tests broker
unavailability and recovery through the same generated components, not interruption of
an in-flight publication. The fixture bounds connection, request, and send waits at five
seconds; these are test connection-factory settings, not exported element options.
The `rejects-connection-refused` invocation sets `timeoutSeconds: 120` to allow time
for Docker shutdown, broker startup, and observer reconnection within the same call.
Artemis and controlled JNDI do not establish WebLogic authentication, transaction,
or acknowledgment behavior.

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

Each invocation has a shared deadline for its setup, all `repeat` executions, and
fixture verification. The default is 30 seconds, configured by
`route.contract.invocation.timeout.seconds`. An invocation may set `timeoutSeconds`
to a positive integer to override that limit for the whole invocation. Omitted or null
values use the configured default. The override does not affect later invocations,
final fixture verification, fixture startup, or the clients' request timeouts.

Invocation and final fixture verification timeouts include suppressed diagnostics in
the Surefire failure report. The runner captures the worker thread's state and stack
before interruption and cleanup. Pub/Sub trigger fixtures also record route and
Subscriber states, broker stream and connection counts, published and acknowledged
message IDs, and the current delivery phase. Capturing this state does not run GC or
make broker calls.

Async fixtures release their gates together, await all
branches, and then check the shared session counter. This also supports an async branch
that calls another chain with its own async branch. Fixture verification uses the runner's
shared invocation deadline, configured by `route.contract.invocation.timeout.seconds`
(30 seconds by default). On timeout, the runner interrupts the wait and releases the
branch gates before stopping the context.

HTTP Service Call fixtures accept `response.delayMillis` to delay a response through
WireMock. The value is a nonnegative integer in milliseconds; omitting it adds no delay.
Keep delays below the HTTP and invocation timeouts when testing slow successful calls.

The `http-sender.yml` manifest runs 20 sender variants in four generated chains through the
`http-sender-http` fixture. Its 18 Short scenarios and one Long scenario contain
111 invocations. They cover GET, POST, PUT, PATCH, DELETE, HEAD, dynamic URIs,
context overrides, correlation IDs, generated external addresses, connection reuse,
and redirects. Failure and recovery invocations share a client, including HTTP
400/401/403/404/500 responses, transport faults, and a response timeout. The timeout
scenario delays a response beyond the exported timeout; it does not simulate a
connection establishment timeout. Incoming GET requests belong to
`http-trigger.yml`; its basic deployment permits GET, and its idempotency deployment permits POST.

Each chain groups related sender configurations under independent entrypoints:

| Chain | Sender variants |
| --- | --- |
| `http-sender-methods` | GET, POST, PUT, PATCH, DELETE, HEAD |
| `http-sender-addressing` | Dynamic URI, explicit external call, default external call |
| `http-sender-context` | Disabled propagation, literal and dynamic overrides, header and body correlation, sequential senders, HTTP error fallback |
| `http-sender-client` | Response timeout, connection reuse disabled |

Scenarios select a chain through `deployment` and a branch through `endpointUri`.
Every invocation includes zero-count interactions for unused senders in that chain.
The HTTP fixture starts a server only for a sender with a positive expected send count
in at least one invocation of the scenario. The count comes from `expectedSendCount`,
or from `expectedRequest.count` when the send count is omitted. A send attempt to an
unused branch is recorded and rejected before HTTP; its zero-count assertion also fails.
This keeps HTTP server startup scoped to the exercised branches while loading all
generated routes and client configurers for the selected chain.

The POST scenario `sends-post-requests-and-recovers-from-failures` groups 38 invocations:
six transport failure and recovery calls, 17 body and HTTP error calls, seven
redirect and recovery calls, and eight charset calls. Connection refusal runs first, before the client has an established
connection. Each HTTP error remains paired with its recovery call. Connection reuse
has a separate scenario so its client and cumulative connection count start fresh.
It also checks reconnection after the server returns `Connection: close`, then reuse
of the new connection. Dynamic URI cases check encoded slashes and percent signs,
literal plus signs, repeated query keys, and empty query values.

HTTP sender fixtures retain the generated client configurer and production sender,
charset, context, and exception processors. Before the HTTP call, the fixture records
the generated URI, then replaces its scheme and authority with the local server's
address. The URI's raw path and query remain unchanged. Use
`expectedRequest.destination` for the generated URI and `expectedRequest.path` and
`query` for what the server receives.

`expectedRequest.count` checks received HTTP requests. The optional
`response.properties.expectedSendCount` checks entries into the sender step and
defaults to the request count. `expectedRequest.properties` checks exchange properties
at each sender entry. Request body assertions compare strings exactly or JSON
structurally; `body: null` requires an empty request body. A null value in
`expectedRequest.headers` requires that outgoing header to be absent.

HTTP sender fixtures accept these response settings:

| Setting | Behavior |
| --- | --- |
| `response.delayMillis` | Delays the response by the specified number of milliseconds. |
| `response.properties.transportFault` | Accepts `EMPTY_RESPONSE`, `CONNECTION_RESET_BY_PEER`, or `CONNECTION_REFUSED`. The first two faults occur after receipt of a request. Refusal stops the server; the next normal invocation restarts it at the same address. |
| `response.properties.expectedConnectionCount` | Checks the cumulative number of TCP connections accepted by the server during the scenario. |
| `response.properties.redirects` | Lists redirect responses as `status` and `location` pairs. Locations must be paths on the same local server, optionally with a query. |
| `response.properties.expectedRequests` | Checks the ordered request sequence, including the initial request and every redirect. Each entry requires `method`, `path`, and `body`; `query` and `headers` are optional. Its length must equal `expectedRequest.count`. |
| `response.properties.expectedRequestBodyHex` | Compares the raw bytes of every received request with the hexadecimal string. Each byte needs two hex digits; an empty string requires an empty body. |
| `response.properties.bodyCharset` | Encodes a string response body using the named charset. Set the response `Content-Type` separately. Omitted values use UTF-8; object bodies remain JSON. |

Charset cases check exact UTF-16LE request bytes when the encoding comes from
`Content-Type`, a `CamelCharsetName` property, or a `CamelCharsetName` header.
They also check a UTF-16LE response after a UTF-8 request, rejection of an unknown
Content-Type charset before HTTP, and a successful UTF-8 request after that failure.
Text and empty HTTP error bodies retain their status and response headers.
An HTTP 500 response with a UTF-16LE body checks error-body decoding and recovery
with UTF-8. Body correlation also retains the outgoing ID for an empty request and
HTTP 204 response, with an exact zero-byte request assertion.

Run connection refusal before the first successful request when asserting
`HttpHostConnectException`: stopping a server with an established pooled connection
can instead fail the next request with `NoHttpResponseException`.

When checking redirects, the top-level `expectedRequest` describes the initial
request. `expectedSendCount` counts entries into the generated sender step, while
`expectedRequest.count` includes every HTTP request in the redirect sequence.
The scenarios check two redirects for each of 301, 302, 307, and 308, including
method, body, and headers at every hop. The current HTTP client turns POST into
GET for 301/302/303 and discards its body and custom headers. For 307/308 it retains
the method, body, and custom headers. The added 303 cases cover a successful response
and a redirect sequence ending in HTTP 500, followed by recovery.

HTTP sender, GraphQL, and JMS fixtures share request-context initialization and production
context processors. `response.properties.expectedRestoredContext` checks context
headers after each restore call, including failed calls. The HTTP sender fixture
registers a scoped test context provider backed by the production
`AllowedHeadersContextObject` for `Authorization`. This allows generated overrides
and incoming-header priority to run through the real context processors. It does
not validate tokens or exercise M2M. Fixture cleanup restores the previous context
and provider registrations.
Response-header collision cases check the returned exchange separately from the
restored context: the exchange retains the response Authorization value and removes
X-Request-Id, while the context restores the original incoming values.

HTTP fixtures initialize request context on the generated `cip-chain` entry routes.
Sender and Catch routes retain that snapshot, including when the entry route itself
contains no sender. JMS and GraphQL fixtures keep their existing route initialization.
The sequential HTTP scenario checks five invocations: success, first-sender failure
with no second send, second-sender failure, and recovery after each failure. The
fallback scenario checks six invocations: primary success, caught HTTP error, fallback
HTTP error, uncaught transport error, and recovery after the two propagated errors.
Assertions include per-sender restoration, response-to-request body flow, correlation
IDs, captured HTTP error details, completion markers, and one Finally execution.

External-address scenarios assert the generated gateway prefix, path, and query
before redirecting the request to the local server. Live gateway routing, TLS, and
proxy configuration remain outside this fixture's boundary.

The `graphql-sender.yml` manifest groups 58 invocations into 12 Short scenarios across
10 exported chains. One scenario combines 24 request, response, variable, and internal
routing checks through the same client. External routing and Authorization checks share
an eight-invocation scenario; header correlation checks share a six-invocation scenario.
The chain with literal `{}` variables also checks an omitted operation name.
Connection reuse and connection closure with recovery have separate scenarios so their
cumulative connection counts start with a fresh client and server.

GraphQL HTTP fixtures accept `response.properties.expectedSendCount` to check how many
times an invocation enters the sender step. The value is a nonnegative integer and
defaults to `expectedRequest.count`, which counts HTTP requests received by the server.
For invalid variables, set `expectedSendCount: 1` and `expectedRequest.count: 0` to check
that parsing fails before an HTTP request is sent. `expectedRequest.properties` checks
the exchange at each sender entry, including entries that fail before HTTP.
Each invocation starts new counts; failure and recovery invocations share the client
and server. The fixture retains the generated HTTP client configurer and GraphQL
processors.

GraphQL HTTP fixtures apply `response.delayMillis` as a WireMock response delay.
`response.properties.transportFault` accepts `EMPTY_RESPONSE` or
`CONNECTION_RESET_BY_PEER`. Both faults occur after the server receives the request,
so `expectedRequest.count` still includes that request. A following invocation can
return a normal response through the same client.
`response.properties.expectedConnectionCount` checks the cumulative number of TCP
connections accepted by that fixture's server across the scenario. Unlike request
counts, this count does not reset between invocations. Use successive calls with
exported `reuseEstablishedConnection: true` and `false` settings to check reuse.

`expectedRequest.destination` checks the generated HTTP address and path
before redirection to the fixture. Camel component options are excluded.
`expectedRequest.path` checks what the server receives. External calls
retain the generated gateway path prefix; the fixture controls the gateway address.
These assertions cover generated routing, not a deployed egress gateway.
The GraphQL routing scenarios use endpoint URLs without query parameters.

GraphQL fixtures use production context propagation, restoration, and correlation
processors. They initialize request context from invocation headers at route entry
and register the production `XRequestIdContextProvider` when Maven disables provider
discovery. `response.properties.expectedRestoredContext` checks context headers
immediately after each production restore call, including the HTTP-error path.
Fixtures restore the previous context providers and context during cleanup.
Use outgoing header assertions and `expectedProperties.correlationId` to check
correlation propagation and receipt. When the outgoing request and successful
response both contain the correlation header, Camel appends the response value
to the outgoing value. The header contains both values in order, and the correlation
processor stores their list representation as a string. When an HTTP error response
contains the header, the exception handler replaces its outgoing value. The correlation
processor is skipped on HTTP errors, so the property keeps its outgoing value.

GraphQL component scenarios check the element's request and response behavior.
Payload logging and session logging belong to separate test scopes.

Authorization scenarios use `m2m: false` and send a Basic header.
`expectedRequest.headers.Authorization` checks the value sent to the external service;
`expectedHeaders.Authorization: { $match: non-empty }` requires a nonblank value after
success, HTTP errors, and recovery. These checks also cover responses that contain a
different Authorization value. They allow replacement or merging with the response
value; they do not require the original credential to remain unchanged.

The baseline chain calls the unauthenticated sender, sets a Bearer header, and calls
the authenticated sender in sequence. Its final body comes from the authenticated call.

`GraphqlCustomEndpointHttpTest` checks HTTP proxy forwarding, a Basic authentication
challenge, trusted HTTPS, and rejection of untrusted, expired, and mismatched certificates.
It also checks a response timeout followed by a successful request through the same
producer and HTTP client. These tests configure the endpoint directly and do not establish
generated-chain support for those settings. Run them separately from snapshot targets:

```bash
./mvnw -pl micro-engine -Dtest=GraphqlCustomEndpointHttpTest -Dgpg.skip=true test
```

The `kafka-trigger.yml` manifest declares 30 scenarios (five Short and 25 Long),
100 invocations, and fourteen exported chains. Run them with
`-Dsnapshot.target=kafka-trigger`. The basic chain uses two topics, one consumer,
String deserializers, PLAINTEXT, and `autoOffsetReset=latest`. The offsets export
contains independent `earliest` and `none` consumers with distinct topics and groups.
Other deployments configure competing consumers, a short poll interval, lifecycle
checks, binary and numeric deserializers, SASL, TLS, and three idempotency actions.
Scenarios select the required deployment. The basic Script throws
`IllegalStateException("Kafka snapshot failure")` when the incoming `snapshotFail`
header is the string `true`; otherwise, it returns `Triggered`. These counts describe
the manifest; execution results are recorded under `target/surefire-reports/snapshot-shards/`.
The complete Kafka trigger target passed all 30 scenarios locally with a regenerated
bundle on 2026-10-12; no scenarios were skipped or aborted.

The `kafka-trigger` fixture retains the generated client factory and the production
Kafka consumer, record processor, trigger processor, and header deserializer. It supplies
an ACTIVE BlueGreen state without a sibling through the runner's existing dependency.
The fixture replaces the external broker address with a real `apache/kafka:3.8.0`
container. Each topic has three partitions. The consumer connects through a TCP proxy;
the test producer and Admin client use a separate PLAINTEXT listener that remains
available during a consumer outage. Security settings come from the generated endpoint.
For SASL_SSL, the fixture supplies the existing test CA's truststore without changing
JVM trust settings. Secured brokers use Kafka's StandardAuthorizer and the reader principal.

After creating topics, the fixture reads their initial offsets. It retries
`UnknownTopicOrPartitionException` while metadata propagates, within one ten-second
deadline. Other failures propagate immediately.

The driver selects the trigger by `sourceElementId`. Its parameters accept `topic`,
`partition`, `timestamp`, `key`, `keyEncoding`, and `valueEncoding`. Both encodings
default to `string`, which accepts strings or null and writes UTF-8. `bytes` accepts a
list of integers from 0 through 255, including an empty list; `integer` and `long`
encode exact signed 32-bit and 64-bit values in Kafka's binary representation. Null
keys and values remain null for every encoding. Invocation headers become UTF-8 Kafka
headers and still accept only strings or null. The ordered `recordHeaders` list accepts
`{key, value}` entries, including duplicate names and null values, and appends them after
invocation headers. The `messages` parameter accepts records with per-record options;
`messageCount` repeats a scalar record or a list entry. Exchange properties cannot be
sent over this driver.

The fixture records inputs after the trigger processor and before downstream processing.
For an idempotent trigger, it observes the input before deduplication. It compares the
exact Java body and key types and their encoded bytes with the published record, then
checks raw headers, timestamp, topic, partition, and offset against the producer's input
and acknowledgment. Input and completion counts must match publications, with offsets
ordered within each partition. It checks for extra deliveries after stopping every route
in the group. These assertions permit interleaving across partitions. Redelivery remains
an error unless the invocation explicitly sets `allowRedelivery: true`.

The type and successful authentication scenarios wait for commits in their final
invocation. That assertion includes the offsets of records from every invocation.
Failure, restart, and idempotency scenarios retain commit checks at their state
transitions.

One fixture can observe peer trigger routes in the same deployment and group. Their
generated endpoints and client factories remain in use, and their inputs and completions
share one runtime. The group export has two consumers on the primary route and one on
its peer. The scenario checks all three assigned partitions with three members, stops
the peer and checks reassignment to the two remaining members, then starts the peer
again. It checks delivery, per-partition order, and broker-stored commits through both
changes.

Kafka trigger `response.properties` supports these controls:

- `startupRecords` publishes records before the first consumer assignment without
  expecting their delivery. Readiness requires the full partition assignment and initial
  offsets committed by the BG consumer. With `latest`, seeded records remain unconsumed.
- `expectedStartupRecords` publishes backlog before route startup and includes it in
  input, completion, metadata, and commit assertions. A driver with `consumeStartup: true`
  collects these records without publishing another message and returns a list of results.
  Observations made before the first invocation are retained.
- `committedOffsets` accepts `{topic, partition, offset}` entries. Kafka Admin commits them
  before the consumer starts. The `none` case checks that existing commits select the
  unread suffix of each partition. An inactive `none` consumer receives zero offsets for
  all its partitions while testing `earliest`.
- `expectedConsumerFailure` uses the standard exception expectation. An observer scoped
  to each route records Kafka poll errors from Camel's error-handler bridge. With
  `awaitConsumerFailure: true`, the driver returns that error without publishing a record.
  Failure scenarios require zero downstream inputs and unchanged initial group offsets.
  For failures that persist through shutdown, the fixture checks those offsets again.
  `resetConsumerFailure: true` clears the observed failure after publishing ignored records,
  so a later invocation must observe another poll error rather than reuse the first one.
- `ignoredRecords` publishes records that the invocation expects to leave unconsumed.
  This includes unrelated topics and malformed records followed by valid records.
- `awaitCommit: true` waits for broker-stored group offsets to equal published offsets
  plus one. Route completion alone does not establish a committed offset.
- `expectedMembers` checks the member count and complete assignment of the subscribed
  partitions. `consumerAction: stop-peer-until-published` waits for prior commits and
  stops the peer before publication; `start-peer` starts it and waits for reassignment.
- `consumerAction: stop-until-published` waits for prior commits, stops the route,
  publishes the invocation, checks zero deliveries, and restarts the same route and group.
- `consumerAction: disconnect-until-published` closes established consumer connections,
  publishes through the direct listener, checks zero deliveries, and restores the proxy.
  Recovery must retain the same Camel consumer.
- `startupUnavailable: true` blocks the proxy before consumer startup. The fixture waits
  for a rejected consumer connection, checks zero delivery and unchanged stored offsets,
  then restores connectivity before collecting the startup backlog. The broker and its
  administration listener remain available during this simulated startup outage.
- `consumerAction: restart-broker` waits for prior commits and restarts the Kafka process
  inside the same Docker container. It preserves broker storage and listener ports,
  checks unchanged stored offsets, and retains the existing Camel consumer before
  publishing the next record. Docker container restart reallocates the fixture's random
  host ports, so the fixture supervisor restarts only the broker process.
  Broker readiness after restart allows 45 seconds. The restart invocation allows
  60 seconds for setup, delivery, and verification; its internal completion wait
  remains limited to 30 seconds.
- `consumerAction: exceed-poll-interval` blocks a record at a processing gate, waits until
  Admin observes zero group members, and releases the record. The scenario then requires
  processing, committed offsets, rejoined membership, and a later valid record. Its
  `allowRedelivery: true` permits repeats only for records published by that invocation;
  assertions still require each unique record, encoded bytes, and per-partition order.
- `consumerAction: stop-during-processing` blocks a record and starts graceful route
  shutdown. It checks that shutdown waits and offsets remain unchanged while processing
  is blocked. After release, it waits for shutdown and the completed record's commit,
  then restarts the route. Pending gates are released during cleanup as well.
- `initialConsumerAccess` sets `allow`, `topic-denied`, or `group-denied` before consumer
  startup. `consumerAccess` applies the same ACL change before an invocation. The reader
  retains Describe permission while Read permission is granted or removed. Recovery
  restores Read access on the running consumer, then checks the protected backlog and a
  new record. Invalid credentials use a separate generated chain.
- `minSuccessfulAuthentications`, `minFailedAuthentications`, and
  `minSuccessfulTlsHandshakes` require minimum cumulative counts observed by the proxy.
  The secured cases check SASL_PLAINTEXT and SASL_SSL delivery, rejected credentials,
  topic Read denial, group Read denial, unchanged offsets during denial, and recovery.
- `expectedAbsentHeaders` checks input header absence. `expectedCompletionBody`,
  `expectedCompletionHeaders`, and `expectedCompletionProperties` apply to every completed
  record. `expectedCompletions` defines individual results, including explicit failures.

The bulk invocation publishes 600 records while the consumer is stopped, then checks
every input and completion after restart. It uses `expectedCompletionBody: Triggered`
instead of listing 600 identical outer results. The Script failure scenario checks a
failed completion, subsequent successful processing, committed offsets, and no replay
after restart. A separate scenario queues valid, failing, and valid records in one
partition before restarting its consumer and checks every completion. These assertions
describe the default consumer error policy and delivery order; they do not measure each
poll's result size. The malformed Integer value and Long key scenarios expect a
`RecordDeserializationException`, zero downstream deliveries, unchanged offsets, and
later valid records blocked behind the same malformed record.

The idempotency exports cover `ignore`, `throw-exception`, and `execute-subchain`.
They derive a business key and context from Kafka headers and check duplicate handling
when payload, transport key, or partition changes. The Kafka fixture observes every
input and commit; `idempotency-postgres` independently counts main Script and duplicate
handler executions and checks stored keys, insertion attempts, record data, and TTL.
Each scenario uses PostgreSQL 17.2 and the production idempotency repository and service
SQL through a test transaction adapter. An unexpired duplicate must leave stored data
and expiry unchanged. The store fixture's `response.properties.expireKey` expires an
existing key, and the next record must reacquire it with the exported 600-second TTL.
Invocations also check distinct keys and contexts, repeated duplicates, and duplicate
handling after a consumer restart. This covers stored state across route
restarts within the scenario; it does not establish recovery after a process restart or
Quarkus transaction interceptor behavior.

For plain groups, `autoOffsetReset=none` uses native Kafka offset resolution. The
regressions cover fresh groups, partially committed partitions, and a stored offset beyond
the log end. Each failure must preserve stored offsets and consume no records. The
valid-offset scenario checks processing and restart. See the
[offset reset regressions](src/test/resources/testspecifications/kafka-trigger.yml).

MaaS and versioned Blue/Green transitions remain deferred. SSL without SASL, mutual TLS,
other SASL mechanisms, offsets removed by retention, partition expansion, and independent
consumer groups subscribing to the same topics remain outside these scenarios. The graceful shutdown gate does not
cover forced shutdown or broker loss while a record is being processed.

Kafka sender fixtures publish through the generated sender and its production client factory to
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
The fixture sets `retention.ms=-1` on every topic it creates or recreates. Records with fixed
historical timestamps must remain available until the scenario verifies them, even when Kafka
runs retention cleanup during a long scenario. Explicit topic deletion still works.
The fixture uploads the broker startup script to a temporary path and publishes it with an
atomic rename after the upload completes. This prevents `Text file busy` errors when the
container shell tries to execute a script that Docker is still writing. The plaintext and TLS
fixtures use the same publication step. The component scenarios exercise this step
when starting their Kafka brokers.

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
- `tlsCertificateOnNextProduce` selects the same certificates but waits for the next
  Produce request before changing the certificate and resetting connections. The proxy
  drops that request before forwarding it to the broker. This places the TLS failure
  after the producer has queued the record, so a delivery-timeout assertion does not
  depend on whether metadata was already available. Verification fails if no Produce
  request consumes the planned change.
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
After restoring a certificate, the fixture retries cached TLS errors and metadata
timeouts on the same producer within its 15-second recovery deadline. Other
authentication errors propagate immediately. The producer's four-second metadata
timeout remains in place for the negative checks.

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
exported chains. Functional scenarios cover bodies, headers, context overrides,
`propagateContext: false`, and configured routing keys. Broker failure checks cover credentials,
vhost access, connection loss, broker restart, and channel errors. The MaaS scenario resolves
a classifier through the production MaaS resolver and publishes with its returned credentials
and vhost. The fixture supplies only the external MaaS client response.

Invocations in a scenario share one broker and Camel context. The fixture runs the production
sender and context processors, registers request ID, version, and test-header context providers,
and restores the previous providers and context when the scenario ends.

The functional chain has four independent trigger-to-sender routes: default settings,
`propagateContext: false`, a context override, and a configured routing key.
With `propagateContext: false`, the template skips both `contextPropagationProcessor` and
`rabbitMqSenderProcessor` and removes the `Authorization` header. The tests expect neither the
request ID nor `Authorization` in the published message, including when the request ID is generated.
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
use base64 strings in expectations; nested maps and lists retain their structure.

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

The exported Pub/Sub element sets `orderingKey` to `${exchangeProperty.orderingKey}`.
Invocations supply that property; the generated route evaluates it before publication and
removes `CamelGooglePubsubOrderingKey` in `finally`. The first invocation checks that the
configured key replaces a different incoming header value. Other invocations check an
omitted property, ordering across repeated keys, and header cleanup after success and failure.

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

The `pubsub-trigger.yml` manifest defines 13 scenarios and 50 invocations across five
generated exports. Two scenarios are Short; the remaining 11 are Long.
Each trigger interaction uses `expectedRequest.count` for the total received deliveries
across the invocation's repeats, including redelivery. Each driver call publishes fresh
message IDs and waits for the completions of that publication.

For `repeat: 1`, the driver waits for `expectedRequest.count` completions, including
expected failed deliveries. For repeated publications that complete once per message,
the total must equal the number of messages per publication multiplied by `repeat`.
The driver then waits for one completion per published message in each repetition.

For repeated publications with additional deliveries, set
`response.properties.expectedCompletionCounts` to one positive integer per repetition.
The list must sum to `expectedRequest.count`. For example, `[2, 2]` expects a failed
delivery and redelivery in each repetition. `[2, 1]` handles an idempotency Ignore case:
the first publication fails and is redelivered as a duplicate; the next publication
is immediately handled as a duplicate. The list starts at its first entry for each
invocation. `expectedCompletions` checks the ordered results across all repetitions,
including exact failures. Ack and nack counts also cover the whole invocation.

The repeat scenarios check normal AUTO acknowledgments, failure and redelivery for
each publication, and unequal delivery counts with PostgreSQL idempotency records.
Subsequent invocations check that completion counts reset while stored keys persist.

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

Failure expectations compare the exact exception type, message, and complete cause chain by default.
Use `anyOf` at any level to list accepted failure shapes. Each alternative checks its own
type, message, and complete cause chain. An `anyOf` object must contain only a nonempty list
of failure expectations.

For system-dependent error text, replace `message` with `ignoreMessage: true` at that level.
The exception type and cause still match exactly. Set `message: null` to require a null
message; omitting both fields or specifying both is invalid. The RabbitMQ `stopped-broker`
invocation uses this form only for the `SocketException` alternative at the end of the cause
chain. It also accepts `EOFException` with a null message; the outer exceptions remain exact.

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
Camel exchange. `expectedProperties` also describes that internal exchange,
including Camel properties such as `CamelDuplicateMessage`. Each
invocation can set its own HTTP method and path; the driver installs each route's bridge
once. The component bridge sends the response body, status, and Content-Type.

The `http-trigger` manifest selects separate basic and idempotency deployments.
The basic deployment uses chain `67d67e35-e881-477b-8203-f893f6958b0b`
at `/routes/trigger-test`. Its seven Short scenarios contain 32 invocations and
33 requests, including one repeated invocation. Empty GET bodies return
`Triggered`. Nonempty text, whitespace, JSON objects and arrays, scalar JSON,
and malformed JSON return HTTP 400 with `Validation Failure` from the exported
validation script. The response retains `application/json` from the standard
error handler even though the script replaces the body with plain text.
The scenarios check failure markers, request header removal, and subsequent recovery.

Other assertions cover unrestricted content types, custom headers and raw query
values, disabled response filtering and correlation receipt, testing session IDs,
Authorization removal, and repeated requests with idempotency disabled.
`expectedAbsentExchangeHeaders` checks headers removed by the generated route or
production processor; it does not establish servlet response header filtering.

The idempotency deployment uses chain `98ee44b6-8bba-43d0-8a7b-67370e11f530`
at POST `/routes/test-w-body`. Four Short scenarios contain 35 invocations and
36 requests. The generated JSONPath expression reads `body.id` and prefixes the
stored key with `POST /qip-routes/test-w-body:`. New keys execute the exported
Script and return HTTP 200; duplicates return HTTP 202 with the duplicate request
body and skip the Script. Changed payload fields, formatting, headers, query
parameters, and content types do not change the body-derived key.

The cases check string conversion of numeric and boolean IDs, explicit null and
empty IDs, whitespace, case, and punctuation. Missing IDs and unsupported bodies
fail before storage and are followed by valid requests. An expired key is acquired
again while an unexpired key remains reserved. Every invocation checks database
keys and insertion attempts independently of the downstream Script count.

The `idempotency-postgres` fixture targets that Script's `sourceElementId` and
observes it without replacing it. Each scenario starts PostgreSQL 17.2, creates
the table from the production migration, and runs the production
`PostgresIdempotentRepository`, `IdempotencyRecordService`, and
`IdempotencyRecordRepository` SQL. A test adapter supplies Hibernate entity
managers and resource-local transactions. This does not verify Quarkus transaction
interceptors. Fixture state and connections are closed after the scenario.

For this fixture, `expectedRequest.count` is the number of downstream Script
executions. `expectedRequest.properties` supports `insertionAttempts`, `key`,
`keyExpiry`, `storedKeys`, `updatedKeys`, and `records`. Attempts reset for each
invocation; stored records persist within the scenario. Keys are sorted in byte
order. Each record exposes its key, data, TTL in seconds, and active flag. Stored
data must equal `{"status":"RECEIVED"}`. `updatedKeys: []` checks that a duplicate
does not modify the record or extend its TTL.

`response.properties.expireKey` moves one existing record's expiry into the past
before a request. The following production insert must renew it with the exported
600-second TTL; the test does not wait ten minutes. These sequential scenarios do
not cover concurrent acquisition, cleanup scheduling, process restarts, or duplicate
actions other than `ignore`.

This driver replaces the servlet consumer with a direct route behind a local HTTP
bridge. It runs the production trigger, validation, exception, and interrupt
processors, but supplies HTTP metadata itself and records calls to the completion
processors. It validates the configured binding name without executing
`HandlingHttpBinding`. Servlet method/path rejection (including HTTP 404/405),
OPTIONS/HEAD behavior, query-to-header parsing, response header filtering, and
chunking require servlet-level tests. The bridge accepts scalar request headers
and uses UTF-8 for bodies; it does not test repeated header values or other encodings.

Other trigger settings require additional exports: methods beyond GET and POST,
parameterized paths, content-type allowlists, JSON schemas, enabled response
filtering or correlation receipt, other validation handlers and duplicate actions,
timeout termination, and access policies. Downstream failures also need a chain
that can fail after validation. M2M is outside this suite.

Map-value assertions, including `expectedHeaders`, accept `{ $match: non-empty }`.
This matcher requires a nonblank string or a collection containing at least one
nonblank string. Missing or null values, blank strings, and collections without a
nonblank string fail the assertion. Literal expectations still use exact equality.

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
