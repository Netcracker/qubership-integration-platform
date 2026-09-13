# Executor evaluation harness

This harness replays a saved `cip-design-planner` response through the production compiler
executor. It does not invoke the planner, so repeated generator runs spend tokens only on the
selected owning skills and validation path.

Each case contains three pinned inputs:

- planner response Markdown;
- typed `ChainSemanticRevision` and `RequirementBrief` JSON;
- catalog bindings already resolved for every service-call occurrence.

Run one smoke case after rebuilding `qip-ai-service`:

```bash
ai-service/e2e/executor-eval/run-executor-eval.sh \
  --runs 1 \
  --case mapping \
  --report-dir /tmp/executor-eval-mapping
```

Run all cases once:

```bash
ai-service/e2e/executor-eval/run-executor-eval.sh \
  --runs 1 \
  --report-dir /tmp/executor-eval-all
```

Repeat only a failing case after changing a generator skill:

```bash
ai-service/e2e/executor-eval/run-executor-eval.sh \
  --runs 5 \
  --case retry-error-handling \
  --report-dir /tmp/executor-eval-retry-fix
```

The report retains every request and response. `summary.json` fails unless the endpoint reports
`plannerInvoked=false`, all expected owners ran, required graph element types exist, and every
compiler validation pass is valid. Cases may also require exact catalog identities on named
service-call nodes and exact mapping intent ids on generated transform nodes.

Validate fixtures and runner syntax without starting the service or spending model tokens:

```bash
ai-service/e2e/executor-eval/test-offline.sh
mvn -pl ai-service \
  -Dtest=BindingMappingContractInvestigationTest,ExecutorEvalFixtureTest test
```

`BindingMappingContractInvestigationTest` contains deterministic negative cases for missing,
duplicate, extra, and misdirected bindings, plus orphan, duplicate, misplaced, and inconsistent
mapping intents. Tests marked `KNOWN DEFECT` characterize current fail-open behavior so it can be
reproduced before a fix and changed into a regression assertion with the fix.

Run the bounded local pre-release sample after rebuilding and starting `qip-ai-service`:

```bash
ai-service/e2e/executor-eval/run-pre-release-sample.sh
```

Set `BASE_URL` to test another local or reachable environment. Set `REPORT_DIR` to retain the
report outside `/tmp`. The command runs `repeated-operation-mappings` once and accepts results
only from `claude-sonnet-5` or `gpt-5.6-luna`.
