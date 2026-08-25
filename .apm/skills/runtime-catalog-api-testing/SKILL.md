---
name: runtime-catalog-api-testing
description: Test and verify runtime-catalog changes through its HTTP API against the local Docker stack. Use when testing, verifying, or reproducing runtime-catalog behavior via API — design change-driven scenarios, gate on container logs, exercise repeat/concurrent/edge paths, check PostgreSQL and Consul side effects, and fold findings into regression tests.
---

# Testing runtime-catalog through its API

Verify a runtime-catalog change end to end against the running local stack. This catches the class
of bug that unit tests with mocked repositories cannot: Hibernate cascade and session identity,
transaction boundaries, Flyway correctness, Consul publishing, and request validation.

Everything below runs with `curl`, `psql`, and `docker logs` — no harness required.

## Start here

The stack must be up and the service healthy:

```bash
docker compose -f infrastructure/docker-compose.yml up -d
docker ps --filter name=qip-runtime-catalog --format '{{.Names}}\t{{.Status}}'
curl -s http://localhost:8091/actuator/health          # {"status":"UP",...}
```

Testing a code change means rebuilding first — the image copies a prebuilt jar, so the host build
comes first:

```bash
mvn -pl runtime-catalog package -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true
docker compose -f infrastructure/docker-compose.yml up -d --build qip-runtime-catalog
```

- The compose service is `qip-runtime-catalog`, not `runtime-catalog`.
- The `Dockerfile` `COPY` globs `target/qip-runtime-catalog-*-exec.jar`; delete stale-version jars
  first, or the glob matches several files and the build fails.
- When you delete or rename a migration, build with `clean`. A plain `package` leaves the old file
  in `target/classes`, and it ships in the jar.
- `-Dmaven.javadoc.skip=true` avoids unrelated Javadoc errors blocking the package build.

## Method

1. **Read the diff. Name what can break.** Map the change to a subsystem (import/export, chains
   CRUD, deployment, folders, variables) and ask which observable behavior it moves.
2. **Design targeted scenarios** that would expose that behavior through the API.
3. **Exercise four directions, not just the happy path:**
   - happy path;
   - **repeat** — do it twice, and into an already-existing entity;
   - **concurrent** — fire the same operation from several threads;
   - **edge inputs** — blank, forbidden characters, duplicates, Unicode, multi-entity payloads.
4. **Verify beyond the HTTP status** — assert side effects in PostgreSQL and Consul, and gate on
   the container log.
5. **Fold findings into regression tests** — turn a reproduced bug into a unit/migration test, or a
   `@SpringBootTest` + Testcontainers integration test. runtime-catalog has no DB integration-test
   harness yet, so an API repro is currently the only automated guard for persistence bugs.

Repeat and concurrent paths catch what the happy path misses. A re-import into an existing folder
tree surfaced a `NonUniqueObjectException`; parallel imports into one new path surfaced duplicate
folders. Both returned HTTP 200.

## The log gate: HTTP 200 is not success

An accepted request can still log an exception, especially on the async import worker. Snapshot the
log before a scenario and diff after it, failing on any new `ERROR`:

```bash
docker logs qip-runtime-catalog --since 5m 2>&1 | grep -E '^\[[^]]+\] \[ERROR\]'
```

**Match the level field, not the word.** Every line carries `error_code=` and `originating_bi_id=`
fields, so a naive `grep -i 'error|exception'` matches almost everything: over one recent day it
returned 6229 lines against 242 real `ERROR` entries. The level is the second bracket group, padded
to five characters (`[INFO ]`, `[WARN ]`, `[ERROR]`).

Two more calibration points:

- **Dedup error lines across scenarios.** The import worker can log an error a second or two after
  the request returns, landing it in the next scenario's window. Dedup by the timestamped line so
  one failure is not blamed on the following scenario.
- **Mark deliberate error paths** (malformed payload, a contention loser) so their expected error
  is reported as a note rather than a failure.

## API recipes the agent cannot guess

Build request bodies from the controller and DTO under test rather than from memory, so a renamed
field fails loudly instead of silently. The non-obvious contracts:

