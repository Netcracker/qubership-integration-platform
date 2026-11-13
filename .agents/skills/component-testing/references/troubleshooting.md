# Diagnose Snapshot Flow failures

Repository paths below are relative to the repository root. Read the failing job
before choosing a test or changing an expectation. `Snapshot Flow Build` is
separate from module builds and end-to-end workflows.

## Identify the failed stage

Read `.github/workflows/snapshot-flow-build.yaml` and
`scripts/test-micro-engine-snapshots.sh`. The script runs the IBP build, catalog
bundle producer, and micro-engine consumer sequentially in one shell step.

| Failed stage or symptom | Sources and evidence to inspect |
| --- | --- |
| `Wait for module builds` | `scripts/wait-for-module-builds.sh` and the referenced module jobs. This is a dependency gate, not a scenario timeout. |
| Compilation, Checkstyle, dependency resolution | The first failing Maven module and its POM. Follow `ci-fix` for dependency credentials and build configuration. |
| Catalog import or snapshot generation | Producer Surefire reports, `runtime-catalog/src/test/java/**/SnapshotBundleProducerIntegrationTest.java`, and the changed exports/resources. |
| Manifest discovery or missing chain/selector | The named YAML, its schema, `CatalogSnapshotExecutionEntryPoint`, and `SnapshotBundleReader`. Check bundle contents and freshness. |
| Scenario assertion, unexpected exception, or timeout | The exact target, scenario, invocation, stage, and error from the consumer report; then the runner and selected provider. |
| Missing combined report | Surefire XML and dump files, `SnapshotScenarioReportExtension`, and `SnapshotShardGenerator`. A terminated JVM or discovery failure can prevent reporting. |

Module build success does not establish that the generated component scenarios
ran. Ordinary module `test` excludes them; use the snapshot profile.

## Read the scenario evidence

Download the workflow's `micro-engine-snapshot-results` artifact:

```bash
snapshot_failure_directory="$(mktemp -d)"
gh run download <run-id> --name micro-engine-snapshot-results --dir "$snapshot_failure_directory"
rg --files "$snapshot_failure_directory" | rg '(summary\.json|TEST-.*\.xml|snapshotindex\.yml)$'
```

The artifact contains the generated bundle and both modules' Surefire reports.
Find them by filename: extracted paths can include checkout and runner-temp
directories. Keep the bundle outside `micro-engine/target` for reproduction.

Start with `Failed snapshot scenarios` in the console and
`snapshot-shards/summary.json` in the consumer reports. Each failed JSON entry
identifies `element`, `scenario`, `invocation`, `repetition`, `stage`,
`expectedBehavior`, and `error`; `outcome` and `duration` remain available.
The wrapped console table has four columns: `Element`, `Test`, `Expected
behavior`, and `Error`. JSON retains the complete messages and cause/suppressed
messages; Surefire XML retains the original stack trace.

Read the matching invocation in
`micro-engine/src/test/resources/testspecifications/<element>.yml`.
`expectedBehavior` explains the intent; the executable expectations and fixture
controls establish what is asserted. A verified `expectedFailure` or
`expectedStartupFailure` is a successful negative case, not a failed test.
An unexpected failure stops later invocations in that scenario.

For older artifacts without diagnostic fields, use the Surefire stack trace and
the last invocation progress message in the job log. If no summary exists,
inspect setup/discovery errors and JVM termination first. A missing failure table
does not establish success: aggregation waits for every shard JSON file.

## Locate the correction

Test support paths in this section are under
`micro-engine/src/test/java/org/qubership/integration/platform/engine/routes/`.

| Failure boundary | Read next and establish before editing |
| --- | --- |
| Returned body, headers, or properties | `tests/SnapshotRouteExecutionTest.java`, `support/SnapshotValueAssertions.java`, the manifest, and the generated route's production processors. Compare the exported configuration with the assertion. |
| Exception type, message, or causes | `support/SnapshotFailureAssertions.java` and the configured failure expectation. Preserve the complete chain and distinguish an execution failure from a fixture assertion failure. |
| Broker/HTTP/scheduler observation | `fixture/SnapshotFixtureRegistry.java`, the selected `*SnapshotFixtureProvider`, its runtime/broker, and `driver/SnapshotScenarioDriverRegistry.java`. Resolve the provider from the manifest rather than guessing from the target name. |
| Timeout | `tests/SnapshotInvocationRunner.java`, `tests/SnapshotRouteExecutionTest.java`, and the provider's timeout diagnostics. The deadline covers setup, all invocation repeats, and verification. Inspect suppressed worker state/stack and fixture diagnostics to find the blocked operation. |
| Container startup or reset | The selected broker/helper and container logs. Record the image from the fixture, architecture, exit code, and container output. RabbitMQ segmentation faults or exit 139 require native process evidence before a route assertion change. |
| Generated behavior or selectors | Exported chains/resources under `integration-build-pipeline/src/test/resources/testConfigurations/`, the element descriptor/template, and the catalog producer. The consumer does not check bundle freshness. |

Distinguish a wrong expectation, a fixture synchronization error, a production
defect, and an infrastructure failure using this evidence. Change an expectation
only when the exported configuration and implementation contract establish the
test's mistake. Keep assertions for rejected work, delivery counts, offsets,
metadata, and subsequent recovery when correcting fixture synchronization.
Use the production-defect escalation rule in the main skill when the correction
requires a product change.

Two fixture races already have useful synchronization patterns:

- **Kafka TLS:** the client can report rejection before the proxy records its
  failed handshake. Read `fixture/KafkaTriggerSnapshotFixtureProvider.java`,
  `KafkaConnectionProxy.java`, and the sender's
  `KafkaContainerSnapshotFixtureProvider.java`. Await the declared proxy
  observation before reading its statistics, retaining the exception, offset,
  and count assertions.
- **Scheduler blue-green:** read `fixture/SchedulerSnapshotFixtureProvider.java`
  and `SchedulerSnapshotRuntime.java`. Apply the real state transition before
  resuming the trigger. Work acquired before standby can enter later; establish
  the acquisition boundary before concluding that standby allowed a new fire.
  Retain the native standby, job-registration, and zero-entry assertions.

Use existing bounded observation helpers for asynchronous effects. A fixed sleep,
blanket retry, weaker count, ignored exception message, or larger timeout does
not establish that the original failure was corrected. Preserve the failing
scenario and its original evidence while selecting the correction.

## Reproduce and verify

Follow [Verification](verification.md) for bundle regeneration and the focused
Maven command. Reuse the CI bundle when its export/generator inputs match the
checkout. Regenerate after changing exports, adjacent resources, templates, or
producer inputs. Keep catalog generation ahead of consumer execution and change
the export or generator rather than editing generated route XML.

Use exact `snapshot.target` and `snapshot.scenario` IDs with `snapshotTests` and
one worker for diagnosis. There is no invocation-level filter: retain the whole
sequence when reproducing a failure/recovery case. A `Long` case selected through
`snapshotTestsShort` is a selection error, not a test failure.

For an intermittent failure, use `snapshot.scenario.repeat` and repeat with the
CI worker count after the focused correction. Read that count from the workflow,
script, and active Maven profile, including overrides. Multiple JVM workers share
one GitHub VM and its Docker resources; increasing the count is not a timeout fix.

Expand to the affected target when the correction changes its shared fixture
behavior. Run Short after shared runner, assertion, reporting, or discovery
changes. Use the original CI concurrency for the final concurrency regression.
Inspect the new reports for the selected IDs, actual invocations, and outcomes
before reporting success. Record the command, bundle provenance, failure before
the fix, result after it, and verification limits.
