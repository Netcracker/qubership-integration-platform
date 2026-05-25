# Qubership Integration Platform

Open-source integration and orchestration platform built on Apache Camel. Lets you build integration flows (chains) with data transformation, process orchestration, and mapping between system formats. Deployed on Kubernetes; Docker Compose is used for local development.

This repository is a **monorepo** that consolidates the previously separate `qubership-integration-*` repositories with full git history preserved.

## Repository layout

| Directory | Role | Stack |
|---|---|---|
| `infrastructure/` | Docker Compose, Helm charts, Nginx routing for local development | Docker, Helm, Nginx, Consul |
| `engine/` | Execution engine (Spring Boot variant) | Java 21, Spring Boot 3.5, Apache Camel 4.14, PostgreSQL, OpenSearch, Consul |
| `micro-engine/` | Execution engine (Quarkus variant) — faster startup, lower memory | Java 21, Quarkus 3.27, Apache Camel 4.14 |
| `runtime-catalog/` | Central catalog: chains, elements, deployments, snapshots, specifications, systems, variables | Java 21, Spring Boot 3.5, PostgreSQL, Consul, Flyway |
| `sessions-management/` | Recorded sessions of integration flow executions | Java 21, Spring Boot 3.5, OpenSearch, Consul |
| `ui/` | Web UI — visual flow editor, chain/service management, session monitoring | React 18, TypeScript, Vite, Ant Design 5 |
| `vscode-extension/` | VS Code extension for offline chain/service editing | TypeScript, VS Code Extension API |
| `schemas/` | JSON Schema definitions for chains, services, elements | TypeScript, JSON Schema, Gulp |
| `checkstyle/` | Shared Checkstyle rules (`qip-checkstyle` artifact) | XML, Maven |
| `help/` | Documentation consumed by UI and VS Code extension | Markdown |
| `parent/` | Shared Maven parent POM for Spring Boot modules | Maven |

## Branches

| Branch | Modules |
|---|---|
| `main` | All active modules |
| `release/0.1..release/0.5` | Release branches with historical module sets |
| `feature/#*-core-adaptation`, `feature/#33` | Feature branches with private `com.netcracker.cloud` dependencies (require GitHub Packages auth) |

## Getting started (first-time setup)

End-to-end recipe to go from a fresh clone to a running UI in a browser. Verified on Linux with the tooling listed below; macOS works the same way, Windows users should run the commands from WSL2.

### Prerequisites

| Tool | Required version | Notes |
|---|---|---|
| **JDK** | 21 | Verified with OpenJDK 21.0.10 |
| **Maven** | 3.8+ | Verified with 3.8.7 |
| **Node.js** | 22+ | Enforced by `engines` in `package.json`. Build also works on 18 with `EBADENGINE` warnings, but is not supported. |
| **npm** | 9+ | Bundled with Node 22 |
| **Docker Engine** | 20.10+ | Verified with 28.4.0 |
| **Docker Compose** | v2 | Bundled as the `docker compose` plugin |
| **git** | any recent | Needed at build time — see "Network access" below |

You also need:
- ~5 GB free disk for Maven artifacts, `node_modules`, and Docker images
- ~4 GB free RAM for the running stack (Postgres + OpenSearch + Consul + 3 Spring Boot apps)
- Free local ports: `4200`, `5432`, `8080`, `8091`, `8092`, `8093`, `8500`, `9200`, `9300` (plus `5006`–`5008` for the JDWP debug ports). The compose stack will fail to start if any of these are in use.
- **Network access at build time** — `npm -w @netcracker/qip-ui run build` runs a `prebuild` step that clones `https://github.com/Netcracker/qubership-integration-help.git` into `ui/public/doc/`. An offline build will fail at that step.

### Step 1 — Clone

```bash
git clone https://github.com/Netcracker/qubership-integration-platform.git
cd qubership-integration-platform
```

### Step 2 — Build the Java modules

```bash
mvn clean install -Dgpg.skip=true -DskipTests
```

- `-Dgpg.skip=true` is required locally (GPG signing is configured for release publishing).
- `-DskipTests` is optional — it cuts ~2× off the build time. Drop it if you want the full test suite.
- The `maven-javadoc-plugin` prints `[ERROR] MavenReportException` for `engine` and `micro-engine` because Lombok-generated `*Builder` classes are not visible to `javadoc`. The build still finishes with `BUILD SUCCESS` and the runnable `*-exec.jar` files end up in each module's `target/`.
- Expected time on a warm Maven repo: ~3 minutes.

Per-module rebuild: `mvn -pl engine -am clean install -Dgpg.skip=true -DskipTests`.

### Step 3 — Install npm dependencies, build schemas, build the UI

```bash
npm install
npm -w @netcracker/qip-schemas run build
npm -w @netcracker/qip-ui run build
```

