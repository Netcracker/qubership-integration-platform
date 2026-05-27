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

## Getting started

End-to-end recipe to go from a fresh clone to a running platform.

### Step 1 — Build the Java modules

```bash
mvn clean install "-Dgpg.skip=true" "-DskipTests"
```

- `-Dgpg.skip=true` is required locally (GPG signing is configured for release publishing).
- `-DskipTests` is optional — it cuts ~2x off the build time. Drop it if you want the full test suite.

Per-module rebuild: `mvn -pl engine -am clean install -Dgpg.skip=true -DskipTests`.

### Step 2 — Install npm dependencies and build schemas

```bash
npm install
npm -w @netcracker/qip-schemas run build
```

- `npm install` populates the root `node_modules/` plus per-workspace symlinks for `schemas`, `ui`, and `vscode-extension`.
- `npm -w @netcracker/qip-schemas run build` resolves `$ref` references, generates TypeScript types, and writes `schemas/dist/index.mjs` (runtime `schemasByType` map). Must run **before** building the UI or VS Code extension.

### Step 3 — Build and run the UI

```bash
npm -w @netcracker/qip-ui run build
```

The `prebuild` step clones the [help repository](https://github.com/Netcracker/qubership-integration-help.git) into `ui/public/doc/`, so network access is required.

### Step 4 — Start the local backend stack

```bash
docker compose -f infrastructure/docker-compose.yml up -d --build
```

Builds three application images from the JARs produced in Step 1 and starts them alongside PostgreSQL, Consul, OpenSearch, and an Nginx proxy.

If your integration chains use Kafka, RabbitMQ, Redis, or Google Pub/Sub, start the corresponding optional service alongside the main stack:

```bash
docker compose -f infrastructure/docker-compose.yml -f infrastructure/docker-compose.kafka.yml up -d --build
```

Available overlays: `docker-compose.kafka.yml`, `docker-compose.rabbitmq.yml`, `docker-compose.redis.yml`, `docker-compose.pubsub.yml`.

### Step 5 — Start the UI dev server

```bash
npm -w @netcracker/qip-ui run dev
```

- **http://localhost:4200** — direct Vite dev server with hot-reload. Recommended for UI development.
- **http://localhost:8080** — Nginx proxy. Routes `/api/*` to backends and proxies `/` to the Vite dev server.

### VS Code extension

The extension provides offline visual editors for `.chain.qip.yaml` and `.service.qip.yaml` files. It does not require the backend stack.

```bash
npm install                                           # if not done already
npm -w @netcracker/qip-vscode-extension run build     # builds schemas -> UI library -> extension
```

Then open the `vscode-extension/` directory in VS Code and press **F5** to launch the extension in debug mode.

Alternatively, run in a browser:

```bash
npm -w @netcracker/qip-vscode-extension run run-in-browser
```

## Running tests

```bash
mvn test -Dgpg.skip=true                         # Java tests (all modules)
mvn -pl engine test -Dgpg.skip=true              # Java tests (single module)
npm test --workspaces --if-present                # Frontend tests (schemas, ui, vscode-extension)
npm -w @netcracker/qip-schemas test               # Schema conformance tests (AJV)
npm -w @netcracker/qip-ui test                    # UI unit tests (Jest)
npm -w @netcracker/qip-vscode-extension test      # Extension unit tests (Jest)
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
