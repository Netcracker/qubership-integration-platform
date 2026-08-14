# Testing Service: Go module migration (plan 1 of 3)

## Overview

Bring an externally developed Go testing service into the monorepo as a new `testing-service/` module, stripped of every
vendor-internal dependency and identifier. The module must work two ways: as a standalone open-source binary shipped
with the platform stack, and as a library that a downstream vendor imports and wraps with its own infrastructure.

The service manages test cases (a reference to a chain element plus request settings and response matchers), endpoint
mocks (bound to a chain element, with request matchers and response settings), and test runs (a queue plus a background
executor). It ships a matching engine of nine predicates and zip-based import/export.

Two follow-up plans cover the rest of the migration and are out of scope here:

- plan 2 — `TestingService` implementation in `engine` and `micro-engine` (endpoint mock interception),
- plan 3 — UI (test cases, mocks and runs screens in `ui/`).

## Context (from discovery)

- **Source**: an external source snapshot, not a git repository. 93 Go files, 7428 lines. `go.mod` declares `go 1.21`
  with `toolchain go1.22.2`; its Dockerfile pins a 1.22.1 toolchain with `GOTOOLCHAIN=local`. Fiber v2, bun ORM over
  PostgreSQL, koanf, swaggo.
- **Vendor dependencies**: 9 modules (7 direct, 2 indirect), 13 distinct import paths, 32 import lines across 19 files.
- **Target repository**: `github.com/Netcracker/qubership-integration-platform`, currently Java and TypeScript only.
  This is the first Go module, so no build, lint or release tooling exists for it.
- **Integration points already present**: `GET /v1/chains/{chainId}/elements/{elementId}` in runtime-catalog returns
  exactly the DTO the catalog client expects (it ignores `chainId` and looks up by element id); the engine exposes chain
  HTTP triggers under `/routes` (`CAMEL_ROUTES_PREFIX`).
- **Platform conventions**: migrations numbered from 100 (`V100_000__init.sql` in runtime-catalog); services run
  non-root as uid 10001 on port 8080 inside the container; `AuditorProvider` in runtime-catalog returns a hardcoded
  `User("0", "developer")` as the open-source default and is overridden downstream.
- **Two routing tables exist**: `infrastructure/nginx/routes.conf` for compose and
  `infrastructure/qip-dev/charts/ui/templates/config.yaml` for Kubernetes. Both need the new service.

## Development Approach

- **testing approach**: Regular — implementation first, tests immediately after within the same task
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- maintain backward compatibility

### Sanitization gate (applies from the first commit)

The source is proprietary and the target repository is public and Apache-2.0. Vendor-internal identifiers must never
reach the repository, **including its commit history**, so every file is ported already cleaned.

Four categories must go: the vendor's internal git host, its internal artifact repository host, the source Go module
name, and the former product name together with its three-letter abbreviation. The literal strings are **deliberately
not written down in this repository** — spelling them out in a plan or a lint config would put them in the history the
gate exists to protect. They live in a file outside the repository.

Two carve-outs:

- **the GitHub organization name is not forbidden.** The repository lives at `github.com/Netcracker/…`, so the Go module
  path must contain it, and it already appears throughout the repository. A blanket ban was never implementable.
- **`external-session-cip-id`** stays: it is already part of the public platform code, and renaming it breaks engine
  compatibility.

**Enforcement is a pre-commit hook, installed in Task 1, not a check at the end.** The textual matches live mostly in
non-Go files — the Dockerfile's base images and proxy, `application.yaml` keys and default service addresses, the
swagger title, the route-registration path, one error message — none of which a dependency check would ever see. A hook
that reads the outside-the-repo list and refuses the commit is the only thing that keeps the history clean; discovering
a hit at the end of the plan means rewriting a branch that may already be pushed.

CI adds a second, coarser net: every module path in `go.mod` and `go.sum` must match a known-good public prefix. That
catches a vendor module sneaking back in without naming anything. Regenerate `go.sum` from scratch — the original has 18
lines pointing at the vendor's host.

## Testing Strategy

- **unit tests**: required for every task
- **integration tests**: `testcontainers-go` against a real PostgreSQL — the queue relies on `FOR UPDATE SKIP LOCKED`,
  on lease fencing and on migration idempotency, none of which can be verified against mocks. Behind a
  `//go:build integration` tag so the default `go test ./...` needs no Docker.
- **manual API verification**: the local docker compose stack, exercised with curl (Task 20)
- **e2e**: no UI in this plan; browser verification belongs to plan 3

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

**Library boundary.** The module exposes a small set of public names, one per thing the downstream vendor overrides
today — no speculative extension points:

| Name | Purpose |
|---|---|
| `Config` | flat value struct: catalog and engine addresses, poll interval, worker count, lease duration, retention, pagination limit, production flag. **No DSN** — downstream supplies its own `DB`. |
| `Deps` | `DB`, `Logger`, `HTTPClient`, `CurrentUser` |
| `DB`, `CurrentUserFunc` | aliases so callers can name the types they must implement |
| `New(Config, Deps) (*Service, error)` | wires repositories, services and controllers |
| `(*Service).Mount(fiber.Router)` | registers domain routes and the user-context middleware |
| `(*Service).RunExecutor(ctx) error` | runs the executor, the lease sweeper and retention |
| `Migrations() (*migrate.Migrations, error)` | required by the downstream DBaaS client, which applies migrations itself |

The root package is `testingservice` (`testing-service` is not a valid Go identifier). `Config` and `Deps` are declared
in `internal/config` and re-exported as aliases — the root package imports `internal/…`, so the canonical declarations
cannot live in the root.

`Deps.DB` is a single method, `GetBunDb(ctx) (*bun.DB, error)` — the only one the DAO calls. The vendor's PostgreSQL
client implements it, so it satisfies the interface without an adapter. Authorization is injected as an
`http.RoundTripper` on `Deps.HTTPClient`.

Health, pprof, metrics, tracing, route registration and API versioning live entirely in `cmd/testing-service/main.go`,
which also owns the database DSN. That removes three vendor modules outright and most of a fourth.

**Execution model.** The current executor picks a pending run and flips its status in two separate statements (there is
a `FIXME` about it), runs cases one at a time in undefined order, and cannot recover a run stranded by a crashed pod.
The replacement claims work atomically, runs different test runs concurrently while keeping cases inside one run
sequential, orders cases by a new `ordinal`, and returns expired leases to `pending` under a fencing token.

**Layout.**

```
testing-service/
  go.mod  go.sum  .golangci.yml  VERSION  Dockerfile  README.md  AGENTS.md
  service.go          package testingservice — aliases, New, Mount, RunExecutor
  migrations.go       Migrations(), go:embed
  migrations/         00000000000100__init.tx.up.sql, 00000000000101__execution.tx.up.sql
  cmd/testing-service/main.go
  docs/               swaggo-generated
  internal/
    config/ model/ dao/ matching/ qip/ triggers/ services/ services/importexport/ controllers/ db/
    httpfield/ testsupport/
```

**Port order is dictated by the import graph, not by architectural layers.** In the source, `matching` imports
`controllers/util` for one symbol, `DecodeTestingContext`; `controllers/util` imports `dao` only for the *other* symbol,
`GetEndpointReference`, which is used solely by a controller and therefore stays put. `dao` imports the configuration
package for the pagination limit. `services` imports `triggers` and the platform clients. Hence:

```
config → model → dao → matching → qip → triggers → services → controllers → root facade → cmd
```

## Technical Details

**Migration 100** creates the whole schema and must be idempotent: the downstream vendor already has these objects from
its own migrations 01 and 02, which stay on their side. It covers **15 tables, 8 indexes, 4 enum types, 3 views, 2
trigger functions and 4 triggers**.

| Object | Idempotency technique |
|---|---|
| tables, indexes | `create … if not exists` |
| enum types | `do $$ … exception when duplicate_object` |
| views, trigger functions | `create or replace` |
| triggers | `drop trigger if exists` then `create trigger` |

**The migration must not create the schema.** Downstream the schema is provisioned by their DBaaS and is evidently not
named `testing_service` — that is precisely why their migration 02 stripped the `testing_service.` qualification from
the trigger bodies. A hardcoded `create schema` would either create a stray empty schema in their database or fail
outright if the provisioned role has no `CREATE` privilege, breaking the "applies cleanly on top" property the whole
idempotency effort exists for. Schema creation belongs to the open-source binary only (Task 13).

Two more traps: the trigger function bodies must be the ones from the source's **migration 02**, which rewrote them
from schema-qualified deletes to search-path-relative ones — copying migration 01's bodies would silently revert that
fix downstream. And the file is named `.tx.up.sql`, because bun decides transactionality by that suffix and a plain
`.up.sql` would leave partial DDL on failure.

**Migration 101** carries the execution changes, and each piece has a reason:

- `ordinal integer` on `test_case_runs`, **backfilled** with `row_number() over (partition by tests_run_id order by
  start nulls last, id)` — without a backfill, every pre-existing row downstream gets NULL and `order by ordinal` sorts
  them last in arbitrary order.
- `lease_until timestamptz` and `lease_owner uuid` — the owner is the fencing token, see below.
- index on `(tests_run_id, status, ordinal)`, which is what the claim actually filters on. The only existing index is on
  `test_case_id`, and PostgreSQL does not index the foreign key, so today the claim would be sequential scans. A plain
  index on `status` — a five-value enum — would be useless.
- partial index on `lease_until where status = 'running'` for the sweeper.
- **recreate `test_case_runs_view`.** It is defined as `select test_case_run.*, …`, and PostgreSQL expands `*` at
  creation time, so the view will not gain the new columns on its own. `create or replace view` can only append columns
  at the end, and here the new ones would land in the middle, ahead of the joined columns — so it needs `drop view` plus
  `create view`. The DAO reads the list API through this view, so without this, `ordinal` never reaches the API.

**Work claim — two steps in one transaction.** A single statement guarded by `NOT EXISTS` does *not* serialize cases
within a run: under READ COMMITTED two workers evaluate the guard against their own snapshots, neither sees the other's
uncommitted `running` row, and both claim different cases of the same run. The run row must be locked first:

```sql
-- step 1: claim the run, which is what serializes entrants
select r.id from tests_runs r
where exists (select 1 from test_case_runs c where c.tests_run_id = r.id and c.status = 'pending')
  and not exists (select 1 from test_case_runs c where c.tests_run_id = r.id and c.status = 'running')
order by r.created_at
for update skip locked limit 1;

-- step 2: claim its next case, stamping the fencing token
update test_case_runs set status = 'running', start = now(),
       session_id = $2, lease_until = now() + $3, lease_owner = $4
where id = (select id from test_case_runs
            where tests_run_id = $1 and status = 'pending'
              and not exists (select 1 from test_case_runs busy
                              where busy.tests_run_id = $1 and busy.status = 'running')
            order by ordinal, id
            for update skip locked limit 1)
returning *;
```

**Step 2 must repeat step 1's "nothing running yet" guard**, which review found missing. The run row is only locked,
never updated, so PostgreSQL runs no `EvalPlanQual` recheck behind step 1's `for update`: a worker whose snapshot
predates another worker's commit passes the guard in step 1 and then takes the lock that commit released. Step 2 is a
statement of its own, so under READ COMMITTED it reads a snapshot taken after the lock was granted — repeating the guard
there is what actually upholds "one running case per run".

