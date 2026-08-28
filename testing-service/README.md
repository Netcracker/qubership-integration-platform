# Testing service

Test integration chains without calling them by hand. The service stores three kinds of object:

- **test cases** — a reference to a chain element, the request to send it, and the matchers the response must satisfy;
- **endpoint mocks** — a reference to a chain element, the matchers an incoming request must satisfy, and the response
  to answer with;
- **test runs** — a queue of selected test cases and the background executor that works through it.

Nine matching predicates back both the response matchers and the mock selection, and test cases, mocks and their
matchers travel between installations as a ZIP archive.

This is the first Go module in the repository. It runs two ways: as the standalone binary that ships with the platform
stack, and as a library that a host application wires into its own server.

## Run it standalone

The binary needs a reachable PostgreSQL: it creates its schema and applies its migrations before it starts listening,
and exits with an error if it cannot connect. The local stack in `infrastructure/docker-compose.yml` starts one, along
with the service itself as `qip-testing-service` on host port 8095.

To run it from the source tree, point the DSN at a database of your own:

```bash
cd testing-service
QIP_TESTING_POSTGRES_DSN='postgres://postgres:postgres@localhost:5432/postgres?sslmode=disable&search_path=testing_service' \
  go run ./cmd/testing-service
```

The binary reads `application.yaml` — `-config` points it elsewhere — serves the API under `/api/v1`, and exposes
`/health` and `/prometheus`. The OpenAPI spec is at `/api/v1/swagger/doc.json` and the browsable UI at
`/api/v1/swagger/index.html`. The same spec is also served at `/v2/api-docs`, with its own UI at
`/swagger-ui/index.html`, and `/api-version` answers with the API version document; these three sit outside `/api/v1`,
so only a caller reaching the service directly gets them. Set `pprof.enabled` to serve the profiles on their own port.

`GET /api/v1/mode` reports whether this is a live installation, and the frontend hides the operations that are unsafe
there while it says so. An installation that names no mode is a live one. `PRODUCTION_MODE` is the flag every service
of the platform reads, so a sandbox sets `PRODUCTION_MODE=false` once rather than naming this service; `production:
false` in the file, or `QIP_TESTING_PRODUCTION=false`, overrides it for this service alone.

Every other key can be overridden from the environment: uppercase it, replace the dots with underscores and prefix it
with `QIP_TESTING_`. `QIP_TESTING_POSTGRES_DSN` sets `postgres.dsn`, `QIP_TESTING_EXECUTION_WORKERS` sets
`execution.workers`. `postgres.schema` names the schema the binary creates, and the `search_path` of the DSN has to
point at the same one.

The DSN is parsed as a URL, so a `#`, `/` or `?` in a username or a password has to be percent-encoded inside it. Set
`postgres.user` and `postgres.password` instead — `QIP_TESTING_POSTGRES_USER` and `QIP_TESTING_POSTGRES_PASSWORD` from
the environment — and leave the credentials out of the DSN. They reach the driver verbatim, need no encoding, and
override whatever the DSN carries. This is what the Helm chart does: kubelet expands `$(POSTGRES_URL)` into the DSN but
cannot encode a secret it expands.

The PostgreSQL driver caps the response to a single statement at 10 seconds out of the box, whatever deadline the caller
set. That is short enough to fail the startup migrations on an installation with test runs already in it, which leaves
the pod crash-looping on an `i/o timeout` that names no statement. The service raises the socket deadlines to five
minutes for reads and one minute for writes, so the context of the caller is what bounds a statement. An installation
that needs other values appends `read_timeout` and `write_timeout` to the DSN, and those win over the defaults:

```text
postgres://USER:PASSWORD@HOST:5432/DATABASE?sslmode=disable&search_path=testing_service&read_timeout=10m
```

## Use it as a library

The host application owns the database connection, the logger, the HTTP client and the identity of the caller, and
passes them in:

```go
import (
	testingservice "github.com/Netcracker/qubership-integration-platform/testing-service"
)

svc, err := testingservice.New(
	testingservice.Config{
		CatalogAddress: "http://runtime-catalog:8080",
		EngineAddress:  "http://engine:8080",
	},
	testingservice.Deps{
		DB:          pgClient, // any GetBunDb(ctx) (*bun.DB, error)
		Logger:      logger,
		HTTPClient:  authorizedClient,
		CurrentUser: currentUser,
	},
)
if err != nil {
	return err
}

svc.Mount(app.Group("/api/v1"))
go func() { _ = svc.RunExecutor(ctx) }()
```

`Config` carries no DSN: the host connects to the database itself and hands over a `DB`. Authorization rides on
`Deps.HTTPClient` as an `http.RoundTripper`. A host that applies migrations through its own tooling gets the set from
`testingservice.Migrations()` instead of letting the binary apply them.

`Config.EngineAddress` is a fallback rather than the address every test case uses. A chain is activated on the engine
the catalog reports it deployed to, which is what makes a chain on a micro domain testable: such a domain runs behind
a Kubernetes service of its own, and the configured address answers for none of them. Only the scheme and the port of
this setting are reused for a resolved engine, so an installation that moves its engines to another port names it
here. A chain the catalog reports nowhere — and a catalog that cannot answer — falls back to this address whole.

`Config.Production` is a `*bool`, because a host that leaves it unset gets production mode: a nil there is not the same
answer as `false`. Read it back through `Config.ProductionMode()`, which resolves the nil.

Migrating over an installation that already runs test cases needs a single writer: **stop the executor that ran them
before you migrate**. Migration 101 re-queues every case left `running` and deletes that attempt's validation errors,
and it cannot fence a writer that predates its lease column. A rolling upgrade runs an in-flight case a second time
against the live chain and loses what the first attempt recorded.

`Mount` registers relative paths, so the prefix is the host's. The generated spec under `docs/` does not follow it:
every path there is written out under `/api/v1`, which is where the standalone binary mounts, so a host that picks
another prefix publishes a spec of its own.

Health, metrics, tracing and route registration stay in the host. The module registers routes and runs the executor,
and nothing else.

## Develop

```bash
go test ./...                    # no Docker needed
go test -tags integration ./...  # PostgreSQL through testcontainers
golangci-lint run                # pinned to v1.64.8
go generate ./...                # regenerate docs/ after changing an annotation
```

`docs/` is generated from the swagger annotations on `(*Controllers).Mount` and on the handlers around it. Edit the
annotations, not the generated files.

The `go` directive stays at 1.22, because the downstream build pins `GOTOOLCHAIN=local` to a 1.22 toolchain. That binds
dependencies too: `github.com/uptrace/bun` is held at v1.2.1 because v1.2.18 declares `go 1.24`. Check the directive of
anything you add or upgrade.
