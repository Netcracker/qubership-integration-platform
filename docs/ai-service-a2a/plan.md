# AI service A2A delivery plan

## Outcome

Expose the existing `create-chain@2` product pipeline as an A2A 1.0.1 agent within three days without replacing
`ProductPipelineRuntime`. Preserve the existing browser chat contract. Add a second REST/SSE adapter that uses the same
application layer and durable pipeline state.

The launch is successful when an external A2A client can discover the agent, create and continue a Task, stream ordered
status and artifact updates, survive an `ai-service` restart, complete chain materialization, and retrieve the final
Task snapshot.

## Delivery horizons

### Launch horizon: three-day A2A MVP

- Keep `ProductPipelineRuntime` as the lifecycle authority.
- Publish only the `create-chain@2` skill.
- Support REST request/response and SSE streaming.
- Run one active `ai-service` replica.
- Persist A2A Tasks and message receipts in PostgreSQL.
- Reuse the qip-dev PostgreSQL server with a separate `ai_a2a` logical database.
- Keep pipeline runs and compilation artifacts in their existing S3/MinIO stores.
- Preserve the browser REST/SSE API.
- Defer authentication enforcement behind explicit application ports.
- Return `TaskNotCancelable` instead of claiming that pipeline cancellation is safe.

### Evolution horizon: post-launch Quarkus Flow migration

- Introduce Quarkus Flow only after the A2A facade and its contract tests are stable.
- Move one product-pipeline stage at a time behind the existing application interface.
- Keep A2A and browser adapters unchanged while the internal orchestrator changes.
- Add multi-replica event distribution, replay guarantees, authentication, and cooperative cancellation as separate
  changes.
- Remove `ProductPipelineRuntime` only after Flow owns every transition and restart test.

## Confirmed decisions

| Topic | Decision |
| --- | --- |
| A2A scope | A2A specification 1.0.1; Agent Card wire `protocolVersion: "1.0"`; REST and SSE |
| Published skill | `create-chain@2` only |
| Orchestration | Reuse `CreateProductPipelineCoordinator` and `ProductPipelineRuntime` |
| Browser chat | Preserve its existing API and SSE event format |
| Task identity | One chain build per Task; `taskId` is the pipeline `conversationId` |
| Context identity | `contextId` groups Tasks but never owns pipeline state |
| Idempotency | Deduplicate every Message by caller identity and `messageId`; bind the receipt to the resulting Task |
| Deployment | One active replica for the MVP |
| Task persistence | PostgreSQL; additive migrations only |
| Database ownership | Prompt 02 owns application wiring; prompt 07 owns Helm and qip-dev wiring |
| Database topology | Reuse the shared PostgreSQL instance with a separate `ai_a2a` database |
| Database bootstrap | Init script for fresh volumes; one-time admin bootstrap for existing volumes |
| Pipeline persistence | Existing S3/MinIO stores remain authoritative |
| Streaming recovery | Reconnect with `GetTask` and `SubscribeToTask`; no historical SSE replay guarantee |
| Approval UX | User input is free-form; the top-level A2A client sends a normalized approval command |
| Approval safety | Validate the expected artifact type, hash, revision, and current waiting stage |
| Implementation gate | Continue automatically when the approved plan hash is available |
| Blocked implementation | Continue with normalized approval or evidence clarification; no public `implement` action |
| Authentication | Local permit-all policy with security ports and persistence fields prepared for OIDC |
| Cancellation | Return `TaskNotCancelable` in the launch horizon |
| Rollback | Disable A2A with a feature flag; do not run a destructive down migration |
| SDK fallback | Time-box the official Java SDK compatibility check to four hours |

## Terminology

- **A2A Task:** The public unit of work owned by the A2A protocol. One Task produces one chain.
- **Pipeline run:** The durable internal execution managed by `ProductPipelineRuntime`.
- **Browser chat:** The existing `/api/v1/chat` and UI-compatible REST/SSE surface.
- **A2A adapter:** The new protocol adapter that maps A2A Messages and Tasks to application commands and events.
- **Top-level agent:** The A2A client that interprets user intent and sends normalized commands to `ai-service`.
- **Interrupted state:** `INPUT_REQUIRED`, where the stream closes and the Task can accept a later Message.
- **Terminal state:** `COMPLETED` or `FAILED`, where the Task rejects further Messages.

## Target architecture

```text
Browser REST/SSE ──> Browser adapter ─┐
                                     │
                                     v
                              Create-chain application facade
                                     │
A2A REST/SSE ─────> A2A adapter ─────┤
                                     │
                       ┌─────────────┴─────────────┐
                       v                           v
             ProductPipelineRuntime       A2A Task repository
                       │                           │
                       v                           v
                S3/MinIO evidence              PostgreSQL
```