If step 2 returns nothing — the run's last pending case was canceled between the two steps — release and try the next
run immediately rather than waiting out a poll interval.

**Lease fencing.** A lease with no owner is unsafe. Consider a stalled (not crashed) worker A holding case 1 of run R:
the sweeper returns case 1 to `pending`, worker B legitimately claims case 2, and then A finishes — and a `Finish`
guarded only by `status = 'running'` would happily overwrite **B's** row. `lease_owner` is the fence, and it must guard
**every** write a worker makes about its case: `Finish`, `Skip`, lease renewal, and the recording of validation errors.
Fencing only `Finish` leaves a zombie worker writing errors against someone else's attempt.

The sweep itself is one guarded statement — `update … set status = 'pending', lease_owner = null where status =
'running' and (lease_until is null or lease_until < now())` — not a select followed by an update by id. PostgreSQL
rechecks the qualifier at write time, so a single statement cannot steal a lease that was renewed concurrently, whereas
the two-statement form can. The sweeper does not need the `tests_runs` row lock: every transition *into* `running` goes
through the claim, which evaluates its guard in the same statement that locks the run, and the sweep only ever decreases
the number of running cases.

The `lease_until is null` branch was added in review and is load-bearing: a `running` row created before migration 101
holds no lease at all, `lease_until < now()` is null rather than true for it, and without the branch every case a
downstream installation carries across the upgrade is stranded forever.

