# Verify component coverage

Run commands from the repository root. Use the JDK configured by the module POMs.
Private Maven dependencies need credentials: pass `-s .github/maven-settings.xml`, which reads them from the `MAVEN_GITHUB_USERNAME` and
`MAVEN_GITHUB_PASSWORD` environment variables. Bundle generation needs Docker for
PostgreSQL; broker scenarios also start containers. Keep dependency and image
versions in their existing manifests.

## Refresh the generated bundle

The consumer reads `runtime-catalog/target/snapshotbundle` by default. It does not
check whether the bundle matches the source exports or generator templates.

1. If integration-build-pipeline code or templates changed, install that module first:

   ```bash
   mvn -q -B -Dstyle.color=never -Dgpg.skip=true \
     -pl integration-build-pipeline -am clean install -DskipTests
   ```

2. If exported chains, chain resources, producer code, or generator inputs changed,
   regenerate the bundle:

   ```bash
   mvn -q -B -Dstyle.color=never -Dgpg.skip=true \
     -pl runtime-catalog -PsnapshotTests clean test
   ```

3. Run the consumer with the selection below. Changes limited to scenario YAML,
   micro-engine test fixtures, or micro-engine code can reuse an existing bundle
   whose generation inputs are unchanged.

Both stages accept `-Dsnapshot.bundle.directory=/absolute/path`. Use the same path
for producer and consumer. Keep it outside `micro-engine/target`, which a consumer
`clean` deletes. A missing bundle requires a producer run even when only the consumer changed.

## Start with the changed scenario

Select exact IDs from the manifest. Its filename without `.yml` is the target ID.
Use one worker while adding or diagnosing a scenario:

```bash
mvn -q -B -Dstyle.color=never -Dgpg.skip=true \
  -pl micro-engine -PsnapshotTests -Dsnapshot.workers=1 \
  -Dsnapshot.target=checkpoint \
  -Dsnapshot.scenario=retries-failed-session-from-checkpoint test
```

Replace both IDs with the case under development. Omit `snapshot.scenario` to run
the whole target. A selected scenario retains its invocation sequence and deployment
dependencies. `snapshotTests` includes both `Short` and `Long` scenarios.

The optional `scripts/run-micro-engine-snapshots.py` launcher accepts `--workers 1`
and Maven options after `--`. It selects the same profile.

Run the short suite when the changed fixture or runner affects multiple targets:

```bash
mvn -q -B -Dstyle.color=never -Dgpg.skip=true \
  -pl micro-engine -PsnapshotTestsShort -Dsnapshot.workers=1 test
```

`snapshotTestsShort` selects the explicit YAML value `scope: Short`; execution time
does not classify scenarios. It includes basic broker scenarios. Target and scenario
filters combine with the scope filter, so a selected `Long` scenario fails selection.
If both profiles are active, the short scope applies.

## Verify the complete generation and execution flow

Use the sequential script when the change needs the full suite:

```bash
SNAPSHOT_WORKERS=1 bash scripts/test-micro-engine-snapshots.sh -q -Dstyle.color=never
```

The script runs system `mvn`, installs integration-build-pipeline, regenerates the
bundle, and runs the consumer in that order. It already supplies batch mode and
`-Dgpg.skip=true`. Additional arguments, including Maven settings, reach every stage.
`SNAPSHOT_BUNDLE_DIRECTORY` changes the bundle path; `SNAPSHOT_WORKERS` changes
consumer concurrency.

Keep producer and consumer stages sequential. `-pl micro-engine -am` does not add
runtime-catalog to the reactor because the bundle is a filesystem dependency.
A combined reactor run with `-T` can race generation against consumption; use the
script for separate stages if Maven parallelism is required.

Run the focused checks needed for the authorized coverage task. Expand to related
targets or the full suite when shared code changes or a failure warrants it.
After those checks pass, repeat them only for new edits or unresolved failures.

## Check evidence before reporting success

Ordinary `test` excludes the `component` tag in both module POMs. A passing ordinary
test run does not verify generated scenarios. Check the requested target and scenario
IDs in the results from this invocation.

- Inspect producer reports under `runtime-catalog/target/surefire-reports`.
- Inspect consumer XML reports under `micro-engine/target/surefire-reports` and
  scenario results under its `snapshot-shards/` directory. `shard-*.json` records
  each worker; `summary.json` combines them. `snapshot.reports.directory` overrides
  the consumer report location.
- Treat a selection error, no tests, missing results, aborted scenarios, or skipped
  scenarios as incomplete verification. An empty worker is valid only when the
  selected scenarios executed on other workers.
- Distinguish dependency resolution, Docker startup, and discovery failures from
  scenario assertion failures. Report the failed stage and the diagnostic evidence.

Report the commands, selected scope and IDs, executed scenario count, outcomes,
bundle regeneration status, and report paths. State which broader suites were not run.