The application facade owns commands and internal events. It must not expose A2A SDK types or browser `ChatEvent`
types. Both transport adapters translate at the boundary.

## Public A2A behavior

### Operations

- Agent Card discovery.
- Send Message to create a Task.
- Send Message with `taskId` to continue a non-terminal Task.
- Send Streaming Message to create or continue a Task over SSE.
- Get Task to read its latest durable snapshot.
- Subscribe to Task to receive live updates for an existing non-terminal Task.
- Cancel Task returns `TaskNotCancelable`.

The Agent Card declares `streaming: true`, `pushNotifications: false`, and one skill named `create-chain@2`.

### Message idempotency

The server scopes every client-generated `messageId` by trusted `tenantId` and `subjectId`. A durable receipt maps that
key to the resulting `taskId`, command fingerprint, processing state, and response reference. The initial `WORKING` Task
snapshot and receipt claim commit in one database transaction before pipeline dispatch.

An initial Message has no `taskId`. If its response is lost, the client resends the same Message with the same
`messageId`; the server returns the Task recorded by the receipt instead of creating another Task. A continuation also
uses the caller-scoped receipt key and verifies that its recorded Task matches the supplied `taskId`.

If a process stops after the receipt claim but before completion, the next delivery resumes the recorded `taskId`. It
reconciles the durable pipeline binding before dispatch. The facade's start operation remains idempotent by `taskId`, so
recovery cannot create a second pipeline run.

Reusing a caller-scoped `messageId` with a different canonical command fingerprint is an idempotency conflict. The
server rejects it with a typed protocol error and never returns the receipt for the unrelated command.

The server fingerprint uses a versioned canonical command descriptor after typed Message parsing. It includes operation
kind, role, ordered typed parts, and normalized structured action fields. It excludes `messageId`, `taskId`,
`contextId`, server-generated identifiers, JSON field order, transport whitespace, and HTTP headers.

`taskId` and `contextId` are omitted on purpose: the A2A SDK stamps generated correlation IDs onto the Message before
the agent executor fingerprints it, while a lost-initial retry omits those fields. Receipt row keys already bind the
caller-scoped `messageId` to `taskId` and scope continuation receipts by `(taskId, messageId)`.

The transport adapter owns this canonicalization; persistence stores the resulting fingerprint as an opaque value.

The local profile uses the trusted fixed caller `tenantId=local`, `subjectId=local-user`. A2A Message metadata cannot
override either value.

### State mapping

| Pipeline state | A2A state |
| --- | --- |
| `RUNNING` | `WORKING` |
| `WAITING_FOR_INPUT` | `INPUT_REQUIRED` |
| `WAITING_FOR_APPROVAL` | `INPUT_REQUIRED` |
| `WAITING_FOR_IMPLEMENT` | `WORKING` while the facade automatically submits `ImplementCommand` |
| `CHAIN_MATERIALIZED` | `COMPLETED` |
| `FAILED` | `FAILED` |

`PLAN_APPROVED` is not a final A2A result for `create-chain@2`. When the approved plan hash is available, the
application facade submits `ImplementCommand` exactly once. The client observes `WORKING` through materialization and
does not send a second implementation command.

If the approved plan hash is absent or the facade cannot construct a safe `ImplementCommand`, the facade emits a typed
implementation-blocked result. The A2A adapter maps that exceptional result to `INPUT_REQUIRED` with structured recovery
data. The pending action contains the reason and the expected `implementation-plan` type, hash, and revision when known.

The client continues a blocked Task with the same normalized `approve` action when the expected plan evidence is known.
The facade validates that evidence and constructs the internal `ImplementCommand`; it does not approve the runtime stage
a second time. When required evidence is missing, the pending action requests clarification. The facade must restore or
produce that evidence through a valid input-capable pipeline transition before accepting clarification. It must not send
an input command directly to `WAITING_FOR_IMPLEMENT`. If no legal recovery transition exists, the facade returns a typed
non-recoverable failure. The public MVP contract has no separate `implement` action.

### Streaming rules

1. Emit the current Task snapshot first.
2. Persist each status or artifact change before publishing it.
3. Preserve event order for every Task.
4. Allow more than one live subscriber in the single-replica deployment.
5. Close the stream at `INPUT_REQUIRED`, `COMPLETED`, or `FAILED`.
6. Do not promise replay of transient progress events after disconnection.
7. Make the latest durable state available through `GetTask` before accepting a new subscription.

### Public artifacts

- `requirement-brief`
- `requirement-draft`
- `integration-design`
- `implementation-plan`
- `validation-report`
- `materialization-result`
- `failure-report`

