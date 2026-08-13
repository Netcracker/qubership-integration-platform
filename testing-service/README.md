# Testing service

Test integration chains without calling them by hand. The service stores three kinds of object:

- **test cases** — a reference to a chain element, the request to send it, and the matchers the response must satisfy;
- **endpoint mocks** — a reference to a chain element, the matchers an incoming request must satisfy, and the response
  to answer with;
- **test runs** — a queue of selected test cases and the background executor that works through it.

Nine matching predicates back both the response matchers and the mock selection, and test cases, mocks and their
matchers travel between installations as a zip archive.

This is the first Go module in the repository. It runs two ways: as the standalone binary that ships with the platform
stack, and as a library that a host application wires into its own server.

## Run it standalone

```bash
cd testing-service
go run ./cmd/testing-service
```

The binary reads `application.yaml` and environment overrides, applies its migrations, serves the API under `/api/v1`,
and exposes `/health` and `/prometheus`. In the local stack it comes up as `qip-testing-service` on host port 8095; see
`infrastructure/docker-compose.yml`.

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

Health, metrics, tracing and route registration stay in the host. The module registers routes and runs the executor,
and nothing else.

## Develop

```bash
go test ./...                    # no Docker needed
go test -tags integration ./...  # PostgreSQL through testcontainers
golangci-lint run                # pinned to v1.64.8
```

The `go` directive stays at 1.22, because the downstream build pins `GOTOOLCHAIN=local` to a 1.22 toolchain. That binds
dependencies too: `github.com/uptrace/bun` is held at v1.2.1 because v1.2.18 declares `go 1.24`. Check the directive of
anything you add or upgrade.

Every commit passes the sanitization gate, which refuses vendor-internal identifiers. Install it once per clone:

```bash
../scripts/check-sanitization.sh --install-hook
```

The gate reads its token list from `QIP_SANITIZATION_TOKENS`, a file kept outside the repository, and fails when that
file is missing rather than passing silently.
