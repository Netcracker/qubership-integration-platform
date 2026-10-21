# Scenario design

Repository paths below are relative to the repository root. Read the existing
manifest and matching section of `micro-engine/TESTING.md` before extending them.

## Locate the inputs

| Artifact | Location |
| --- | --- |
| Exported chains and adjacent resources | `integration-build-pipeline/src/test/resources/testConfigurations/` |
| Shared bundle models and writer | `integration-build-pipeline/src/test-support/java/org/qubership/integration/platform/snapshotbundle/` |
| Catalog import wrapper | `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/snapshotbundle/` |
| Scenario manifests | `micro-engine/src/test/resources/testspecifications/` |
| Manifest schema | `micro-engine/src/test/resources/testspecifications.schema.json` |
| Supporting files | `micro-engine/src/test/resources/snapshot-fixtures/` |
| Consumer implementation | `micro-engine/src/test/java/org/qubership/integration/platform/engine/routes/` |

Keep one `.yml` manifest per element family, including its failure cases. Discovery
walks subdirectories but reads `.yml` files. Retain the YAML language-server
schema comment, with a relative path appropriate to the manifest's directory.

Reuse a chain when the case changes only runtime input or an existing fixture's
response. When element settings or topology must change, add an exported chain:

```text
testConfigurations/<folder>/<name>/<chainId>/<chainId>.chain.qip.yaml
testConfigurations/<folder>/<name>/<chainId>/resources/
```

Take `folder` and `name` from the element's `description.yaml` or
`description.yml`. Give chains distinct IDs and names across the bundle. Keep
supporting subchains beside the related chains and shared imported services under
`testConfigurations/services/`.

The producer imports these files and generates route XML. Edit the exported
configuration or generator source when authorized; do not hand-edit bundle XML
under `target` as a test input.

## Select routes and deployments

The manifest filename without `.yml` is its target ID. Without explicit
deployments, it also supplies the chain name. With explicit `deployments`, set
the manifest's `subject` and use each deployment's `route` to name an exported
chain. A `route` is a chain name, not a file path or chain ID.

Use `sourceElementId` from the exported chain for fixture and driver selectors.
The bundle maps it to the generated snapshot element ID. Generated IDs change
when the bundle is rebuilt.

Set a scenario's `deployment` when the manifest groups independent chains. The
runner loads that deployment, its transitive `dependsOn` dependencies, and their
fixtures. Omitting `deployment` loads all declared deployments. Declare
interactions for the fixtures that are actually selected.

## Choose scenario boundaries

Each scenario gets a fresh CDI container and Camel context. Its invocations share
the loaded deployments, fixture state, and clients. An unexpected invocation
failure stops the remaining sequence.

Every scenario declares `scope: Short` or `scope: Long`. Use `Short` for basic
element behavior or a case that normally takes at most one second. Basic broker
checks can be `Short` despite container startup. Use `Long` for the remaining
cases; timing does not change the declared scope.

Each scenario also needs exactly one `endpointUri` or `driver`, including when
using explicit `invocations`. An invocation may override that route selection.
Keep bodies, headers, properties, expectations, and interactions inside each
invocation when using `invocations`; they are not scenario-level defaults.

Put `expectedFailure` inside an invocation. The shorthand scenario object does
not accept it. For failure and recovery, give the invocations separate IDs and
assert the outcome of both calls through the same runtime state.

Every selected fixture needs an interaction in every invocation. Use explicit
zero-count expectations for a fixture that is loaded but unused by that call.
For sender providers, check whether `expectedSendCount: 0` is also required:
zero delivered records and zero attempted sends are different expectations.
The invocation's `repeat` reuses its state; interaction counts cover its repeats.

## Adapt a maintained example

Use examples from `micro-engine/src/test/resources/testspecifications/` rather
than copying a generic Java test or inventing a second manifest format:

| Concern | Example |
| --- | --- |
| Route selection and header absence | `header-modification.yml` |
| HTTP request assertions and controlled failure responses | `service-call.yml` |
| Multiple deployments and nested async completion | `nested-async-chain-call.yml` |
| Stateful failure and subsequent retry | `checkpoint.yml` |
| Shared broker with explicit invocation expectations | `rabbitmq-sender.yml` |
| Structural output and generated values | `mapper.yml`, `xslt.yml` |

The schema supports editor diagnostics. Java validates provider capabilities,
references, and assertion semantics beyond that schema. For an unfamiliar field,
read `entrypoint/execution/SnapshotExecutionScenario.java`,
`SnapshotScenarioInvocation.java`, and the selected provider under the consumer
implementation directory. Run the scenario to validate the complete contract.