Each artifact has a stable ID, type, revision, hash, and structured payload. Large files use a safe application-level
reference. Public artifacts must not expose prompts, model traces, credentials, raw logs, storage coordinates, retry
internals, or pipeline snapshots.

### Approval command

The Task status advertises the pending action as structured data, including the artifact type, hash, revision, and
allowed actions. The top-level agent converts any user-facing approval interaction into a normalized command.

```json
{
  "action": "approve",
  "artifactType": "integration-design",
  "artifactHash": "<sha256>",
  "revision": 3
}
```

Free-form user input remains valid for requirements and clarifications. A free-form `approve` Message does not bypass
the exact approval check at the A2A boundary.

An implementation-blocked status uses one of these recovery actions:

- `approve`, with the expected `implementation-plan` type, hash, and revision;
- `clarify`, with a reason and a list of missing evidence.

Both actions continue the same Task. The pending-action discriminator determines which payload the client sends.

## Work graph

```text
01 SDK compatibility ──────────────┐
                                   ├─> 04 A2A transport ─> 05 Streaming ─┐
02 Task persistence ───────────────┼───────────────────────────────┐      │
                                   ├─> 06 Approval and artifacts ─────────┤
03 Pipeline application facade ────┤                                      ├─> 08 E2E launch gates
                                   └─> 07 Browser, database, and rollout <─┘
```

| ID | Deliverable | Blocking inputs | Parallel work |
| --- | --- | --- | --- |
| 01 | SDK compatibility decision and boot proof | Baseline commit | None |
| 02 | Durable Task and message-receipt persistence | 01 store decision | 03 |
| 03 | Transport-neutral create-chain facade | Baseline commit | 02 |
| 04 | Agent Card, Send Message, and Get Task | 01, 02, 03 | None |
| 05 | Send Streaming Message and Subscribe to Task | 02, 03, 04 | 06 |
| 06 | Exact approvals and public artifact projection | 02, 03, 04 | 05 |
| 07 | Browser regression, database wiring, security seam, and feature flag | 02, 03, 04 | 05, 06 |
| 08 | E2E launch gates, deployment checks, and rollback runbook | 05, 06, 07 | None |

## Delivery risks

### SDK fallback expands transport work

Prompt 01 has a four-hour limit. If the official SDK does not work with Quarkus `3.32.3`, prompts 04 and 05 must build
the specification binding. Freeze the fallback decision before prompts 02 and 04 select SDK-owned interfaces.

### Security ports cross prompt ownership

Prompt 04 owns the initial `CallerContext`, `CallerContextProvider`, `TaskOperation`, `TaskIdentity`, and
`TaskAccessPolicy` contracts. Prompt 07 supplies rollout configuration and hardens the local implementations without
changing those contracts. Any required signature change blocks prompt 07 until prompt 04 is amended and its consumers
are green.

### Implementation gate has one public behavior

The browser already continues automatically after plan approval when the approved plan hash is available. The A2A
facade uses the same behavior. `INPUT_REQUIRED` is reserved for the typed recovery path when automatic implementation
cannot start.

### Database wiring crosses application and deployment ownership

Prompt 02 owns JDBC, Flyway, datasource configuration names, migrations, and PostgreSQL test infrastructure. Prompt 07
owns `infrastructure/qip-dev` database creation, ConfigMap and Secret references, deployment environment variables, and
the A2A feature flag. Prompt 07 must consume the property names from prompt 02 without renaming them. Prompt 08 verifies
the assembled topology and does not introduce missing wiring.

### Existing PostgreSQL volumes skip updated init scripts

The PostgreSQL image runs `/docker-entrypoint-initdb.d` only when it initializes an empty data directory. Prompt 07 adds
`ai_a2a` to the init script for fresh qip-dev volumes. Prompt 08 documents and executes a one-time manual command or
short-lived admin Job for an existing volume. Deployment must verify that `ai_a2a` exists before starting the new
`ai-service` version. The MVP does not add a recurring database-creation Job to the Helm release.

## Three-day schedule

### Day 1: compatibility and deep seams

- Complete 01 in the first four hours.
- Run 02 and 03 in parallel after the SDK store decision is known.
- Merge in order: 01, 02, 03.
- Start 04 only after all three focused suites are green.

Exit gate: the service boots with the chosen A2A approach, Task persistence survives a repository restart, and the
application facade drives `create-chain@2` without using browser DTOs.

### Day 2: protocol behavior

- Complete 04.
- Run 05 and 06 in parallel.
- Run 07 after prompts 02, 03, and 04 stabilize their database, application, and transport contracts.
- Merge in order: 04, 05, 06, 07.

Exit gate: Agent Card, create, continue, poll, stream, subscribe, exact approval, artifacts, and browser smoke tests are
green.

