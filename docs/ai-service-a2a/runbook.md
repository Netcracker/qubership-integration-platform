# A2A MVP runbook

Roll out and roll back the `create-chain@2` A2A adapter on the shared `qip-dev` topology. One
`ai-service` replica, one shared PostgreSQL server with logical database `ai_a2a`, existing S3/MinIO
for pipeline state, local caller mode.

## Required configuration

| Item | Value |
| --- | --- |
| Feature flag | `QIP_AI_A2A_ENABLED` / `qip.ai.a2a.enabled` (Helm `global.qip.ai.a2aEnabled`) |
| JDBC URL | `QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://<release>-postgres:5432/ai_a2a` |
| Credentials | `QUARKUS_DATASOURCE_USERNAME` / `PASSWORD` from Secret `<release>-postgres-auth` |
| Flyway | `QUARKUS_FLYWAY_MIGRATE_AT_START` unset in Helm (app default `true`) |
| Caller | `tenantId=local`, `subjectId=local-user` |
| Replicas | `1` for `ai-service` |
| Dependencies | knowledge sidecar, catalog, MinIO/S3 as required by `create-chain@2` |

## Deployment order

1. Bootstrap `ai_a2a` (fresh volume or existing-volume one-time create).
2. Verify `SELECT 1 FROM pg_database WHERE datname = 'ai_a2a';` returns a row.
3. Deploy `ai-service` (preflight initContainer waits for the database).
4. Verify Flyway applied `V1__a2a_task_persistence` and `V2__a2a_caller_message_receipts`.
5. Enable A2A only after steps 1–4 pass.

Do not start the new `ai-service` image on an existing PostgreSQL volume before the database-exists
preflight passes. Do not add a recurring Helm Job that creates databases.

## Fresh-volume database initialization

On a new PostgreSQL PVC, chart init SQL creates `ai_a2a` before Flyway runs:

- `infrastructure/init-db/init.sql`
- `infrastructure/qip-dev/charts/postgres/templates/postgres-config.yaml`

Verify after Postgres is Ready:

```bash
kubectl exec -i deploy/<release>-postgres -- \
  psql -U postgres -d postgres -c "SELECT 1 FROM pg_database WHERE datname = 'ai_a2a';"
```

## Existing-volume database preflight

Init scripts do not re-run on an existing PVC. Check first:

```bash
kubectl exec -i deploy/<release>-postgres -- \
  psql -U postgres -d postgres -c "SELECT 1 FROM pg_database WHERE datname = 'ai_a2a';"
```

When absent, create once with admin credentials (short-lived Job or `kubectl exec`):

```bash
kubectl exec -i deploy/<release>-postgres -- \
  psql -U postgres -d postgres -c "CREATE DATABASE ai_a2a;"
kubectl exec -i deploy/<release>-postgres -- \
  psql -U postgres -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE ai_a2a TO postgres;"
```

Re-run the existence query, then deploy `ai-service`. An updated init script alone is not proof for
this path.

Local dry-run (no cluster):

```bash
./infrastructure/qip-dev/tests/assert-a2a-helm-wiring.sh
./infrastructure/qip-dev/tests/verify-a2a-db-bootstrap.sh
```

`verify-a2a-db-bootstrap.sh` exercises both paths on disposable Postgres containers: fresh-volume
init SQL that creates `ai_a2a`, and an existing instance that starts **without** `ai_a2a`, then
runs the same `pg_database` preflight → `CREATE DATABASE` / `GRANT` branch above before the
pre-deploy existence check.

## Startup and readiness

```bash
kubectl rollout status deploy/<release>-ai-service
kubectl exec -i deploy/<release>-ai-service -- curl -sf http://127.0.0.1:8080/q/health
```

Confirm ConfigMap carries `QUARKUS_DATASOURCE_JDBC_URL` and `QIP_AI_A2A_ENABLED`, and the Deployment
mounts username/password from `<release>-postgres-auth`.

## Agent Card and A2A smoke

Set `global.qip.ai.a2aPublicBaseUrl` (or `QIP_AI_A2A_PUBLIC_BASE_URL`) to the externally reachable base URL before
enabling A2A. An empty value falls back to `http://localhost:<port>`, which is wrong for Kubernetes clients. Helm
rejects `a2aEnabled=true` when the public URL is blank.

