# QIP AI Service (`ai-service`)

Quarkus + LangChain4j service for QIP chain design, planning, and catalog implementation. Part of the [qubership-integration-platform](https://github.com/Netcracker/qubership-integration-platform) monorepo.

See [ARCHITECTURE.md](ARCHITECTURE.md) for request flow, scenarios, and configuration.

## Build

QIP element schemas are **not** committed in this module. They are copied from the monorepo [`schemas`](../schemas/) module at build time into `target/classes/qip-schemas/`.

From the repository root (requires resolvable reactor; prefer module directory if other modules fail):

```bash
mvn -pl ai-service --batch-mode verify -Dgpg.skip=true
```

Or:

```bash
cd ai-service && ./mvnw verify -Dgpg.skip=true
```

From this directory:

```bash
./mvnw verify
```

Run `mvn compile` (or `verify`) before `quarkus dev` so classpath schemas are present.

### Code style (Checkstyle)

`verify` runs [QIP Checkstyle](../checkstyle/) on `src/main/java` and `src/test/java` (same rules as `micro-engine`). Import order: third-party (including `jakarta.*`), then `java.*` / `javax.*`, then static imports; case-sensitive alphabetical order; outer types before nested (`Foo` before `Foo.Bar`).

When editing Java, run `./mvnw verify` or `checkstyle:check` before pushing. Optional helper: `python3 scripts/fix-qip-checkstyle.py --imports-only` (imports only) after reformatting.

## Local stack (Docker Compose)

With the platform gateway (`http://localhost:8080`):

```bash
cd infrastructure
docker compose --profile ai up -d --build qip-ai-service
```

Configure secrets in [`ai-service-dev.env`](../infrastructure/ai-service-dev.env). UI uses same-origin gateway by default (`window.location.origin`); override with `VITE_AI_SERVICE_URL` for direct access (e.g. `http://localhost:8094`).

### Schemas outside the monorepo

To build standalone without a sibling `schemas/` tree, use the download profile:

```bash
mvn verify -Pschemas-from-download
```

## Docker

```bash
mvn -pl ai-service -DskipTests package
docker build -f ai-service/Dockerfile ai-service
```

Requires `ai-service/target/quarkus-app/` from the Maven package step.
