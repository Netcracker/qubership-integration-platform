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
  go.mod  go.sum  .golangci.yml  VERSION  Dockerfile  README.md  CLAUDE.md
  service.go          package testingservice — aliases, New, Mount, RunExecutor
  migrations.go       Migrations(), go:embed
  migrations/         00000000000100__init.tx.up.sql, 00000000000101__execution.tx.up.sql
  cmd/testing-service/main.go
  docs/               swaggo-generated
  internal/
    config/ model/ matching/ dao/ db/ services/ controllers/ triggers/ qip/ testsupport/
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
            order by ordinal
            for update skip locked limit 1)
returning *;
```

If step 2 returns nothing — the run's last pending case was canceled between the two steps — release and try the next
run immediately rather than waiting out a poll interval.

**Lease fencing.** A lease with no owner is unsafe. Consider a stalled (not crashed) worker A holding case 1 of run R:
the sweeper returns case 1 to `pending`, worker B legitimately claims case 2, and then A finishes — and a `Finish`
guarded only by `status = 'running'` would happily overwrite **B's** row. `lease_owner` is the fence, and it must guard
**every** write a worker makes about its case: `Finish`, `Skip`, lease renewal, and the recording of validation errors.
Fencing only `Finish` leaves a zombie worker writing errors against someone else's attempt.

The sweep itself is one guarded statement — `update … set status = 'pending', lease_owner = null where status =
'running' and lease_until < now()` — not a select followed by an update by id. PostgreSQL rechecks the qualifier at
write time, so a single statement cannot steal a lease that was renewed concurrently, whereas the two-statement form
can. The sweeper does not need the `tests_runs` row lock: every transition *into* `running` goes through the claim,
which evaluates its guard in the same statement that locks the run, and the sweep only ever decreases the number of
running cases.

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
rule in Task 17 assumes.

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
- [x] create `VERSION` with `0.1.0` and a `README.md` describing both usage modes
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
`result[0]` after a nil check, so an empty non-nil slice — what a re-used buffer yields — panics.

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

[decision] `Runner` keeps the erased signature (`func(ctx, bun.IDB) (any, error)`) and `*Dao` implements it; the
generics sit on top as `Run[T]`/`RunInTx[T]`. A generic interface would have forced one `Runner[T]` field per result
type in every service.

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

[decision] `New` builds the pool eagerly and `GetBunDb` returns the stored handle, so no mutex is needed. The source
built a fresh `bun.DB` wrapper on every call and guarded it with an `RWMutex`.

[decision] The pool is capped at 16 connections by default. `database/sql` leaves it unlimited, which turns a burst of
executor workers into a burst of PostgreSQL backends.

[deviation] `github.com/uptrace/bun/dialect/pgdialect` and `github.com/uptrace/bun/driver/pgdriver`, both v1.2.1, join
`go.mod` here rather than with the binary in Task 13, because `internal/db` needs them. They pull in
`golang.org/x/crypto v0.21.0` and `mellium.im/sasl v0.3.1`; every one of the four declares `go 1.21` or earlier, so the
1.22 ceiling holds. Task 18's dependency allowlist has to admit the `mellium.im/` prefix.

✅ Verified against PostgreSQL 14 in Docker: migration 100 applies to an empty schema, applies a second time cleanly,
and applies on top of a schema already carrying the source's migrations 01 and 02. Object counts after the first apply
match the plan exactly — 15 tables, 8 indexes, 4 enums, 3 views, 2 functions, 4 triggers — and the statement triggers
cascade correctly under a schema not named `testing_service`.

### Task 7: Platform clients and the HTTP trigger

**Files:**
- Create: `testing-service/internal/qip/*.go`, `testing-service/internal/triggers/*.go`
- Create: `testing-service/internal/triggers/http_trigger_test.go`

- [ ] port the catalog and engine clients, dropping the M2M token calls — authorization rides on `Deps.HTTPClient`
- [ ] point defaults at platform service names and pass the real `chainId` instead of the placeholder the source used
- [ ] fix `resolvePathParameters`: strip the braces from the name and substitute the value
- [ ] fix `buildUrl`: populate the query string from `requestSettings.QueryParameters`
- [ ] move the per-case timeout from a per-activation client to `context.WithTimeout`, since the client is now shared, and build the request with `NewRequestWithContext`
- [ ] write tests for path substitution (single, multiple, missing parameter), query parameters (none, one, repeated), and timeout cancellation
- [ ] run `go test ./internal/triggers/...` - must pass before next task

### Task 8: Services

**Files:**
- Create: `testing-service/internal/services/*.go`, `testing-service/internal/services/importexport/*.go`

- [ ] port every service — test cases, endpoint mocks, matchers, test runs, test case runs, run errors, trigger resolution, execution — plus zip import/export
- [ ] replace config-loader reads with `Config` fields and delete the configuration package
- [ ] replace the vendor logging calls in this package (17 of the 43, all in the execution service) with `slog`
- [ ] depend on the runner and the repository interfaces, and drop the `result.(*[]dao.X)` assertions the generics replace
- [ ] write tests for mock selection order (most enabled matchers first, then oldest) using fakes
- [ ] run `go test ./...` - must pass before next task

### Task 9: Controllers and the facade

**Files:**
- Create: `testing-service/internal/controllers/**/*.go`, `testing-service/service.go`

- [ ] port the controllers and their pagination, sorting and response helpers, keeping `GetEndpointReference` alongside its only caller
- [ ] register routes in `(*Service).Mount(router fiber.Router)` together with middleware that resolves the current user into the request context
- [ ] read the production-mode flag from `Config` in the mode controller
- [ ] keep every path and payload identical, including `?return_ids=true` on the list endpoints, which the UI relies on
- [ ] create the root `service.go` in package `testingservice` with `New`, `Mount`, `RunExecutor` and the public aliases
- [ ] update swagger annotations, removing the vendor product name
- [ ] write tests mounting the service on a bare fiber app and asserting routing, status codes and that the user middleware populates the context
- [ ] run `go test ./...` - must pass before next task

### Task 10: Migration 101 and the atomic claim

**Files:**
- Create: `testing-service/migrations/00000000000101__execution.tx.up.sql`
- Modify: `testing-service/internal/dao/test_case_runs_repository.go`, `testing-service/internal/services/test_case_runs_service.go`, `testing-service/internal/services/tests_runs_service.go`

- [ ] add `ordinal`, `lease_until` and `lease_owner`, and backfill `ordinal` for existing rows
- [ ] add the `(tests_run_id, status, ordinal)` index and the partial `lease_until` index
- [ ] drop and recreate `test_case_runs_view` so the new columns reach the list API — `create or replace` cannot do it
- [ ] assign `ordinal` when a test run is created, in the order the cases were selected
- [ ] implement the two-step claim from Technical Details, stamping `lease_owner`
- [ ] when step 2 finds nothing, move on to the next run instead of waiting a poll interval
- [ ] fence every worker write on `lease_owner` — `Finish`, `Skip` and the recording of validation errors, not just `Finish`
- [ ] write tests for ordinal assignment and for the fenced writes against fakes
- [ ] run `go test ./...` - must pass before next task

### Task 11: Workers, lease sweeper and shutdown

**Files:**
- Modify: `testing-service/internal/services/test_execution_service.go`, `testing-service/service.go`, `testing-service/go.mod`

- [ ] run a configurable pool of workers, each claiming its own case with its own owner token
- [ ] wake workers by signal when a run is created, keeping the ticker as a fallback
- [ ] renew the lease while a case runs, guarded by the owner token
- [ ] add a sweeper that reclaims expired leases in **one** guarded statement (`where status = 'running' and lease_until < now()`), clearing `lease_owner` and deleting the attempt's validation errors; a select-then-update-by-id form can steal a lease renewed between the two statements
- [ ] replace the `quit` channel with a context so shutdown no longer blocks until the queue drains
- [ ] run `go mod tidy`
- [ ] write tests for lease renewal, expiry selection, owner-token rejection, and that a reclaimed case re-executes cleanly rather than colliding with its previous attempt's validation errors
- [ ] run `go test ./...` - must pass before next task

### Task 12: Run retention

**Files:**
- Modify: `testing-service/internal/services/tests_runs_service.go`, `testing-service/internal/config/config.go`, `testing-service/service.go`
- Create: `testing-service/internal/services/retention_test.go`

- [ ] add retention settings to `Config` (age threshold and sweep interval; disabled when the threshold is zero)
- [ ] delete by `tests_runs.created_at` and let the existing cascades remove case runs and validation errors — `test_case_runs` has no creation timestamp of its own
- [ ] exclude runs that still have `pending` or `running` cases, so a long or stuck run is never deleted out from under a worker
- [ ] batch the deletion so a large backlog does not hold a long transaction, and start the sweep from `RunExecutor`
- [ ] write tests for threshold behavior, batching, the active-run exclusion, and the disabled case
- [ ] run `go test ./...` - must pass before next task

### Task 13: Standalone binary

**Files:**
- Create: `testing-service/cmd/testing-service/main.go`, `testing-service/application.yaml`

- [ ] read configuration with koanf from `application.yaml` plus environment overrides; the DSN lives here, not in `Config`
- [ ] construct `slog`, the bun-backed `DB`, an `http.Client`, and a `CurrentUser` defaulting to `developer`
- [ ] create the schema before initializing the migrator: bun creates its bookkeeping table first, and with `search_path` pointing at a schema that does not exist yet the initialization fails
- [ ] mount the service under `/api/v1`, which the nginx rule depends on, and start `RunExecutor`
- [ ] serve `/health` for the compose healthcheck and `/prometheus`; keep pprof behind a flag
- [ ] handle SIGINT and SIGTERM with graceful shutdown of server and executor
- [ ] write tests for configuration precedence and the health handler
- [ ] run `go test ./...` - must pass before next task

### Task 14: Swagger docs

**Files:**
- Create: `testing-service/docs/*.go`
- Modify: `testing-service/cmd/testing-service/main.go`, `testing-service/.golangci.yml`, `.github/super-linter.env`

- [ ] generate and commit the `docs/` package, pointing the generate directive at the new annotation location
- [ ] confirm the generated spec carries no vendor product names
- [ ] serve the spec and swagger UI from the binary
- [ ] exclude the generated package from both the module's linter config and the repository's super-linter exclusions
- [ ] run `go mod tidy` now that every import finally exists — koanf arrives with the binary in Task 13 and swagger with this task, so an earlier tidy would have stripped them — and verify the dependency set still builds under directive `go 1.22`
- [ ] run `go build ./...` and the linter - must pass before next task

### Task 15: Integration tests

**Files:**
- Create: `testing-service/internal/testsupport/postgres.go`
- Create: `testing-service/internal/db/migrations_integration_test.go`, `testing-service/internal/services/execution_integration_test.go`

- [ ] add `testcontainers-go` **pinned to a release that still supports go 1.22** — current versions require 1.23 and `go mod tidy` would raise the directive for the downstream too
- [ ] put every integration test behind `//go:build integration`
- [ ] test that migration 100 applies to an empty database and that applying it twice succeeds
- [ ] test that migration 101 backfills `ordinal` for rows created before it and that the recreated view exposes the new columns
- [ ] test the claim under concurrency: N workers against M pending cases, asserting no case is claimed twice
- [ ] test that two runs progress concurrently while cases inside one run stay sequential and ordered
- [ ] test lease expiry returning a stranded case to `pending`, that the stalled worker's later writes are rejected by the owner token, and that the reclaimed case re-executes without colliding with the previous attempt's validation errors
- [ ] test that retention leaves a run with pending or running cases alone
- [ ] run `go test -tags integration ./...` - must pass before next task

### Task 16: Container image and the local stack

**Files:**
- Create: `testing-service/Dockerfile`
- Modify: `infrastructure/docker-compose.yml`, `infrastructure/nginx/routes.conf`

- [ ] write a multi-stage Dockerfile on public images, uid 10001, port 8080, curl for the healthcheck; run only the default test suite during build, not the integration-tagged one
- [ ] add `qip-testing-service` with the alias `testing-service` on host port 8095 — 8094 is taken by the commented-out AI assistant block
- [ ] do not make the engine depend on this service; the dependency would form a cycle
- [ ] add the nginx location for `^/api/(v\d+)/.*/testing-service`, remembering this service serves `/api/v1/*` while the Java modules serve `/v1/*`
- [ ] exclude `/endpoint-mocks/call` from the public routes — only the engine calls it, from inside the network
- [ ] verify the stack starts and the container reports healthy

### Task 17: Helm chart and the Kubernetes route

**Files:**
- Create: `infrastructure/qip-dev/charts/qip-testing-service/Chart.yaml`, `.../templates/*.yaml`
- Modify: `infrastructure/qip-dev/values.yaml`, `infrastructure/qip-dev/charts/ui/templates/config.yaml`

- [ ] model the chart on `qip-sessions-management`
- [ ] add the route to the UI chart's routing config — it is a second, independent copy of the nginx table, and without it the Kubernetes deployment has no path to the service
- [ ] guard the templates on a values flag so the chart can be left out; note that no existing chart here does this, so there is no in-repo pattern to copy
- [ ] pass catalog and engine addresses, the DSN and worker settings through the config map
- [ ] verify with `helm lint` and `helm template`, with the flag both on and off

### Task 18: CI

**Files:**
- Create: `.github/workflows/testing-service-build.yaml`
- Modify: `.github/workflows/main-build.yaml`

- [ ] add a build workflow path-filtered on `testing-service/**` running `go build`, `go vet`, `golangci-lint` and `go test`
- [ ] add the dependency-allowlist step over `go.mod` and `go.sum`
- [ ] add a Go job to `main-build.yaml` **and extend that workflow's own `paths:` filter**, or a testing-service-only change will never trigger it
- [ ] check how `super-linter.yaml` reacts to Go files — it pulls a shared config and may run a second, differently configured Go linter
- [ ] leave Sonar out of the Go pipeline for now
- [ ] verify the workflow actually runs on a draft PR touching only this module

### Task 19: Release workflow

**Files:**
- Create: `.github/workflows/testing-service-release.yaml`
- Modify: `.github/workflows/release-all.yaml`, `scripts/build-bom.sh`

- [ ] publish the image and tag as `testing-service/vX.Y.Z` — Go resolves nested modules only from tags prefixed with the module subdirectory
- [ ] read the version from `testing-service/VERSION` and bump it the way other modules do
- [ ] decide whether the module joins release waves: `release-all.yaml` hardcodes the module list and rejects unknown tokens, and `scripts/build-bom.sh` hardcodes both the list and the tag scheme
- [ ] verify on a dry run before the first real tag

### Task 20: Verify acceptance criteria

- [ ] verify all requirements from Overview are implemented
- [ ] run `go test ./...`, then `go test -tags integration ./...`
- [ ] run `golangci-lint run` and confirm it is clean
- [ ] run the sanitization script over the working tree and over `git log -p` for the whole branch; if anything is found, rewrite the branch before it is pushed anywhere public
- [ ] confirm the dependency allowlist passes
- [ ] bring up the stack and exercise the API with curl: create a test case, list it with filters, sorting and pagination, check `?return_ids=true`, create an endpoint mock, call `/endpoint-mocks/call` with a base64 testing context and assert both a matching mock response and the not-found response
- [ ] create a test run and observe cases move pending → running → finished, with errors recorded for a deliberately failing matcher
- [ ] start two runs at once and confirm they progress in parallel while cases inside each stay ordered
- [ ] inspect PostgreSQL: `testing_service` schema present, `ordinal`, `lease_until` and `lease_owner` populated, the recreated view exposing them, views returning aggregates
- [ ] kill the service mid-run and confirm the stranded case returns to `pending` and completes
- [ ] apply migration 100 twice against a database that already has the schema and confirm it succeeds
- [ ] let retention run against a seeded old run and confirm cascades removed its cases and errors
- [ ] open the swagger UI in Chrome and confirm the spec renders and carries no vendor naming

### Task 21: [Final] Update documentation

- [ ] write `testing-service/AGENTS.md` covering the layout, the public surface, the library-versus-binary split, the migration numbering and transactionality rules, the lease-fencing invariant and the Go tag format — note that `CLAUDE.md` files are **not** in the repository (only `AGENTS.md` is versioned), so anything meant to survive belongs there
- [ ] note in the root `AGENTS.md` that the repository now has a Go module and how it differs from the Maven and npm conventions
- [ ] document the service and its port alongside the other services, in whichever versioned file carries that table
- [ ] move this plan to `docs/plans/completed/`

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

- the first Go tag must be `testing-service/v0.1.0`; the repository's usual tag form will not resolve
- for major version 2 and beyond, the module path itself gains a `/v2` suffix

**Legal:**

- license review of the ported code and of the direct Go dependencies (all currently MIT, BSD or Apache-2.0)
