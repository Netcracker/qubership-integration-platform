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

To build every Maven module and run the generated scenarios in a sequential reactor:

```bash
./mvnw clean install -Dgpg.skip=true -PsnapshotTests
```

The reactor relies on the module order in the root POM: runtime-catalog generates the
bundle before micro-engine reads it. For parallel Maven builds with `-T`, use the script,
which keeps these stages sequential.
When using `-pl`, select both `runtime-catalog` and `micro-engine`; `-am` alone does not
include the producer in a micro-engine build.

## What the flow executes

The flow has two executable test classes. `SnapshotBundleProducerIntegrationTest`
generates the bundle, and `MicroEngineSnapshotRouteExecutionTest` executes the manifest
scenarios and prints their results. The other snapshot classes provide shared execution
support.

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
Each scenario gets a fresh Quarkus CDI component container and Camel context. All
its deployments share this context. The production `CustomXmlRoutesBuilderLoader`
creates the generated beans, preprocesses the source, and loads its routes.

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
failure expectations, then verifies fixture interactions. A sequence of invocations
shares the scenario's state. Async fixtures release their gates together, await all
branches, and then check the shared session counter. This also supports an async branch
that calls another chain with its own async branch.

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
3. Use `sourceElementId` to identify fixture nodes in generated snapshots. Use
   `dependsOn` for additional deployments and `invocations` for stateful sequences.
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