### Day 3: launch proof

- Complete 08 against the assembled branch.
- Run the four mandatory A2A E2E scenarios plus the browser regression scenario.
- Run the browser regression gate and existing product-pipeline suites.
- Verify the feature flag rollback and restart path in the target deployment environment.
- For an existing PostgreSQL volume, create `ai_a2a` once and pass the database-exists preflight before deployment.
- Freeze scope after the first complete green launch run.

Exit gate: all launch checks pass twice from a clean service start, and the rollback procedure is documented and
executed once.

## TDD policy

Every prompt implements one behavior slice at a time:

1. Add one failing test that describes externally observable behavior.
2. Run the narrowest command and record the expected failure.
3. Implement the smallest production change that makes the test pass.
4. Refactor only while the focused test remains green.
5. Run the prompt's integration tests and the shared regression suites.
6. Commit only files owned by that prompt.

Tests must not pass before their corresponding production behavior exists. Do not substitute mocks for the persistence,
restart, HTTP, or SSE boundaries that the test claims to verify.

## Shared regression command

```bash
mvn -pl ai-service \
  -Dqip.schemas.sync.skip=true \
  -Dqip.ai.qipknowledge.build.skip=true \
  -DskipITs=false \
  -Dtest=CreateChainSharedDesignRuntimeIT,ProductPipelineRuntimeTest,ProductPipelineApprovalTest,\
CreateChainProductPipelineRestartIT,KnowledgePackagePinRestartIT,MaterializationCapabilityTest,\
ProductPipelineImplementationGateTest,ProductPipelineValidationRollbackTest,ProductChainMaterializerTest,\
ProductPipelineProfileCatalogCutoverTest test
```

Agents may split the command while developing, but the assembled branch must run the complete set.

## Mandatory launch scenarios

### Provided IDS

Create a Task with a provided IDS, complete all required approvals, materialize the chain, receive `COMPLETED`, and
retrieve `materialization-result` through both the stream and `GetTask`.

### Generated design

Create a Task without an IDS, reach `INPUT_REQUIRED`, send a clarification and normalized approval to the same Task,
and complete materialization without creating another pipeline run.

### Restart and resubscribe

Stop the service while a Task is `INPUT_REQUIRED`, restart it, retrieve the same Task, subscribe again, continue it,
and complete it. The latest Task state and public artifacts must survive the restart.

### Initial response recovery

Drop the connection after Task creation commits but before the client receives the response. Resend the same initial
Message with the same trusted caller and `messageId`. The server must return the original Task without creating a second
pipeline run.

### Browser regression

Use the existing browser endpoint to start `create-chain@2`, receive the original SSE format, and pass one approval.
No A2A DTO or Task-store requirement may leak into the browser contract.

## Launch observability

Log structured fields for `taskId`, `contextId`, `conversationId`, `messageId`, pipeline run ID, A2A state, pipeline
state, and transition revision. Do not log Message content, artifact bodies, approval payloads, credentials, or storage
references.

Minimum counters cover Task creation, continuation, idempotent duplicate delivery, state transitions, active streams,
stream failures, persistence failures, and protocol errors. Metrics must not add a new blocking dependency to the MVP.

## Rollback

- Disable the A2A adapter with one configuration property.
- Keep the browser endpoints enabled.
- Leave additive database objects and Task data intact.
- Confirm A2A discovery and invocation are unavailable while browser smoke tests remain green.
- Re-enable the feature and verify that a previously persisted non-terminal Task can be read.

## Scope controls

Do not include these changes in the launch horizon:

- a Quarkus upgrade solely to accommodate the A2A SDK;
- Quarkus Flow orchestration;
- gRPC or JSON-RPC bindings when REST satisfies the target environment;
- push notifications;
- multi-replica stream distribution;
- historical SSE replay;
- active pipeline cancellation;
- production OIDC enforcement;
- a dedicated PostgreSQL server for A2A;
- new AI capabilities beyond `create-chain@2`;
- browser UI redesign.

## Prompt index

Execute the files under `prompts/` in dependency order. Each prompt is a fresh agent context and must end with a focused
commit and a short handoff containing test evidence.

- [01 SDK compatibility](prompts/01-sdk-compatibility.md)
- [02 Task persistence](prompts/02-task-persistence.md)
- [03 Pipeline facade](prompts/03-pipeline-facade.md)
- [04 A2A transport](prompts/04-a2a-transport.md)
- [05 Streaming](prompts/05-streaming.md)
- [06 Approval and artifacts](prompts/06-approval-and-artifacts.md)
- [07 Browser, database, security, and rollout](prompts/07-browser-security-and-rollout.md)
- [08 E2E launch gates](prompts/08-e2e-launch-gates.md)