**Re-execution must clear the previous attempt's errors.** `validation_errors` carries `unique (test_case_run_id,
matcher_id)`. The source never re-runs a case, so this never mattered; with leases it becomes routine, and the second
attempt's first error on the same matcher would hit the constraint and abort validation. Reclaiming a case must delete
its validation errors along with resetting the status.

**Retention.** `test_case_runs` has no `created_at` — only `start` and `finish`, both NULL for pending and canceled
rows — so age comes from `tests_runs.created_at`. And `test_case_runs` and `validation_errors` are both `on delete
cascade`, so a batched `delete from tests_runs where created_at < now() - $1` cleans all three tables; deleting children
first and then hunting orphaned parents is backwards. The delete must exclude runs that still have `pending` or
`running` cases, or an old run that is stuck or simply long can be removed out from under an executing worker.

**Current user.** `Deps.CurrentUser` cannot be injected where the source reads it: the audit hook is a bun
`BeforeAppendModel` method on the embedded metadata model, which bun constructs — there is no seam. It goes through a
context key: `Mount` installs middleware that resolves the user and puts it into the request context (every controller
already passes `ctx.UserContext()` down), and `RunExecutor` seeds a system user for background writes.

**Trigger bugs to fix** (both confirmed by reading the source):

- `resolvePathParameters` slices the parameter name as `path[match[0]:match[1]-1]`, keeping the opening brace, so the
  lookup never matches; and the value is never substituted. Correct: `path[match[0]+1:match[1]-1]` plus substitution.
- `buildUrl` writes `values := u.Query()` then `u.RawQuery = values.Encode()` without ever reading
  `requestSettings.QueryParameters`, so query parameters are stored, returned by the API, editable in the UI — and never
  sent.

Note a third change that falls out of the port: the source builds a fresh `http.Client{Timeout: …}` per activation, but
with authorization on the shared `Deps.HTTPClient` the per-case timeout has to move to `context.WithTimeout`.

**Generics.** Go forbids type parameters on methods, so `Dao.Run`/`Dao.RunInTx` become package-level functions
`dao.Run[T](ctx, d, fn)`. Today their call sites do `result.(*[]dao.X)` with no ok-form; when the connection cannot be
acquired, `run` returns an untyped nil before the handler runs and the assertion panics.

**Testability seam.** A `Runner` interface alone is not enough: repositories re-enter through the DAO's context helper
and issue real bun queries, so faking the runner still needs a database. The repositories are already interfaces, so
services depend on **the runner plus the repository interfaces**; anything that must exercise real SQL moves to the
integration suite.

**Pagination limit.** `dao.AddPagination` reads the vendor config package directly. It takes the limit as a parameter
instead, threaded from `Config` through the repositories.

**Backward compatibility.** The HTTP contract stays as-is; the downstream front end talks to it. Changes are additive
only. The bug fixes change behavior, not the contract. `cmd` mounts the service under `/api/v1`, which is what the nginx
rule in Task 16 assumes (Task 17 carries the matching Kubernetes route).

## What Goes Where

- **Implementation Steps**: the module, migrations, tests, compose, nginx, helm, CI
- **Post-Completion**: downstream adoption, the first Go tag, license review

## Implementation Steps

### Task 1: Module skeleton and the commit gate

**Files:**
- Create: `testing-service/go.mod`, `testing-service/go.sum`, `testing-service/.golangci.yml`, `testing-service/VERSION`, `testing-service/README.md`
- Create: `testing-service/internal/config/config.go`, `testing-service/internal/config/config_test.go`
- Create: `testing-service/sanitization_test.go`
- Create: `scripts/check-sanitization.sh`, `.githooks/pre-commit`

- [x] create `go.mod` with module path `github.com/Netcracker/qubership-integration-platform/testing-service` and directive `go 1.22` — the downstream build pins `GOTOOLCHAIN=local` to 1.22.x, so nothing may raise it. This binds **every** dependency, not just the obvious ones: pin any library whose current release declares `go 1.23` or later, and record why
- [x] declare `Config` (no DSN) and `Deps` in `internal/config`, with `DB` as the single-method `GetBunDb` interface and `CurrentUserFunc` as a named type
- [x] write `scripts/check-sanitization.sh` reading the token list from a path given by an environment variable, and install it as a pre-commit hook; it must fail closed when the list is missing
- [x] pin a golangci-lint version, write `.golangci.yml` against that version's schema, and exclude the generated `docs/` package
- [x] create `VERSION` with `0.0.0` — the file holds the last released version, and `scripts/compute-release-version.sh` bumps it before tagging — and a `README.md` describing both usage modes
- [x] write tests for `Config` defaults and for the sanitization script, driving it with a **synthetic** token list — a fixture containing a real token would itself violate the gate it is testing
- [x] run `go test ./...` - must pass before next task

⚠️ The machine's git already points `core.hooksPath` at a shared global hooks directory, so the hook could not simply
be written into `.git/hooks`. Installation now goes through a versioned `.githooks/pre-commit` that first delegates to
the global hook and then runs the check, with `scripts/check-sanitization.sh --install-hook` setting the repository-local
`core.hooksPath`.

⚠️ `--tracked` over the whole repository reports around 70 pre-existing lines in `engine/`, `micro-engine/`,
`runtime-catalog/`, `schemas/` and `ui/`. They are public code that predates this plan and matches a deny pattern by
substring. Before Task 20 either widen the `[allow]` section of the token list or scope the working-tree scan to the
paths this plan touches; the pre-commit hook is unaffected, since it only reads staged content.

✅ Resolved in Task 20 by scoping rather than by widening the list. The hits are 39 files, one of them
`infrastructure/.gitignore`, and none is touched by this branch — the intersection with `git diff --name-only
origin/main..HEAD` is empty. Widening the `[allow]` section would have weakened the gate for the sake of code the gate
was never about.

### Task 2: Domain model

**Files:**
- Create: `testing-service/internal/model/*.go`, `testing-service/internal/model/testing_context_test.go`

- [x] port `model/` (exchange, select, importexport, testing context)
- [x] move `DecodeTestingContext` here from `controllers/util`; leave `GetEndpointReference` behind, since it pulls in the DAO and only a controller uses it
- [x] keep the testing-context field names and the header value `Testing-Service-Context` exactly as they are — plan 2 encodes against this shape, and `path` and `operationPath` feed query- and path-parameter matching
- [x] write tests for decoding, including malformed base64 and malformed JSON, plus one golden base64 literal shared with plan 2's engine tests
- [x] run `go test ./internal/model/...` - must pass before next task

[decision] `DecodeTestingContext` now wraps its two failures with `fmt.Errorf(… %w)` naming the header. The source
returned the bare `base64`/`json` error, which reaches the caller with no clue which header was malformed; no caller
inspects the error type, so wrapping costs nothing.

[decision] `FeatureFilterConfiguration.RequresConversionToText` is spelled `RequiresConversionToText` here. Nothing
consumes it yet — `dao` arrives in Task 3 — so the misspelling is free to fix now and expensive to fix later.

[deviation] `github.com/google/uuid v1.6.0` joins `go.mod` with this task rather than a later one: `ImportResult` and
`SelectionSpecification` carry `uuid.UUID` fields. Its `go.mod` declares no `go` directive, so the 1.22 ceiling holds.

### Task 3: Repositories and the DB interface

**Files:**
- Create: `testing-service/internal/dao/*.go`

- [x] port all fifteen repositories, the filtering and sorting helpers, and the bun models
- [x] replace the vendor client type with the `DB` interface from `internal/config`
- [x] change `AddPagination` to take the maximum limit as a parameter and thread it from `Config` — it currently reads the vendor config package, which would leave this package uncompilable
- [x] replace the package-level vendor logger with a `*slog.Logger` supplied at construction (10 of the 43 vendor logging call sites are in this package)
- [x] port `hooks.go` with a temporary local user resolver so this package compiles; Task 4 replaces it with the context seam — without this the task's own test gate is unreachable, since the source file imports the vendor helper
- [x] write tests for filter and sort validation, which need no database
- [x] run `go test ./internal/dao/...` - must pass before next task

[decision] `NewDao` takes `(config.Config, config.Deps)` rather than a loose parameter list. The pagination limit and
the logger reach the four paginated repositories through their constructors, so nothing below `NewDao` reads
configuration.

[decision] The context key is already the unexported struct type Task 4 asks for. Keeping the source's untyped string
constant would have failed `staticcheck` SA1029 in this task's own lint gate, and the change is one line.

[decision] `golang.org/x/exp/maps` is gone. `sortedFeatures` collects and sorts the keys itself, which keeps the
dependency set small and makes the "expected one of" message stable between runs — `maps.Keys` returned them in map
order, so the same bad request produced a differently worded error each time.

[decision] Validation messages are single lower-case clauses (`wrong sorting field "x", expected one of: …`) instead of
the source's two capitalized sentences. They are Go error values first and response bodies second, and no caller parses
them.

[decision] `FindById` and `FindPending` test `len(result) == 0` rather than `result == nil`. The source indexes
`result[0]` after a nil check, so an empty non-nil slice — what a re-used buffer yields — panics. (`FindPending` is
gone as of Task 10; the atomic claim replaced it.)

[decision] Every bulk operation now returns early on an empty input set. `BulkDelete` with no ids built
`... WHERE id IN ()`, which is a syntax error rather than a no-op.

[decision] `AddSpecification` and the test-run listing tolerate a nil specification. The listing dereferenced
`specification.SearchText` right after the nil-guarded validation block, so a nil specification panicked one line later.

### Task 4: Runner, generics and the user seam

**Files:**
- Create: `testing-service/internal/dao/runner.go`, `testing-service/internal/dao/runner_test.go`
- Modify: `testing-service/internal/dao/hooks.go`

- [x] convert `Run`/`RunInTx` into package-level generic functions `Run[T]`/`RunInTx[T]`
- [x] define a `Runner` interface, and make services depend on it **together with the repository interfaces** — faking the runner alone still leaves repositories issuing real queries
- [x] replace the string context key with an unexported struct key type
- [x] resolve the current user from the request context instead of the vendor helper: the audit hook is a bun model method with no constructor to inject into, so a context key is the only seam
- [x] write tests for the generic runner covering success, handler error, and the connection-failure path that panics today, plus a test that the audit hook picks up the user from context
- [x] run `go test ./internal/dao/...` - must pass before next task

[decision] `Runner` keeps an erased signature and `*Dao` implements it; the generics sit on top as `Run[T]`/`RunInTx[T]`.
A generic interface would have forced one `Runner[T]` field per result type in every service. **Overturned in review:**
the handler no longer takes a `bun.IDB`. The signature is `func(ctx context.Context) (any, error)` and the handle
travels in the context, which is what lets `RunInTx` replace it so a nested transaction cannot commit nothing.

[decision] The runner machinery — `Runner`, the generic entry points, `GetDb`, the context key and the connection
handling — moved to `runner.go`; `dao.go` keeps only the `Dao` struct and `NewDao`. The `GetDb` tests moved along with
it to `runner_test.go`.

[decision] The context key was already an unexported struct type, introduced in Task 3 to satisfy `staticcheck` SA1029.
The checkbox stands as verified rather than changed.

[decision] `CurrentUser` falls back to `DefaultUser` (`developer`) rather than failing the write. The source returned an
error when the vendor helper found no user, which would make every background write fail before Task 13 supplies a
`CurrentUser`; the platform's own open-source default is the same fixed name.

[deviation] Fixed a bug the port inherited: `RunInTx` put the *connection* into the context while handing the handler
the transaction, so every repository — which reads its handle from the context — issued its statements outside the
transaction object that commits or rolls back. The context now carries the transaction. Task 10's two-step claim
depends on this, since the row lock and the update it guards must share one transaction.

[deviation] `createDbContext` became `withDb` and returns only a context. Its error result was always nil and forced
two dead branches at each call site.

### Task 5: Matching engine

**Files:**
- Create: `testing-service/internal/matching/*.go`, `testing-service/internal/matching/predicates/*.go`

- [x] port `matching/` and `matching/predicates/`, pointing the data getters at the relocated `DecodeTestingContext`
- [x] fix the path-parameter getter, which ignores the decode error and then dereferences a possibly-nil context — a third latent bug alongside the two trigger ones, and the new malformed-input tests will hit it
- [x] port the existing predicate tests unchanged
- [x] write tests for the entity data getters, especially the query- and path-parameter ones, which the source does not cover and which plan 2 depends on
- [x] run `go test ./internal/matching/...` - must pass before next task

[decision] The header lookup, the single-value check and the decode moved into one `testingContextOf` helper shared by
the query- and path-parameter getters. The source duplicated all three, and the duplicate is exactly where the missing
error check hid.

[decision] `testingContextOf` returns `(nil, nil)` when the header is absent, keeping the source's behavior: an exchange
that never went through a tested chain is not a matcher failure. Both callers return early on it, so the nil context is
never dereferenced.

[deviation] The three new dependencies land here rather than in a later task: `github.com/PaesslerAG/jsonpath v0.1.1`,
`github.com/santhosh-tekuri/jsonschema/v6 v6.0.1` and `github.com/wI2L/jsondiff v0.6.0`, all at the versions the source
used. Each declares `go 1.21` or earlier, as does every module they pull in, so the 1.22 ceiling holds without a pin.

[decision] Three cosmetic fixes inside the predicates, none of which any test asserts on: the `exist` predicate says
`does not exist` instead of `not exists`, the `match` predicate no longer prints the pattern and the data the wrong way
round, and `NewMatchJsonPredicate` wraps its unmarshal failure the way its JSON Schema counterpart already did.

[deviation] `GetEntityDataGetter` refuses a header, query-parameter or path-parameter matcher whose entity name no
request can carry a value under. The source built the getter anyway, and it read nothing out of every exchange, so an
`empty` matcher over it held for every call — an endpoint mock carrying one answered calls meant for the specific mocks
it outranked on creation time. The body and the status are the message itself and still take no name. The refusal
reaches create, update and import through the existing `validateMatchers` path; a row stored before it degrades the way
a broken matcher already does, skipped in `Call` and recorded as a validation error by the executor.

Each entity type is held to the grammar its name actually travels under, stated as what a name may be rather than as a
list of banned characters — three review rounds each banned the one character that round had named, and the next round
found another:

- A header field name is an RFC 9110 token (`internal/httpfield`), the only spelling a header line carries.
- A query-parameter name is any non-blank string. The query string carries it percent-encoded, so a space, a slash and
  an ampersand all survive the round trip and no narrower rule is derivable.
- A path-parameter name is read back out of a literal `{name}` placeholder taking up a whole segment of the operation
  path. `checkPathParameterName` writes that placeholder and reads it back through the steps the getter takes, so a
  name returns only if some template can spell it. That one check covers the slash (two segments), the closing brace
  (the placeholder ends early), `?` and `#` (the path ends there), `%` (the segment is decoded, so the name returns as
  something else) and control characters (`url.Parse` refuses the path). Invalid UTF-8 is refused alongside it: the
  operation path arrives as a JSON string, so such a byte reaches the matcher as U+FFFD.

`TestEntityNameValidationAcceptsExactlyTheMatchableNames` holds all three to that property over the byte space and a
set of multi-byte runes, against an oracle per entity type that builds a real exchange: every accepted name is one some
request produces a value for, and every refused name is one no request does. Two refusals are deliberate and pinned as
exceptions — a blank name, and a header name holding a space, which `net/http` keeps verbatim (go.dev/issue/34540)
while fasthttp folds it into a different key.

[deviation] The path-parameter getter splits the raw path and decodes each segment afterwards, instead of splitting the
decoded one. A `%2F` inside a value used to become a segment boundary, shifting the template alignment and handing the
matcher only the tail of the value. This is the other half of the `url.PathEscape` the trigger applies to substituted
path values.

[decision] `MatchPredicate.Test` calls `MatchString` instead of converting the string back to bytes for `Match`. The
predicate keeps `regexp.Regexp` by value so the ported test can read `predicate.Pattern.String()`; the type carries no
lock, so `go vet` is satisfied.

### Task 6: Migration 100 and the migrations entry point

**Files:**
- Create: `testing-service/migrations/00000000000100__init.tx.up.sql`, `testing-service/migrations.go`
- Create: `testing-service/internal/db/postgres.go`

- [x] write migration 100 covering all 15 tables, 8 indexes, 4 enum types, 3 views, 2 trigger functions and 4 triggers, each idempotent per the table in Technical Details
- [x] take the trigger function bodies from the source's migration 02, not 01
- [x] use `text` and `timestamptz`, leave every object unqualified and rely on `search_path`, and do **not** create the schema here — see Technical Details for why that would break the downstream database
- [x] create `Migrations()` over `go:embed migrations/*.sql`, building a fresh `migrate.Migrations` on every call; the source registers into a package-level value, so a second call would register duplicates
- [x] create the bun/pgdriver `DB` implementation; it must not run migrations as a side effect the way the source does
- [x] write a test asserting the embedded set is discoverable and correctly named
- [x] run `go test ./...` - must pass before next task

[decision] The migration file is executed by bun as a single `ExecContext`, since it carries no `--bun:split`
directives. That is what makes `.tx.up.sql` meaningful, and pgdriver's simple query protocol accepts the multi-statement
body including the `do $$ … $$` blocks.

[decision] `internal/db` takes a DSN rather than the source's five separate connection fields. Task 13 owns the DSN per
the plan, and a URL keeps `sslmode` and `search_path` in one place. `pgdriver.WithDSN` panics on a malformed URL, so
`New` recovers and returns the failure as an error — a typo in `application.yaml` must not crash the process.
**Amended in review:** `New` takes an `Options` struct (`DSN`, `User`, `Password`, `ApplicationName`, `MaxOpenConns`)
and the credentials deliberately stay out of the URL, applied with `pgdriver.WithUser`/`WithPassword` — see the
overturned decision under Task 17. Review also added explicit read and write timeouts, applied before the DSN so a
`read_timeout` in the DSN still wins; pgdriver's 10 s default capped migration 101 on a populated table.

[decision] `New` builds the pool eagerly and `GetBunDb` returns the stored handle, so no mutex is needed. The source
built a fresh `bun.DB` wrapper on every call and guarded it with an `RWMutex`.

[decision] The pool is capped at 16 connections by default. `database/sql` leaves it unlimited, which turns a burst of
executor workers into a burst of PostgreSQL backends.

[deviation] `github.com/uptrace/bun/dialect/pgdialect` and `github.com/uptrace/bun/driver/pgdriver`, both v1.2.1, join
`go.mod` here rather than with the binary in Task 13, because `internal/db` needs them. They pull in
`golang.org/x/crypto` (v0.21.0 at this point, raised to v0.31.0 by a later task) and `mellium.im/sasl v0.3.1`; every one
of the four declares `go 1.21` or earlier, so the 1.22 ceiling holds. Task 18's dependency allowlist has to admit the
`mellium.im/` prefix.

✅ Verified against PostgreSQL 14 in Docker: migration 100 applies to an empty schema, applies a second time cleanly,
and applies on top of a schema already carrying the source's migrations 01 and 02. Object counts after the first apply
match the plan exactly — 15 tables, 8 indexes, 4 enums, 3 views, 2 functions, 4 triggers — and the statement triggers
cascade correctly under a schema not named `testing_service`.

### Task 7: Platform clients and the HTTP trigger

**Files:**
- Create: `testing-service/internal/qip/*.go`, `testing-service/internal/triggers/*.go`
- Create: `testing-service/internal/triggers/http_trigger_test.go`

- [x] port the catalog and engine clients, dropping the M2M token calls — authorization rides on `Deps.HTTPClient`
- [x] point defaults at platform service names and pass the real `chainId` instead of the placeholder the source used
- [x] fix `resolvePathParameters`: strip the braces from the name and substitute the value
- [x] fix `buildUrl`: populate the query string from `requestSettings.QueryParameters`
- [x] move the per-case timeout from a per-activation client to `context.WithTimeout`, since the client is now shared, and build the request with `NewRequestWithContext`
- [x] write tests for path substitution (single, multiple, missing parameter), query parameters (none, one, repeated), and timeout cancellation
- [x] run `go test ./internal/triggers/...` - must pass before next task

[decision] The aggregate client interface is gone. It existed to carry `M2MEnabled()` alongside `Catalog()` and
`Engine()`, and with the M2M flag removed it was a pass-through. `qip.CatalogClient` and `qip.EngineClient` are now
injected directly, so Task 8's trigger resolver takes the catalog client and the trigger factory takes the engine client.

[decision] The clients take their address as a constructor argument rather than reading configuration themselves. The
platform defaults already live in `config.Config` from Task 1, so nothing below `New` reads configuration.

[decision] `FindChainElementById(ctx, id)` became `FindChainElement(ctx, chainID, elementID)`. The catalog ignores the
chain segment and looks the element up by its own id, but `dao.TriggerReference` carries the real chain id, and sending
`any-chain` made the request unreadable in the catalog's access log.

[decision] `TriggerFactory` became `triggers.Factory` and `BuildHttpTrigger` became `NewHTTPTrigger`. `GetTrigger` also
lost its `context.Context` parameter: the only thing that read it was the M2M token call.

[decision] The session identifier moves through an unexported struct key, matching `dao.WithCurrentUser`. The source's
untyped string key fails `staticcheck` SA1029. **Overturned in review:** the context key is gone. The session id is an
explicit parameter the whole way down — `Claim(ctx, owner, sessionID, leaseDuration)`, `ClaimNext(ctx, owner,
sessionID)`, `Activate(ctx, sessionID, …)` — which is what the value always was, and a parameter cannot go missing
silently the way a context value can.

[deviation] Substituted path-parameter values are escaped with `url.PathEscape`. A value carrying a `/` or a `?` would
otherwise reach a different route than the test case names. The source never substituted at all, so nothing depends on
the unescaped form.

[decision] Three smaller fixes that fell out of the port: `convertHttpResponseToExchange` dropped the underlying error
on the floor, the response body is now closed by `Activate` rather than by the converter, and nil entries in the
parameter and header slices are skipped instead of being dereferenced.

### Task 8: Services

**Files:**
- Create: `testing-service/internal/services/*.go`, `testing-service/internal/services/importexport/*.go`

- [x] port every service — test cases, endpoint mocks, matchers, test runs, test case runs, run errors, trigger resolution, execution — plus zip import/export
- [x] replace config-loader reads with `Config` fields and delete the configuration package
- [x] replace the vendor logging calls in this package (17 of the 43, all in the execution service) with `slog`
- [x] depend on the runner and the repository interfaces, and drop the `result.(*[]dao.X)` assertions the generics replace
- [x] write tests for mock selection order (most enabled matchers first, then oldest) using fakes
- [x] run `go test ./...` - must pass before next task

[decision] The repository interfaces reach a service grouped in a `Repositories` value rather than one constructor
parameter each — `testCasesService` alone needs eight. A service stores the runner and that value, so a test supplies
only the repositories the path under test touches. **Amended in review:** the type is `dao.Repositories`, declared and
embedded in `Dao` rather than duplicated in `services`.

[decision] `dao.Run`/`dao.RunInTx` cannot express "no result": `typedResult[any]` asserts an untyped nil and fails. The
write paths that discard their result go through `runInTx`/`runQuery`, whose result type is the empty struct.

[decision] `fiber.StatusOK` and `fiber.StatusNotFound` became the `net/http` constants of the same value. Fiber arrives
with the controllers in Task 9, and nothing else here needs it.

[decision] The mock-response delay now waits on a timer against the request context instead of `time.Sleep`, so a caller
that gives up is not held for the full delay. When no request-start timestamp reached the context, the full delay
applies; the source silently skipped the delay altogether.

[decision] `TimestampKey`, an untyped string context key, became the unexported `requestStartKey` struct with a
`WithRequestStart` accessor, matching `dao.WithCurrentUser` and `triggers.WithSessionID`. `staticcheck` SA1029 rejects
the original.

[decision] `ErrorEmptyTestCaseList`, a struct implementing `error`, became the `ErrEmptyTestCaseList` sentinel. The
controller in Task 9 matches it with `errors.Is`.

[deviation] Fixed the CSV export of test case runs. `Start.String()` and `Finish.String()` panicked on a pending run,
whose timestamps are nil; the "Test Case Run ID" column carried the test case id, so the run id was never exported; and
the per-error rows appended into the shared field slice, so the second error of a run overwrote the first row's matcher
columns. Timestamps are now RFC 3339.

[deviation] `ResolveTrigger` rejects a nil trigger reference instead of dereferencing it. The column is nullable, and a
test case saved without a trigger crashed the executor.

[deviation] `doUpdate` in both CRUD services checks the error from its `FindById` before the nil result. A failing lookup
used to be reported as "not found", hiding the real failure.

[deviation] `MigrateEntityData` rejects a version below one, which used to slice the migration list from -1 and panic on
a hand-edited archive. Archive entries are also capped at 32 MiB — `gosec` G110 flags the unbounded copy as a
decompression bomb. **Amended in review:** the migration framework had no migrations to run and was removed, leaving
`CheckDataVersion(version int) error`, which bounds the version on both sides against `ActualDataVersion`.

[deviation] `importEntityFromFile` checks the error from `zip.File.Open` before deferring `Close`; the source deferred
first and dereferenced a nil reader whenever an entry could not be opened.

[deviation] The archive is bounded on two more dimensions than the per-entry cap: at most 10,000 entries, and at most
512 MiB decompressed across them. The per-entry cap alone left both an archive of many tiny entries — each one a
database transaction, and a few megabytes on the wire bought tens of thousands of them — and an archive of many large
ones unbounded. The numbers come off what an export weighs: one entry is the JSON of one test case or endpoint mock, a
deliberately heavy one measures about 175 KiB and a typical one a few KiB, and a whole-installation export runs to a few
thousand entities. An archive over the entry count is refused whole, before anything is read; the byte budget stops the
importer mid-archive, and the entries it already covered keep the outcome they got.

### Task 9: Controllers and the facade

**Files:**
- Create: `testing-service/internal/controllers/**/*.go`, `testing-service/service.go`

- [x] port the controllers and their pagination, sorting and response helpers, keeping `getEndpointReference` alongside its only caller
- [x] register routes in `(*Service).Mount(router fiber.Router)` together with middleware that resolves the current user into the request context
- [x] read the production-mode flag from `Config` in the mode controller
- [x] keep every path and payload identical, including `?return_ids=true` on the list endpoints, which the UI relies on
- [x] create the root `service.go` in package `testingservice` with `New`, `Mount`, `RunExecutor` and the public aliases
- [x] update swagger annotations, removing the vendor product name
- [x] write tests mounting the service on a bare fiber app and asserting routing, status codes and that the user middleware populates the context
- [x] run `go test ./...` - must pass before next task

[decision] The source's `controllers/util` and `controllers/v1` are one flat `internal/controllers` package. The split
existed to share helpers across version packages, and there is only one version; flattening also retires the `util`
package name and lets every controller stay unexported behind `New` and `Mount`.

[decision] The response helpers became methods on an embedded `responder` holding a `*slog.Logger`. They are the last
of the 43 vendor logging call sites, and a package-level logger cannot take the one the host supplies through `Deps`.

[decision] `ErrorMessage.ServiceName` is the constant `testing-service`. The source read it from the vendor
configuration loader, which is gone; the field is informational and no caller matches on it.

[decision] `Mount` registers relative paths — `/test-cases`, not `/api/v1/test-cases`. The host owns the prefix, and the
binary in Task 13 mounts under `/api/v1`, which is what the nginx rule in Task 16 assumes. The swagger `@Router`
annotations still spell the full public path.

[decision] The general swagger info moved from the source's router to `(*Controllers).Mount`, which is the new route
table. The title is `Testing Service API` and the description names this platform, so neither carries the former product
name. Task 14 points the generate directive here.

[deviation] Two `@Router` annotations were wrong in the source and are corrected: the test case delete said
`/api/v1/test-case/{id}` and the bulk delete said `/api/v1/tests-cases`. Both are annotations only; the registered
routes are unchanged and stay byte-identical to the source.

[decision] `New` returns `ErrNoDatabase` when `Deps.DB` is nil. Every operation needs it, so refusing at construction
beats failing on the first request.

[decision] `RunExecutor` returns nil once its context is canceled. Cancellation is the ordinary way to shut down, and an
`errgroup` member that reports it as a failure hides the real one. Task 11 replaces the shutdown path itself.

[decision] The executor's background writes are attributed to `Deps.CurrentUser` when a host supplied one, and to
`dao.DefaultUser` otherwise. There is no request behind them, so a host that wants them named has to say so.

[deviation] `github.com/gofiber/fiber/v2 v2.52.15` joins `go.mod` with this task. It declares `go 1.20`, as does its
whole transitive closure, so the 1.22 ceiling holds; v3 requires `go 1.23` and is therefore out of reach.

### Task 10: Migration 101 and the atomic claim

**Files:**
- Create: `testing-service/migrations/00000000000101__execution.tx.up.sql`
- Modify: `testing-service/internal/dao/test_case_runs_repository.go`, `testing-service/internal/services/test_case_runs_service.go`, `testing-service/internal/services/tests_runs_service.go`

- [x] add `ordinal`, `lease_until` and `lease_owner`, and backfill `ordinal` for existing rows
- [x] add the `(tests_run_id, status, ordinal)` index and the partial `lease_until` index
- [x] drop and recreate `test_case_runs_view` so the new columns reach the list API — `create or replace` cannot do it
- [x] assign `ordinal` when a test run is created, in the order the cases were selected
- [x] implement the two-step claim from Technical Details, stamping `lease_owner`
- [x] when step 2 finds nothing, move on to the next run instead of waiting a poll interval
- [x] fence every worker write on `lease_owner` — `Finish`, `Skip` and the recording of validation errors, not just `Finish`
- [x] write tests for ordinal assignment and for the fenced writes against fakes
- [x] run `go test ./...` - must pass before next task

[decision] `TestCaseRunsRepository.FindPending` and `Update`, and `TestCaseRunsService.FindPendingTestCaseRun` and
`Start`, are gone. The claim is what starts a run, so a separate start call would either duplicate the stamp or race
with it, and the only remaining writes to a claimed case are the fenced ones. `Update` became `UpdateOwned` and
`TestCaseRunErrorsRepository.Insert` became `InsertOwned`; both report the new `dao.ErrLeaseLost` when the fence
rejects the write, and the executor logs that as a warning rather than a fault.

[decision] The owner token is a plain parameter on every write (`Finish`, `Skip`, `AddError`) rather than a value
carried in the context. A context-borne token would fence silently and leave nothing for a reader of the signature to
check; the explicit parameter is what makes an unfenced write visible at the call site.

[decision] `NewTestCaseRunsService` takes `config.Config` first, matching `NewTestExecutionService`. The lease duration
is the only setting it reads, and nothing below the constructor reads configuration.

[decision] Step 2 orders by `ordinal, id`. Rows that predate migration 101 in a downstream database are backfilled, but
a tie is still possible, and an arbitrary order among tied rows would make the queue non-deterministic.

[decision] The lease is stamped from `now()` in the database (`now() + make_interval(secs => ?)`), not from the
worker's clock. Workers and the sweeper compare against database time, so a skewed pod clock cannot sweep an
unexpired lease.

[deviation] A permanent fault now finishes the case run with the fault recorded against it: a run that references no
test case, and a test case that no longer exists. Before the claim, such a case was left `pending` and the poll loop
picked it up forever; with leases it would be swept back into the queue forever instead. A failure that may pass on
retry — the lookup itself failing — still leaves the lease to expire, which is what the sweeper is for.

[deviation, added in review] Migration 101 also carries a cutover statement: it returns every `running` case with a null
`lease_owner` to `pending` and deletes that attempt's validation errors. Those rows belong to the old unfenced executor,
which no fence can recognize and no sweeper can date, so leaving them alone strands them. The migration states its
precondition at the top of the CTE — stop the old executor before applying it — rather than inventing a time-based
heuristic for whether a row is really abandoned.

✅ Verified against PostgreSQL 14 in Docker with a throwaway build-tagged suite (removed afterwards; the real
integration suite is Task 15): migrations 100 and 101 apply in one group to a fresh schema; the backfill numbers
pre-existing rows by `start nulls last, id` and renumbers nothing on a second apply; two runs claim in parallel while a
third claim finds nothing; eight concurrent workers over eight runs never claim the same case twice; `UpdateOwned` and
`InsertOwned` refuse a foreign owner and accept the right one; the recreated view exposes `ordinal` and `lease_owner`;
and a run whose next case is locked elsewhere is excluded by id so the claim reaches the next run.

### Task 11: Workers, lease sweeper and shutdown

**Files:**
- Modify: `testing-service/internal/services/test_execution_service.go`, `testing-service/service.go`, `testing-service/go.mod`

- [x] run a configurable pool of workers, each claiming its own case with its own owner token
- [x] wake workers by signal when a run is created, keeping the ticker as a fallback
- [x] renew the lease while a case runs, guarded by the owner token
- [x] add a sweeper that reclaims expired leases in **one** guarded statement (`where status = 'running' and (lease_until is null or lease_until < now())`, widened in review to catch the leaseless rows migration 101 inherits), clearing `lease_owner` and deleting the attempt's validation errors; a select-then-update-by-id form can steal a lease renewed between the two statements
- [x] replace the `quit` channel with a context so shutdown no longer blocks until the queue drains
- [x] run `go mod tidy`
- [x] write tests for lease renewal, expiry selection, owner-token rejection, and that a reclaimed case re-executes cleanly rather than colliding with its previous attempt's validation errors
- [x] run `go test ./...` - must pass before next task

[decision] The sweep and the delete of the reclaimed attempt's errors are one statement, not two: a data-modifying CTE
updates the expired rows and a second one deletes the validation errors of exactly what it returned. Two statements
would leave a window in which a reclaimed case is pending again while its previous errors still exist, and a worker fast
enough to claim it in that window would hit the unique constraint the delete exists to clear.

[decision] The sweep also clears `lease_until` and `start`. The plan's statement names only `status` and `lease_owner`,
but the claim stamps all four together, so clearing all four is what makes a reclaimed row indistinguishable from one
that was never claimed. Nothing reads `lease_until` outside the `status = 'running'` guard, so this is hygiene rather
than correctness.

[decision] The renewal and sweep intervals are derived from `LeaseDuration` — a third and a half of it — rather than
added to `Config`. A case renews three times per lease and the sweeper looks twice, so the two rates stay in the
relation the lease defines and a host cannot configure them into contradiction.

[decision] The wake signal reaches the executor through a `services.WorkNotifier` interface that `TestsRunsService`
takes, not through the executor type. The queue writer has no other reason to know what runs its work, and the interface
is what a test asserts the signal on. A nil notifier is allowed and leaves the executor to find the run on its next poll.

[decision] The wake channel holds one token. A worker that takes it drains the queue and signals on in turn before
executing its case, so a second pending token would buy nothing; the ticker covers a signal that was dropped.

[deviation] Shutdown no longer runs the queue dry, and it no longer runs the case in flight to completion either: the
canceled context reaches the trigger activation and the fenced writes. The case keeps its lease until it expires, and
the sweeper — here or in another replica — hands it out again. That is the same path a crashed pod takes, and it is what
lets `RunExecutor` return promptly instead of blocking on a test case that hangs.

✅ Verified against PostgreSQL 14 in Docker with a throwaway build-tagged suite (removed afterwards; the real
integration suite is Task 15): a live lease survives the sweep, an expired one returns to `pending` with `lease_owner`,
`lease_until` and `start` cleared and its validation errors gone, and the reclaimed case is claimed again and records
the same matcher without hitting `unique (test_case_run_id, matcher_id)`; `RenewLease` refuses a foreign owner and a
case that is no longer running, and a renewed lease is skipped by the sweep. The single-statement claim was checked
against the race it exists for: with a renewal holding the row uncommitted, the sweep blocks, then rechecks its
qualifier after the commit and reclaims nothing.

### Task 12: Run retention

**Files:**
- Modify: `testing-service/internal/services/tests_runs_service.go`, `testing-service/internal/config/config.go`, `testing-service/service.go`
- Create: `testing-service/internal/services/retention_test.go`

- [x] add retention settings to `Config` (age threshold and sweep interval; disabled when the threshold is zero)
- [x] delete by `tests_runs.created_at` and let the existing cascades remove case runs and validation errors — `test_case_runs` has no creation timestamp of its own
- [x] exclude runs that still have `pending` or `running` cases, so a long or stuck run is never deleted out from under a worker
- [x] batch the deletion so a large backlog does not hold a long transaction, and start the sweep from `RunExecutor`
- [x] write tests for threshold behavior, batching, the active-run exclusion, and the disabled case
- [x] run `go test ./...` - must pass before next task

[decision] `RetentionAge` is the one setting `WithDefaults` leaves alone. Every other non-positive number counts as
unset and gets a default, but a host that named no age has not asked for anything to be deleted, so a default here would
silently start deleting test runs on upgrade. `RetentionInterval` keeps the usual treatment, since it only paces a sweep
that is off anyway. `Config.RetentionEnabled` names the rule so no caller has to repeat the comparison.

[decision] The batch size is the constant `retentionBatchSize`, not a `Config` field. It trades statement duration
against the number of statements, which is a property of the schema rather than of an installation, and nothing in the
plan asks a host to tune it.

[decision] The first sweep waits out an interval rather than running at startup. Retention is not urgent, and a restart
loop that deletes on every boot is worse than one that waits.

[decision] `NewTestsRunsService` takes `config.Config` and a `*slog.Logger` first, matching `NewTestExecutionService`.
The retention loop is the first thing in this service with something to report. (`NewTestCaseRunsService` takes the
configuration but no logger, having nothing of its own to log.)

[deviation] `RunExecutor` now runs the executor and retention as two goroutines under a `WaitGroup` instead of calling
the executor inline. Both stop on the same canceled context, and the function still returns only once both have.

[deviation] The active-run exclusion is asserted in a new `internal/dao/tests_runs_repository_test.go` rather than in
`internal/services/retention_test.go`: the guard lives in the statement, and the statement is unexported in `dao`. The
service-level tests cover the threshold, the batching, the failure path and the disabled case against fakes.

✅ Verified against PostgreSQL 14 in Docker with a throwaway build-tagged suite (removed afterwards; the real
integration suite is Task 15): with migrations 100 and 101 applied, a sweep over five seeded runs deletes only the aged
one whose cases are all finished — the aged runs holding a `pending` and a `running` case, the recent run and a run with
no `created_at` all survive — and the cascades take the deleted run's case runs and its validation error. A second sweep
finds nothing, and a backlog of five aged runs comes out as batches of 2, 2 and 1 under a batch size of two.

### Task 13: Standalone binary

**Files:**
- Create: `testing-service/cmd/testing-service/main.go`, `testing-service/application.yaml`

- [x] read configuration with koanf from `application.yaml` plus environment overrides; the DSN lives here, not in `Config`
- [x] construct `slog`, the bun-backed `DB` and an `http.Client`; leave `CurrentUser` unset, because this binary does not authenticate its callers and `dao.DefaultUser` already names them `developer`
- [x] create the schema before initializing the migrator: bun creates its bookkeeping table first, and with `search_path` pointing at a schema that does not exist yet the initialization fails
- [x] mount the service under `/api/v1`, which the nginx rule depends on, and start `RunExecutor`
- [x] serve `/health` for the compose healthcheck and `/prometheus`; keep pprof behind a flag
- [x] handle SIGINT and SIGTERM with graceful shutdown of server and executor
- [x] write tests for configuration precedence and the health handler
- [x] run `go test ./...` - must pass before next task

[decision] Every configuration key is a single word under one level of nesting, so an environment variable maps onto a
key by lowercasing it and replacing `_` with `.`: `QIP_TESTING_POSTGRES_DSN` is `postgres.dsn`. A key spelled with a
dash, as the source spelled `maximum-limit`, has no unambiguous environment form.

[decision] A missing configuration file is not an error. Defaults plus `QIP_TESTING_*` configure the service on their
own, which is what the Helm chart in Task 17 does; only an unreadable or malformed file stops startup.

[decision] `defaultAppConfig` fills in only the settings this binary owns — the listen addresses, the schema, the pool
size and the logging. Everything the library owns stays zero and picks up its value from `Config.WithDefaults`, so no
default is written down twice and `RetentionAge` stays off unless `application.yaml` names one.

[decision] pprof is a configuration flag (`pprof.enabled`) on a listener of its own (`pprof.bind`, `:6060`), not a route
on the API port. The nginx rule exposes only `/api/v1/…`, but the API port is reachable from inside the network, and the
source kept pprof on a separate port too.

[decision] `/health` reports `UP` only after a database round-trip, bounded by a three-second timeout. The compose
healthcheck gates `depends_on: service_healthy`, and an instance that cannot reach PostgreSQL serves nothing.

[decision] A failed migration is not rolled back, unlike the source. Both files carry the `.tx.up.sql` suffix, so a
failure leaves nothing behind, and there are no down migrations for `Rollback` to run.

[deviation] `github.com/knadh/koanf/v2 v2.1.2` and `github.com/prometheus/client_golang v1.20.5` join `go.mod` with this
task. koanf is held back because v2.2.2 declares `go 1.23`; `client_golang` and its whole transitive closure declare
`go 1.20` or earlier, and every module in the graph still declares `go 1.21` or earlier. `/prometheus` serves the default
registry through `promhttp` — the Go and process collectors, no custom metrics.

[deviation] `serve` cancels the whole process on the first failure among the API listener, the executor and pprof, and
reports that one failure. Whatever the others report afterwards is the shutdown it set off, so surfacing it would bury
the cause.

✅ Verified against PostgreSQL 14 in Docker: the binary creates the `testing_service` schema, applies migrations 100 and
101 in one group, answers `/health` with `UP`, exposes the Go collectors on `/prometheus`, serves `/api/v1/mode` and
`/api/v1/test-cases`, serves pprof on its own port when enabled, exits 0 on SIGTERM, and finds the schema up to date on
the next start.

### Task 14: Swagger docs

**Files:**
- Create: `testing-service/docs/*.go`
- Modify: `testing-service/cmd/testing-service/main.go`, `testing-service/.golangci.yml`, `.github/super-linter.env`

- [x] generate and commit the `docs/` package, pointing the generate directive at the new annotation location
- [x] confirm the generated spec carries no vendor product names
- [x] serve the spec and swagger UI from the binary
- [x] exclude the generated package from both the module's linter config and the repository's super-linter exclusions
- [x] run `go mod tidy` now that every import finally exists — koanf arrives with the binary in Task 13 and swagger with this task, so an earlier tidy would have stripped them — and verify the dependency set still builds under directive `go 1.22`
- [x] run `go build ./...` and the linter - must pass before next task

[decision] The `go:generate` directive lives in the root `service.go`, not in the annotated
`internal/controllers/controllers.go` and not in `cmd/testing-service/main.go`. `go generate` runs with the working
directory set to the package that carries the directive, and the search dir, the general-info file and the output
directory are all module-root paths; anywhere else they would be spelled `../..`.

[decision] The directive runs the CLI as `go run github.com/swaggo/swag/cmd/swag@v1.16.4`, so regenerating needs no
separately installed tool and always uses the same version. `go run pkg@version` builds in module-agnostic mode, so
swag's own dependencies never enter this module's graph — only the small `github.com/swaggo/swag` runtime package that
the generated `docs.go` imports does.

[decision] `--parseDependency` is required, not optional: the response models embed `bun.BaseModel`, and without it swag
fails with `cannot find type definition`. `--parseInternal` is required because every controller and model lives under
`internal/`. The generated spec has 27 paths and 21 definitions, and none of the dependency types leaked into it.

[decision] The spec carries no `@version`. `testing-service/VERSION` is the single source of the version and Task 19
bumps it; a second copy in an annotation would go stale on the first release. The Swagger 2.0 `info.version` field
stays empty, which the UI renders without complaint.

[decision] Swagger is served under the API prefix — `/api/v1/swagger/index.html` for the UI, `/api/v1/swagger/doc.json`
for the spec — because the nginx rule in Task 16 exposes nothing else. `fiberswagger` derives that prefix from the route
it is registered on and honors `X-Forwarded-Prefix`, so it works behind the proxy as well as directly. **Overturned in
Task 20:** it does not. The handler is registered with a relative `doc.json` instead; see the finding recorded there.

[deviation] `github.com/gofiber/swagger v1.1.1` and `github.com/swaggo/swag v1.16.4` join `go.mod`, pulling in
`swaggo/files/v2`, the `go-openapi` packages and `golang.org/x/tools`; `golang.org/x/crypto` moves from v0.21.0 to
v0.24.0, and later to v0.31.0. Every module in the resulting graph still declares `go 1.22` or earlier — checked with
`go list -m -f '{{.GoVersion}}' all` — and `go build ./...` and `go test ./...` were run under a real go1.22.12
toolchain, not just under the 1.22 language level.

[decision] The module's `.golangci.yml` already excluded `docs` from Task 1, so this task only added the repository's
super-linter exclusion. `FILTER_REGEX_EXCLUDE` gains a second alternative for `testing-service/docs/`, which keeps
yamllint and jsonlint off the generated `swagger.yaml` and `swagger.json`.

✅ The sanitization script was run explicitly over `docs/docs.go`, `docs/swagger.json` and `docs/swagger.yaml`, and over
the rest of the change set: clean.

### Task 15: Integration tests

**Files:**
- Create: `testing-service/internal/testsupport/postgres.go`, `testing-service/internal/testsupport/doc.go`
- Create: `testing-service/internal/db/migrations_integration_test.go`, `testing-service/internal/services/execution_integration_test.go`
- Modify: `testing-service/go.mod`, `testing-service/go.sum`

- [x] add `testcontainers-go` **pinned to a release that still supports go 1.22** — current versions require 1.23 and `go mod tidy` would raise the directive for the downstream too
- [x] put every integration test behind `//go:build integration`
- [x] test that migration 100 applies to an empty database and that applying it twice succeeds
- [x] test that migration 101 backfills `ordinal` for rows created before it and that the recreated view exposes the new columns
- [x] test the claim under concurrency: N workers against M pending cases, asserting no case is claimed twice
- [x] test that two runs progress concurrently while cases inside one run stay sequential and ordered
- [x] test lease expiry returning a stranded case to `pending`, that the stalled worker's later writes are rejected by the owner token, and that the reclaimed case re-executes without colliding with the previous attempt's validation errors
- [x] test that retention leaves a run with pending or running cases alone
- [x] run `go test -tags integration ./...` - must pass before next task

[decision] `github.com/testcontainers/testcontainers-go v0.35.0`, the last release that declares `go 1.22`: v0.36.0
declares `go 1.23.0`. Only the base package is used, not `modules/postgres`, which would add pgx and the gRPC gateway for
a wrapper that saves three lines here. Every module in the resulting graph still declares `go 1.22` or earlier — checked
with `go list -m -f '{{.GoVersion}}' all` — and `go build`, `go vet` and `go test`, all with `-tags integration`, were
run under a real go1.22.12 toolchain rather than at the 1.22 language level of the local one.

[decision] `internal/testsupport` carries an untagged `doc.go` alongside the tagged `postgres.go`. Every helper is behind
the tag, so the default build pulls in neither Docker nor testcontainers, and the package clause keeps `go build ./...`
from failing with "build constraints exclude all Go files".

[decision] One container per test binary, started on the first call and stopped by `testsupport.RunMain`, which a
package using it calls from `TestMain`. Each test takes a schema of its own and a pool whose `search_path` points at it,
so tests in one package neither see each other's rows nor pay for a container each.

[decision] The stranded case is produced by backdating `lease_until`, not by sleeping out a short lease.
`Config.WithDefaults` replaces a non-positive `LeaseDuration`, so a sub-second lease cannot be configured, and waiting
one out would make the test both slow and timing-dependent.

[decision] The execution suite is `package services_test`. `testsupport` imports the module root for `Migrations()`, and
the root imports `internal/services`, so the same file inside `package services` would be an import cycle.

[decision] Retention is asserted through `TestsRunsRepository.DeleteExpired` rather than through `RunRetention`. The
guard that this task is about lives in the statement; the ticker, the batching and the disabled case are already covered
against fakes in Task 12.

[note] Task 18's dependency allowlist has to admit what testcontainers pulls in: `github.com/testcontainers/`,
`github.com/docker/`, `github.com/moby/`, `github.com/containerd/`, `github.com/opencontainers/`,
`github.com/distribution/`, `github.com/Microsoft/`, `github.com/Azure/`, `github.com/shirou/`, `github.com/tklauser/`,
`github.com/lufia/`, `github.com/power-devops/`, `github.com/shoenig/`, `github.com/yusufpapurcu/`,
`github.com/go-ole/`, `github.com/cpuguy83/`, `github.com/morikuni/`, `github.com/magiconair/`, `github.com/gogo/`,
`github.com/sirupsen/`, `github.com/felixge/`, `github.com/cenkalti/`, `github.com/go-logr/`, `dario.cat/` and
`go.opentelemetry.io/`.

✅ Verified against PostgreSQL 14 in Docker: eight integration tests over two packages pass under go1.22.12, and the
default `go test ./...` still needs no Docker. `golangci-lint run` is clean both with and without the build tag. (Review
grew the tagged suite to 24 tests over four packages, adding `internal/dao` and `cmd/testing-service`.)

### Task 16: Container image and the local stack

**Files:**
- Create: `testing-service/Dockerfile`
- Modify: `infrastructure/docker-compose.yml`, `infrastructure/nginx/routes.conf`

- [x] write a multi-stage Dockerfile on public images, uid 10001, port 8080, curl for the healthcheck; run only the default test suite during build, not the integration-tagged one
- [x] add `qip-testing-service` with the alias `testing-service` on host port 8095 — 8094 is taken by the commented-out AI assistant block
- [x] do not make the engine depend on this service; the dependency would form a cycle
- [x] add the nginx location for `^/api/(v\d+)/.*/testing-service`, remembering this service serves `/api/v1/*` while the Java modules serve `/v1/*`
- [x] exclude `/endpoint-mocks/call` from the public routes — only the engine calls it, from inside the network
- [x] verify the stack starts and the container reports healthy

[decision] The build stage is `golang:1.22-alpine`, which ships go1.22.12, with `GOTOOLCHAIN=local` so a dependency
asking for a newer toolchain fails the build instead of quietly raising the ceiling the downstream depends on. The
runtime stage is `alpine:3.21` with curl pinned to `8.14.1-r2` — the same version the four Java images pin, and what
alpine 3.21 currently carries. Pinning is what `hadolint` DL3018 wants, and super-linter runs it over this file.

[decision] The compose entry spells out the DSN and the catalog and engine addresses as `QIP_TESTING_*` variables. The
other services carry their configuration in the compose file through `env_file`, so a reader looking there for what this
one talks to finds it in the same place, and the environment path gets exercised on every start. Review later emptied
the DSN in the shipped `application.yaml`, since a default carrying credentials does not belong in the image, so
`QIP_TESTING_POSTGRES_DSN` is now required rather than merely explicit.

[decision] `ui-proxy` gains a `depends_on` on the new service. nginx resolves a statically named upstream when it loads
its configuration and refuses to start if the name does not resolve, which is exactly why the three existing services
are listed there.

[decision] The `/endpoint-mocks/call` exclusion is a `return 404` location placed **before** the proxy location. nginx
takes the first matching regex location in file order, so ordering is what makes the exclusion work; a negative
lookahead in the proxy pattern would have to be repeated in the rewrite as well.

✅ Verified against the local stack: the image builds and runs the untagged suite inside the build stage (the
`integration`-tagged suite is excluded by the tag), the container comes up `healthy` on the real `/health` database
ping, and `/api/v1/qip/testing-service/{mode,test-cases,endpoint-mocks,swagger/doc.json}` all reach the service through
the nginx rule while `/endpoint-mocks/call` is refused by the proxy and still answers on the in-network port. Route
verification ran against a throwaway nginx container bound to this worktree's `routes.conf`, since the stack's running
`ui-proxy` mounts the main checkout's copy. `go test ./...`, `golangci-lint run ./...`, `hadolint` and the sanitization
check are all clean.

### Task 17: Helm chart and the Kubernetes route

**Files:**
- Create: `infrastructure/qip-dev/charts/qip-testing-service/Chart.yaml`, `.../templates/*.yaml`
- Modify: `infrastructure/qip-dev/values.yaml`, `infrastructure/qip-dev/charts/ui/templates/config.yaml`

- [x] model the chart on `qip-sessions-management`
- [x] add the route to the UI chart's routing config — it is a second, independent copy of the nginx table, and without it the Kubernetes deployment has no path to the service
- [x] guard the templates on a values flag so the chart can be left out; note that no existing chart here does this, so there is no in-repo pattern to copy
- [x] pass catalog and engine addresses and the schema through the config map; compose the DSN in the deployment, and leave the worker, lease, poll and pagination settings to `Config.WithDefaults` rather than spelling a default out twice
- [x] verify with `helm lint` and `helm template`, with the flag both on and off

[decision] The flag is `global.qip.testingService.enabled`, not a subchart-local `.Values.enabled`. The nginx route
names the service statically, so nginx refuses to start once the chart is gone; the UI subchart therefore has to read
the same flag, and only a `global.` value is visible from both subcharts. The spelling follows the camelCase keys
already under `global.qip` (`variables.defaultSecret`).

[decision] The DSN is composed in the deployment from `$(POSTGRES_URL)` and `$(QIP_TESTING_POSTGRES_SCHEMA)` rather
than spelled out in the config map. Kubernetes expands `$(VAR)` in a `value:` against the variables declared above it,
whichever source they came from, and a config map value cannot pull the address in on its own. The schema is named
once, in the config map, and reaches both `search_path` and `QIP_TESTING_POSTGRES_SCHEMA` from there.

[decision] **Overturned.** The credentials no longer reach the DSN. They travel as `postgres.user` and
`postgres.password` — `QIP_TESTING_POSTGRES_USER` and `QIP_TESTING_POSTGRES_PASSWORD` in the chart, both from the
shared `postgres-auth` secret — and `db.New` applies them with `pgdriver.WithUser` and `pgdriver.WithPassword` after
`WithDSN`. Interpolated into the URL they had to be percent-encoded, and a password holding a `@`, a `/` or a `#`
either failed to parse or pointed the driver at another host.

[decision] The config map hardcodes the worker, lease, poll, pagination and production settings the way
`engine-env-configmap.yaml` hardcodes its own, instead of plumbing each through `values.yaml`. These charts are the dev
stack, and no other setting in them is tunable.

[decision] **Overturned.** The worker, lease, poll and pagination values were dropped from the config map, because
naming them there repeats a default the service already ships. The deployment no longer references them either: an
`env` entry with a `configMapKeyRef` is not optional, so a key the config map stopped rendering left the pod in
`CreateContainerConfigError`. The four settings now come from `Config.WithDefaults` alone, which is the only place they
are spelled out.

⚠️ `helm lint` does not resolve `configMapKeyRef` and `secretKeyRef` against the manifests the same render produced, so
it passes a deployment that can never start. Verify the chart by rendering it and then checking that every
non-optional key reference in the rendered deployments resolves to a key of a rendered ConfigMap or Secret:
`helm template qip . --set global.qip.testingService.enabled=true`, then walk the stream and match each
`valueFrom.configMapKeyRef` / `valueFrom.secretKeyRef` against the `data` of the ConfigMap or Secret it names. Run it
with the flag both on and off whenever a chart template or a config map key changes.

[decision] `QIP_TESTING_RETENTION_AGE` is absent, so retention stays off. Naming an age here would start deleting test
runs on every installation that adopts the chart.

[decision] The image is `ghcr.io/netcracker/qubership-integration-testing-service:latest`, hardcoded like every sibling
chart's image. Task 19 publishes it.

⚠️ `helm lint` on the parent chart fails with "chart metadata is missing these dependencies", listing all ten
subcharts. Helm 4 wants an `apiVersion: v2` parent to declare its subcharts; this parent declares none, so the failure
predates this task and reproduces with the new chart removed. Linting the new subchart on its own is clean, and
`helm template` renders the parent both ways.

✅ Verified: `helm template` with the flag on renders the config map, the service and the deployment plus the two nginx
locations; with the flag off it renders none of them and no `testing` string reaches the routing config. Both rendered
routing configs pass `nginx -t` in a throwaway container with the upstreams stubbed in `/etc/hosts`.

### Task 18: CI

**Files:**
- Create: `.github/workflows/testing-service-build.yaml`, `scripts/check-go-dependencies.sh`, `testing-service/dependencies_test.go`
- Modify: `.github/workflows/main-build.yaml`, `.github/super-linter.env`, `.editorconfig`
- Modify: `scripts/check-sanitization.sh`, `.githooks/pre-commit`, `testing-service/cmd/testing-service/main_test.go`, `testing-service/internal/dao/test_case_runs_repository.go`, `testing-service/internal/dao/tests_runs_repository.go`, `testing-service/internal/db/migrations_integration_test.go` (whitespace only, see below)

- [x] add a build workflow path-filtered on `testing-service/**` running `go build`, `go vet`, `golangci-lint` and `go test`
- [x] add the dependency-allowlist step over `go.mod` and `go.sum`
- [x] add a Go job to `main-build.yaml` **and extend that workflow's own `paths:` filter**, or a testing-service-only change will never trigger it
- [x] check how `super-linter.yaml` reacts to Go files — it pulls a shared config and may run a second, differently configured Go linter
- [x] leave Sonar out of the Go pipeline for now
- [x] verify the workflow actually runs on a draft PR touching only this module — skipped, not automatable: the branch is not pushed and opening a PR is the maintainer's call. The filters were reasoned through against the change set instead (see below)

[decision] The allowlist is a script, `scripts/check-go-dependencies.sh`, next to `check-sanitization.sh`, rather than
inline YAML. It runs locally in one command, it is covered by `testing-service/dependencies_test.go` the way the
sanitization script is covered by `sanitization_test.go`, and `main-build.yaml` reuses it without repeating it.

[decision] The allowlist is one GitHub organization per entry — 74 prefixes — not `github.com/` as a whole. The plan's
threat is a vendor module reappearing, and a host-level list would wave through anything published under any GitHub
account. A new dependency source therefore has to arrive as a visible edit to the list. The entries are exactly what
go.mod and go.sum name today; `go.uber.org/` turned out not to be among them.

[decision] The script fails closed three ways: a missing module directory, an unreadable `go.mod` or `go.sum`, and a
scan that collected nothing all exit 2 rather than reporting success.

[decision] The check is a step in the build job, before `setup-go`. A module from an unvetted source is worth reporting
as itself rather than as whatever the module download fails with a minute later.

[decision] CI runs the integration suite in a job of its own. GitHub's runners carry a Docker daemon, so testcontainers
works there, and the claim, the lease fence and the migrations have no coverage outside that suite. Keeping it separate
from `build` means a container failure does not hide a compile failure. `go vet -tags integration ./...` runs there too,
since the default vet never sees the tagged files.

[decision] `main-build.yaml` gets `build`, `vet` and `test` but neither the linter nor the integration suite. That
workflow exists to catch cross-module semantic conflicts, the Go module has no cross-module coupling to catch, and both
of the omitted checks give a clearer signal on the PR that caused them. It mirrors what the `npm` job there already
does.

⚠️ Super-linter would have run a second Go linter. Our own `.github/super-linter.env` replaces the shared one wholesale,
and any validator it does not name defaults to on — so `GO` and `GO_MODULES` were both live. Super-linter v8.1.0 ships
golangci-lint **v2.4.0** and points it at `.github/linters/.golangci.yml`, a v2-schema file copied in from
`netcracker/.github`; that binary cannot read this module's v1.64.8 config, and the copied config lints against a
different rule set. Both validators are now off, with the module's own pinned linter named as the owner.

⚠️ Three more shared linters reacted to the new files, all of them enabled for the same reason. `shfmt` and
`editorconfig-checker` are off in the shared environment file and on in ours, and this plan's own shell scripts were the
only ones in the repository that failed them:

- `scripts/check-sanitization.sh`, `.githooks/pre-commit` and the new script are reformatted to the repository's
  `.editorconfig` — four-space indentation, indented `case` branches, spaced redirections. Whitespace only, verified with
  `shfmt -l` and re-checked with `shellcheck`. Every script that predates this plan already complied.
- `.editorconfig` gains `[*.go] indent_style = tab`, which gofmt leaves no choice about, and `indent_style = unset` for
  `[*.md]`, because a fenced block carries the indentation of the language inside it and `README.md` embeds Go.
- The SQL inside the raw string literals in `test_case_runs_repository.go`, `tests_runs_repository.go` and
  `migrations_integration_test.go` is re-indented with tabs, so those files use one indentation character throughout.
  The YAML fixture in `cmd/testing-service/main_test.go` cannot follow — YAML forbids tabs — and carries an
  `editorconfig-checker-disable` block instead. `gofmt -l` stays clean and no test text changed.
- The generated `testing-service/docs/` still fails both, and stays excluded by `FILTER_REGEX_EXCLUDE` from Task 14.

✅ Verified locally, since CI was not observed running: both workflows parse, `actionlint` is clean on them,
`yamllint` is clean under `.github/linters/.yaml-lint.yml`, `shellcheck` and `shfmt` are clean on all three scripts, and
`editorconfig-checker` is clean over `testing-service/` apart from the excluded `docs/`. The allowlist passes over the
real `go.mod` and `go.sum` and rejects a synthetic module from an unlisted source. `go build`, `go vet`, `go test` and
`golangci-lint run` (v1.64.8, the pinned version) all pass, as do `go vet -tags integration` and `go test -tags
integration`; the untagged suite was re-run under a real go1.22.12 with `GOTOOLCHAIN=local`. The path filters were
traced against `git diff --name-only`: this change set touches `testing-service/**` and
`scripts/check-go-dependencies.sh`, so the new workflow fires and no other module build does.

### Task 19: Release workflow

**Files:**
- Create: `.github/workflows/testing-service-release.yaml`
- Modify: `.github/workflows/release-all.yaml`, `scripts/build-bom.sh`, `scripts/compute-release-version.sh`

- [x] publish the image and tag as `testing-service/vX.Y.Z` — Go resolves nested modules only from tags prefixed with the module subdirectory
- [x] read the version from `testing-service/VERSION` and bump it the way other modules do
- [x] decide whether the module joins release waves: `release-all.yaml` hardcodes the module list and rejects unknown tokens, and `scripts/build-bom.sh` hardcodes both the list and the tag scheme
- [x] verify on a dry run before the first real tag — exercised locally against a throwaway repository; no tag, branch or image left the machine (see below)

[decision] **The module joins release waves.** `release-all.yaml` excludes only `schemas` and `checkstyle`, and its own
comment gives the reason: they are libraries released manually at a rare cadence. testing-service is a deployable
platform service — it sits in `docker-compose.yml`, it has a Helm chart, and that chart pulls
`ghcr.io/netcracker/qubership-integration-testing-service:latest`, which nothing else would ever publish. Leaving it out
would also leave it out of the drop's BOM, since `build-drop-release.sh` renders the release notes from
`build-bom.sh`. It is therefore in `ALL_MODULES`, has a `release-testing-service` job, and is in the `needs` of both
`publish-bom` and `create-drop-release` — the second of which is what makes a failed Go release stop the drop.

[deviation] The version read, the bump, the override and the recovery sentinel come from the shared
`scripts/compute-release-version.sh`, extended with `ECOSYSTEM=go`, rather than from an inline copy in the new workflow.
The plan's file list did not name that script, but "bump it the way other modules do" is exactly what it implements, and
a second copy of the semver arithmetic would drift. The addition is one `case` branch reading `$MODULE/VERSION` and one
conditional for the tag form; the `maven` and `npm` outputs are byte-identical to what they were.

[decision] The tag form is keyed on the ecosystem — `$MODULE/v$release` for `go`, `$MODULE-v$release` otherwise — not on
a `TAG_PREFIX` input. Go's module resolution is the whole reason the form differs, so the ecosystem is the honest
discriminator, and a caller cannot get it wrong by omission.

[decision] `next-dev` stays empty for `go`, as it does for `npm`. It exists so the maven workflow can write the released
version back into `<revision>`; the Go workflow writes `RELEASE_VERSION` into `VERSION` directly, so a second output
carrying the same string would only be one more thing to keep in step.

[decision] No `_go-module-release.yaml` reusable. The maven and npm reusables exist because four and three modules share
them; there is one Go module, and `checkstyle`-style ceremony around a single caller would put the logic one file
further from the thing it releases. `testing-service-release.yaml` is self-contained and still exposes both
`workflow_call` and `workflow_dispatch`, so `release-all` calls it exactly like the others.

[decision] The release job builds and tests before it tags. The maven and npm flows get this for free — a broken module
fails its deploy — but for a Go module the tag *is* the publication, and it is immutable once a module proxy has fetched
it. The dependency allowlist runs there too, for the same reason it runs before `setup-go` on the pull request.

[decision] `publish-image` runs in recovery, unlike the maven reusable's. Maven skips it because recovery has no jar to
download; here the image is built from source, and a recovering run is precisely one that tagged but never reached the
image job, so its released version has no image yet.

[decision] `scripts/build-bom.sh` gains a `tag_prefix` function rather than a second module list. One module departs
from the scheme, and a lookup keyed by module name keeps the departure in one place — the comment above it says why the
slash is there.

✅ Verified locally, since CI was not observed running and nothing was released: `actionlint` and `zizmor` (1.12.1, the
version super-linter ships, under `.github/linters/zizmor.yaml`) are clean on the new workflow and on `release-all.yaml`
and report nothing new across all workflows; `yamllint` under `.github/linters/.yaml-lint.yml` reports only the same
`comments-indentation` warning the existing reusable release workflow already carries; `shellcheck`, `shfmt` and
`editorconfig-checker` are clean on both scripts; and the sanitization check passes over the whole change set. The
version logic was run for real: `ECOSYSTEM=go` reads `0.1.0` from `VERSION` and computes `0.1.1`, `0.2.0`, `1.0.0` and
the override, each with the tag `testing-service/v<version>`, while the maven and npm paths return what they always did.
The slash form was then exercised end to end in a throwaway git repository under the scratchpad — never this one, and no
tag, branch or image left the machine: `build-bom.sh` picks `testing-service/v0.10.0` over `testing-service/v0.4.5`
alongside `engine-v1.2.3`, `git ls-remote` finds the slash tag so recovery mode engages on a re-release of an already
tagged version, and the released version written back into `VERSION` is what the next run bumps from. Against the real
repository `build-bom.sh` reports `"testing-service": null`, which is correct until the first tag.

### Task 20: Verify acceptance criteria

**Files:**
- Modify: `testing-service/cmd/testing-service/main.go`, `testing-service/cmd/testing-service/main_test.go`

- [x] verify all requirements from Overview are implemented
- [x] run `go test ./...`, then `go test -tags integration ./...`
- [x] run `golangci-lint run` and confirm it is clean
- [x] run the sanitization script over the working tree and over `git log -p` for the whole branch; if anything is found, rewrite the branch before it is pushed anywhere public
- [x] confirm the dependency allowlist passes
- [x] bring up the stack and exercise the API with curl: create a test case, list it with filters, sorting and pagination, check `?return_ids=true`, create an endpoint mock, call `/endpoint-mocks/call` with a base64 testing context and assert both a matching mock response and the not-found response
- [x] create a test run and observe cases move pending → running → finished, with errors recorded for a deliberately failing matcher
- [x] start two runs at once and confirm they progress in parallel while cases inside each stay ordered
- [x] inspect PostgreSQL: `testing_service` schema present, `ordinal`, `lease_until` and `lease_owner` populated, the recreated view exposing them, views returning aggregates
- [x] kill the service mid-run and confirm the stranded case returns to `pending` and completes
- [x] apply migration 100 twice against a database that already has the schema and confirm it succeeds
- [x] let retention run against a seeded old run and confirm cascades removed its cases and errors
- [x] open the swagger UI in Chrome and confirm the spec renders and carries no vendor naming

⚠️ **The swagger UI did not render behind the proxy, and this task fixed it.** Task 14 recorded that `fiberswagger`
"honors `X-Forwarded-Prefix`, so it works behind the proxy as well as directly". It does not, for two reasons. The
handler builds the spec URL as `X-Forwarded-Prefix` + its own **internal** route path, and the nginx rule inserts
`/qip/testing-service` in the *middle* of the public path — no prefix value can turn `/api/v1/swagger/doc.json` into
`/api/v1/qip/testing-service/swagger/doc.json`. The library also computes that URL once per process, under a
`sync.Once`, so the first request that arrives decides it for every later one. Through the proxy the UI therefore
fetched `/api/v1/swagger/doc.json`, got the front end's fallback HTML and reported "Unable to render this definition".
The handler is now registered with a **relative** spec URL (`doc.json`), which the browser resolves against the UI page
and which is correct under any prefix. `TestSwaggerAsksForTheSpecRelativeToThePage` locks it in, and
`TestSwaggerServesTheUI` no longer asserts the absolute form it used to.

⚠️ An unknown `sort_by` returns **500**, not 400: `ValidateSortOptions` lives in `dao` and the controllers report every
service error as an internal failure. The body does name the field and list the accepted ones. This is the ported
contract and "keep every path and payload identical" from Task 9 covers it, so it was left alone rather than fixed here.

**Overturned by the second review round.** Validation was tightened after this was written — `ValidateSortOptions` now
requires the order to be `ASC` or `DESC`, where `GetSqlSortingOrder` used to pass anything through — so a client sending
`sort_order=asc` got a 500 for a request that used to work. Filter and sorting rejections now carry
`dao.ErrInvalidSelection`, which the listing handler answers with **400** and the validation message; the body keeps the
`ErrorMessage` shape, and the order is read case-insensitively again.

[decision] The executor checks ran against a second instance of the binary on the host, bound to its own
`testing_service_acc` schema and pointed at a stub catalog and engine, rather than against the compose container. The
container's own catalog and engine are the real runtime-catalog (unhealthy since before this branch, unrelated to it)
and the real engine, and neither carries a chain with a deployed HTTP trigger after the Task 16 database reset. The stub
answers `/v1/chains/{chainId}/elements/{elementId}` with an `http-trigger` element and serves `/routes/...`, with a
`slow-<seconds>` element that holds the trigger open — which is what makes the intermediate states observable at all.
The compose container served every API and routing check unchanged.

[note] `sweepExpiredLeases` calls `NotifyWork` the moment it reclaims, so a reclaimed case is re-claimed within
milliseconds and the `pending` state cannot be sampled from outside. The crash test therefore runs one worker and keeps
it busy with a second run, which widens the window to the length of that run.

✅ Verified end to end. `go test ./...` and `go test -tags integration ./...` pass under a real go1.22.12 with
`GOTOOLCHAIN=local`; `golangci-lint run` (v1.64.8, the pinned version) is clean with and without the build tag; the
dependency allowlist passes. **Sanitization is clean for everything this branch owns**: the 155 files it touches, the
whole `testing-service/` tree including untracked files, `git log -p` over `origin/main..HEAD`, and the commit messages
on their own. The roughly 70 `--tracked` hits noted in Task 1 were confirmed to be 39 pre-existing files under
`engine/`, `micro-engine/`, `runtime-catalog/`, `schemas/`, `ui/` and `infrastructure/.gitignore`, with **no overlap**
against this branch's file list. Against the running stack: test cases create, read, filter, sort, paginate and answer
`?return_ids=true` (which ignores pagination, as the UI expects); bulk delete works; an endpoint mock answers a matching
call with its configured status, body and headers, and a call that matches no mock — whether the element has none or its
matchers fail — is refused with 404 by design; a missing or malformed testing-context header is a 400. A run of two
cases was sampled through `pending`, `running` and `finished`, numbered `ordinal` 1 and 2 in selection order, recorded
exactly one validation error against the failing matcher, and the query parameters reached the trigger — the `buildUrl`
fix, observed on the wire. Two runs started together ran strictly in parallel while each kept its own cases sequential
and in ordinal order. In PostgreSQL both schemas carry 15 domain tables, 3 views, 4 enums and 4 triggers; `ordinal`,
`lease_until` and `lease_owner` exist with the right types, the recreated `test_case_runs_view` exposes all three, the
claim index and the partial `lease_until where status = 'running'` index are both present, a running case carries a
live lease that is renewed under the same owner, and both views return their aggregates. A `SIGKILL` mid-case left the
case `running` under its owner; the sweeper returned it to `pending` with `lease_until`, `lease_owner` and `start`
cleared, logged what it reclaimed, and the case was claimed under a **different** owner and completed with no leftover
validation errors. Migration 100 applies to an empty schema with exactly the object counts in Technical Details, applies
a second time unchanged, applies again after 101 without undoing its columns or its recreated view, and leaves seeded
data intact. Retention with a 7-day age deleted only the aged run whose cases were all finished — the two aged runs
holding a running and a pending case and the recent run all survived — and the cascades took its case runs and its
validation errors, with nothing orphaned and nothing further deleted on later sweeps. The swagger UI renders in Chrome
through this worktree's nginx rule and directly on the service port, with the title `Testing Service API`, no vendor
naming in the served page or spec, and no console errors; `/endpoint-mocks/call` stays refused by the proxy. Route
verification again used a throwaway nginx bound to this worktree's `routes.conf` — the stack's `ui-proxy` mounts the
main checkout's copy and falls through to the front end — and the container was removed afterwards.

### Task 21: [Final] Update documentation

- [x] write `testing-service/AGENTS.md` covering the layout, the public surface, the library-versus-binary split, the migration numbering and transactionality rules, the lease-fencing invariant and the Go tag format — note that `CLAUDE.md` files are **not** in the repository (only `AGENTS.md` is versioned), so anything meant to survive belongs there
- [x] note in the root `AGENTS.md` that the repository now has a Go module and how it differs from the Maven and npm conventions
- [x] document the service and its port alongside the other services, in whichever versioned file carries that table
- [x] move this plan to `docs/plans/completed/` (the harness does this once the review phases are over, so the plan is still here)

⚠️ Every `AGENTS.md` in this repository is generated by `apm compile` from the primitives under `.apm/`, and the repo's
own rules forbid hand-editing one. The plan asks for two things that do not fit that shape.

[decision] `testing-service/AGENTS.md` is written by hand anyway. It is module reference material, not a skill trigger,
and APM has no primitive that produces prose of this kind. No primitive targets `testing-service/**`, so `apm compile`
writes nothing there today; the file says so in its opening paragraph, and a future primitive scoped to that path would
be the thing to reconsider.

[decision] The root note went in twice, on purpose: `.apm/instructions/go-module.instructions.md` (`applyTo: "**"`) is
the source of truth, and the same paragraph is written into the generated root `AGENTS.md` so it is live before anyone
runs `apm compile`. The next compile reproduces it rather than dropping it. The instruction is scoped to `**` and not to
`testing-service/**`, which would have landed in — and overwritten — the hand-written file above.

[decision] No versioned file carries a service-and-port table: `infrastructure/README.md` has none and the port numbers
appear only in `docker-compose.yml`. The table the checkbox means is the repository layout table in the root
`README.md`, so the row went there carrying host port 8095. The same file's Step 4 said "three application images",
which is now four — the fourth builds from Go sources rather than from a JAR — and "Running tests" gained the Go
commands, which is where the Maven and npm conventions visibly stop applying.

[decision] Two words were rephrased to keep super-linter's `textlint-rule-terminology` quiet ("re-exported",
"indexes"). The rule already fails on pre-existing lines of `ui/AGENTS.md`, so it is evidently not blocking, but adding
new hits to a file this branch owns is free to avoid.

## Post-Completion

*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Downstream adoption:**

- the vendor keeps its own migrations 01 and 02; migration 100 is idempotent so it applies cleanly on top, but their
  schema will keep `varchar(255)` where ours uses `text` — functionally equivalent in PostgreSQL
- migration 101 recreates `test_case_runs_view`, an object they already own; flag it in the upgrade notes
- a rolling upgrade is not safe: during the overlap, old pods create case runs with a NULL `ordinal` and claim work
  through the old unfenced two-statement path, racing new pods. Cut over with a single writer
- their wrapper supplies `Deps`: the DBaaS client satisfies `DB` directly, the M2M manager becomes a `RoundTripper`, and
  their logger becomes an `slog.Handler`
- health, metrics, tracing, route registration and security policies stay in their binary

**Release:**

- the first Go tag must be `testing-service/v0.1.0`; the repository's usual tag form will not resolve. `VERSION` holds
  `0.0.0`, the last released version, so cut the first release as a `minor` one to land on `0.1.0`
- for major version 2 and beyond, the module path itself gains a `/v2` suffix

**Legal:**

- license review of the ported code and of the direct Go dependencies (all currently MIT, BSD or Apache-2.0)