- `npm install` populates the root `node_modules/` plus per-workspace symlinks for `schemas`, `ui`, and `vscode-extension`. Expected time: ~40 s.
- `npm -w @netcracker/qip-schemas run build` resolves `$ref` references in `src/main/resources/qip-model/`, writes `schemas/assets/` (71 resolved YAML files), `schemas/types/` (TypeScript definitions), and `schemas/dist/index.mjs` (runtime `schemasByType` map consumed by the UI). Must run **before** the UI build — without it the UI will start but every chain element form will fail with `Schema not found`. Expected time: ~30 s.
- `npm -w @netcracker/qip-ui run build` first fetches the documentation repo (see "Network access" above), then runs `vite build`. Expected time: ~2–3 minutes; output lands in `ui/dist/`.
- **Do not run the root `npm run build`.** Its third step (`compile-web -w vscode-extension`) is known broken — see `CLAUDE.md` → "Known-broken npm scripts". The two workspace commands above are the supported path.

### Step 4 — Start the local backend stack

```bash
docker compose -f infrastructure/docker-compose.yml up -d --build
```

- Builds three application images (`infrastructure-qip-runtime-catalog`, `-qip-engine`, `-qip-sessions-management`) from the JARs produced in Step 2 and starts them alongside `postgreSQL`, `consul`, `opensearch`, and an `ui-proxy` (Nginx).
- Expected time on a warm Docker cache: ~1–2 minutes for the build, then 1–2 minutes for Spring Boot apps to become healthy.

Wait for all three Spring Boot apps to report `healthy`:

```bash
# Polls every 5 s up to 5 min; exits non-zero on timeout so the recipe doesn't hang silently.
for _ in $(seq 1 60); do
  rc=$(docker inspect --format '{{.State.Health.Status}}' qip-runtime-catalog 2>/dev/null)
  eng=$(docker inspect --format '{{.State.Health.Status}}' qip-engine 2>/dev/null)
  sm=$(docker inspect --format '{{.State.Health.Status}}' qip-sessions-management 2>/dev/null)
  [ "$rc" = "healthy" ] && [ "$eng" = "healthy" ] && [ "$sm" = "healthy" ] && { echo "all healthy"; break; }
  sleep 5
done
[ "$rc" = "healthy" ] && [ "$eng" = "healthy" ] && [ "$sm" = "healthy" ] || { echo "timeout waiting for healthy"; docker compose -f infrastructure/docker-compose.yml ps; exit 1; }
```

Smoke-test the backends:

```bash
curl -s http://localhost:8091/actuator/health   # Runtime Catalog → {"status":"UP"}
curl -s http://localhost:8092/actuator/health   # Engine
curl -s http://localhost:8093/actuator/health   # Sessions Management
curl -s http://localhost:8500/v1/status/leader  # Consul
curl -s http://localhost:9200/_cluster/health   # OpenSearch
```

### Step 5 — Start the UI dev server

```bash
npm -w @netcracker/qip-ui run dev
```

Vite starts on http://localhost:4200 in ~1 s after the dependency cache is warm. The dev server has hot-reload and is the supported way to work on the UI.

Open one of:
- **http://localhost:4200** — direct Vite dev server. Recommended for UI development.
- **http://localhost:8080** — Nginx proxy. Routes `/api/*` to the right backend container and proxies `/` to `host.docker.internal:4200` (the Vite dev server). Returns `HTTP 502` until Step 5 is running.

### Tearing it all down

```bash
docker compose -f infrastructure/docker-compose.yml down       # stop containers, keep volumes
docker compose -f infrastructure/docker-compose.yml down -v    # also drop Postgres/OpenSearch data
```

To start over completely:

```bash
rm -rf node_modules ui/node_modules vscode-extension/node_modules \
       engine/target runtime-catalog/target sessions-management/target \
       micro-engine/target checkstyle/target parent/target \
       schemas/assets schemas/types schemas/dist \
       ui/dist vscode-extension/dist ui/dist-lib
```

## Building

### Java modules (Maven)

```bash
mvn clean install -Dgpg.skip=true
```

Per-module: `mvn -pl engine clean install -Dgpg.skip=true`

Maven artifacts published from each module:

| Module | Coordinates |
|---|---|
| engine | `org.qubership.integration-platform:qip-engine` |
| runtime-catalog | `org.qubership.integration-platform:qip-runtime-catalog` |
| sessions-management | `org.qubership.integration-platform:qip-sessions-management` |
| micro-engine | `org.qubership.integration-platform:qip-micro-engine` |
| checkstyle | `org.qubership.integration-platform:qip-checkstyle` |

### Frontend modules (npm workspaces)

```bash
npm install
npm -w @netcracker/qip-ui run build
```

NPM packages:
- `@netcracker/qip-schemas`
- `@netcracker/qip-ui`
- `@netcracker/qip-vscode-extension`

### Local stack

```bash
docker compose -f infrastructure/docker-compose.yml up -d --build
```

Services exposed:
- Runtime Catalog: `http://localhost:8091`
- Engine: `http://localhost:8092`
- Sessions Management: `http://localhost:8093`
- UI: `http://localhost:4200`
- Nginx proxy: `http://localhost:8080`

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
