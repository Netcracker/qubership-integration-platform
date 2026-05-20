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