With `QIP_AI_A2A_ENABLED=true` and header `A2A-Version: 1.0`:

```bash
curl -sf "$BASE_URL/.well-known/agent-card.json" | jq '.skills[].id, .supportedInterfaces[0].url'
# expect only create-chain@2 and the configured public base URL

curl -sf -H 'A2A-Version: 1.0' -H 'Content-Type: application/json' \
  -d '{"message":{"messageId":"smoke-1","role":"ROLE_USER","parts":[{"text":"smoke"}]}}' \
  "$BASE_URL/message:send"
```

## PostgreSQL migration verification

Connect to `ai_a2a` and confirm tables `a2a_tasks`, `a2a_message_receipts`,
`a2a_caller_message_receipts`, and Flyway history rows for `V1` and `V2`. Repeat on an empty database
and on a database that already holds non-terminal Tasks from the same schema version.

## Provided IDS smoke

1. `POST /message:stream` with a provided IDS body.
2. Observe ordered SSE states through `INPUT_REQUIRED`.
3. `GET /tasks/{taskId}` and submit structured `approve` Messages for each pending artifact.
4. Confirm `COMPLETED` and a public `materialization-result` on Get Task.
5. Confirm `taskId` equals pipeline `conversationId`.

## Generated-design smoke

1. Create a Task without an IDS.
2. Approve the requirement brief, send a clarify Message, approve design and plan.
3. Confirm one pipeline run and one chain / materialization result.
4. Confirm cancel returns `TaskNotCancelable` (HTTP 409) with no state change.

## Restart and resubscribe

1. Reach `INPUT_REQUIRED`.
2. Stop and recreate `ai-service`.
3. `GET /tasks/{taskId}`, then `POST /tasks/{taskId}:subscribe`.
4. Continue approvals and complete the Task.
5. Confirm history, public artifacts, Message receipts, and pipeline binding survive.

## Feature-flag rollback and re-enable

1. Set `global.qip.ai.a2aEnabled=false` / `QIP_AI_A2A_ENABLED=false` and roll the Deployment.
2. Expect Agent Card and A2A routes to return `503` with `A2A is disabled`.
3. Confirm `/api/v1/chat` and `/q/health` stay available.
4. Confirm A2A tables and Task rows remain (no down migration, no delete).
5. Re-enable the flag; Get Task must read existing non-terminal rows without schema changes.

## Expected metrics and safe log fields

Log `taskId`, `contextId`, `conversationId`, `messageId`, pipeline run ID, A2A state, pipeline
state, and transition revision. Do not log Message content, artifact bodies, approval payloads,
credentials, or storage coordinates.

Minimum counters: Task create, continue, duplicate Message delivery, state transitions, active
streams, stream failures, persistence failures, protocol errors.

## Known launch limitations

- Single replica only; no multi-replica SSE fan-out.
- No push notifications, active cancel, OIDC, or Quarkus Flow.
- Historical SSE replay is not provided; reconnect uses Get Task then live subscribe.
- Local caller profile only for this horizon.
- Implementation-blocked recovery is covered by focused facade/protocol tests, not this runbook's
  mandatory E2E list.
- Command fingerprints omit `taskId`/`contextId` so SDK-stamped IDs stay compatible with lost-initial
  retries; receipts bind `messageId` to `taskId`.
- Launch E2E gates mock the create-chain facade; they prove REST/SSE/JDBC, not live LLM or S3
  materialization.
- Before enabling A2A for real clients, close enable-path gaps tracked in
  `.superpowers/sdd/a2a-remediation-plan.md` (incremental SSE, reviewable artifact payloads without
  unresolved `app://` refs, production `materialization-result`, resumable receipt dispatch).

## Escalation evidence

Collect without secrets:

- Feature flag value and Helm revision.
- Preflight and Flyway status.
- Task id, conversation id, A2A state, revision (no Message or artifact bodies).
- Recent safe structured logs and counter deltas.
- Whether the PostgreSQL volume was fresh or existing, and the `pg_database` check result.