| Operation | Contract |
|---|---|
| Create chain | `POST /v1/chains` with `{"name", "labels": []}`. Omitting `labels` has caused a `getLabels()` NPE — keep it explicit. |
| Delete chain | `DELETE /v1/chains/{id}` returns 200. Deleting a folder cascades to its children. |
| Create folder | `POST /v1/folders` with `{"name", "parentId"}`. |
| Export chain | `GET /v1/catalog/export/chain/{id}` returns a zip; unzip and assert on the `*.chain.qip.yaml` inside. |
| Import | `POST /v3/import` multipart, field name `file`, returns **202** + `importId`. The status endpoint returns **404 until the session registers**; poll `GET /v3/import/{importId}` until `done`/`completion == 100`. A 200 status body can still report per-chain failures — check `result.chains[].status` for `ERROR`. |
| Count chains | `GET /v1/chains/count` returns `{"chainsCount": N}`, not a bare number. |
| List folders | `GET /v1/folders` returns **root** folders only. Assert on nested folders through SQL. |
| Copy / duplicate / move chain | `POST /v1/chains/{id}/copy`, `/duplicate`, `/move`; target via `?targetFolderId=`. Each returns a new (or moved) chain. |
| Snapshot (compile) | `POST /v1/catalog/chains/{id}/snapshots` (no body) compiles the chain to Camel XML and returns the snapshot. The build response omits the XML; fetch it via `GET /v2/catalog/snapshots/{snapshotId}/full` → `xmlDefinition` (empty for an empty chain). |
| Deploy | `POST /v1/catalog/chains/{id}/deployments` with `{"domain": "default", "snapshotId"}`; list via `GET /v1/catalog/chains/{id}/deployments`. `default` is a valid domain in the local dev stack. |
| Common variables | `POST /v1/common-variables` with a `{name: value}` map; `GET` lists; delete via `DELETE /v1/common-variables?variablesNames=a,b` (query param, **not** a body). |

A worked scenario — create, compile, clean up:

```bash
RC=http://localhost:8091
PFX="T$(uuidgen | cut -c1-8)"                       # unique run prefix
ID=$(curl -s -X POST $RC/v1/chains -H 'Content-Type: application/json' \
      -d "{\"name\":\"$PFX-chain\",\"labels\":[]}" | jq -r .id)
curl -s -X POST $RC/v1/catalog/chains/$ID/snapshots | jq '{id, name}'
curl -s -X DELETE $RC/v1/chains/$ID -o /dev/null -w '%{http_code}\n'
```

Seed every entity with that unique run prefix so assertions and cleanup are unambiguous and
parallel runs do not collide. Delete what you created — later runs and migrations should start from
a known state.

## Verify side effects

**PostgreSQL** — query the `catalog` schema for the rows the change should produce. Use this for
nested folders, snapshots and deployments, and duplicate detection the API will not show you:

```bash
psql -h localhost -p 5432 -U postgres -d postgres \
  -c "SELECT id, name, parent_id FROM catalog.folders WHERE name LIKE '$PFX%'"
```

**Consul** — depending on the deployment mechanism, compiled config may be published to Consul KV.
The local dev stack does **not** populate it: the KV store stays empty even after a deploy. Verify a
deployment through `GET /v1/catalog/chains/{id}/deployments` and the `catalog.deployments` /
`catalog.snapshots` rows instead, and check Consul only on a stack that actually publishes there:

```bash
curl -s 'http://localhost:8500/v1/kv/?recurse&keys' | jq
```

## Scripted scenarios

For a scenario longer than a few calls, write a throwaway Python script that wraps the recipes
above: one function per API call, one snapshot of the log per scenario, cleanup by prefix at the
end. Keep it outside the repository unless the team agrees to maintain it — an unversioned harness
that no CI job runs goes stale within a release.

## Maintaining this skill

This skill is APM-managed. Edit the source under `.apm/skills/runtime-catalog-api-testing/`; its
trigger lives in `.apm/instructions/runtime-catalog.instructions.md`. Run `apm install` to refresh
the mirrors under `.claude/` and `.agents/`, then `apm compile` for the `AGENTS.md` files. Do not
hand-edit the mirrored copies.
