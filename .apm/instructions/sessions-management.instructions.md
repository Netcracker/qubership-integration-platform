---
description: "Sessions management service: architecture over OpenSearch, build, and configuration."
applyTo: "sessions-management/**"
---

### Project Overview

Spring Boot 3.5 microservice that manages recorded sessions of integration flow executions stored in OpenSearch. Part of the Qubership Integration Platform (QIP). Provides REST APIs for searching, filtering, importing, and exporting session data.

### Build & Test Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Build with tests
mvn clean verify

# Run a single test class
mvn test -Dtest=TimeUtilsTest

# Run a single test method
mvn test -Dtest=TimeUtilsTest#testMethod

# Generate executable JAR (produces target/qip-sessions-management-*-exec.jar)
mvn clean package spring-boot:repackage -DskipTests

# Code coverage report (target/site/jacoco/)
mvn verify
```

Java 21 is required. Checkstyle is enforced with zero violations allowed.

### Architecture

**Stack:** Spring Boot 3.5 + OpenSearch (data store) + Consul (config) + MapStruct (DTO mapping) + Lombok

**Entry point:** `SessionManagementRunner.java` — standard Spring Boot app with `@EnableScheduling`.

**Package structure** (`org.qubership.integration.platform.sessions`):

- `controller/` — REST endpoints under `/v1/sessions` (SessionController, ExportController, ImportController)
- `service/` — Business logic (SessionService, ImportService, ExportService, CatalogInternalService)
- `dto/` — API contracts. `dto/filter/` for search filters, `dto/opensearch/` for OpenSearch document models
- `mapper/` — MapStruct mappers converting between OpenSearch documents (`SessionElementElastic`) and API DTOs (`Session`, `SessionElement`)
- `configuration/` — Spring auto-configurations including `opensearch/OpenSearchAutoConfiguration` for OpenSearch client setup
- `configuration/opensearch/` — OpenSearch client wiring: `OpenSearchStandaloneAutoConfiguration` and `OpenSearchDefaultAutoConfiguration` pick the client for the deployment mode
- `properties/` — `@ConfigurationProperties` records for OpenSearch, sessions bulk config, internal services
- `exception/` — `GlobalExceptionHandler` with custom exceptions mapping to HTTP status codes

**Data flow:** OpenSearch stores `SessionElementElastic` documents in index `qip-elements-{namespace}-session-elements`. `SessionService` aggregates elements by sessionId using OpenSearch collapse with inner_hits. MapStruct mappers convert to API DTOs.

**Key domain concepts:**
- **Session** — complete record of an integration flow execution (metadata + list of elements)
- **SessionElement** — individual step within a session (recursive tree structure with body/headers/properties before & after)
- **Chain** — integration flow definition; chain names resolved from Runtime Catalog service

**Import pipeline:** JSON file → validate for duplicates → mark as imported → convert to `SessionElementElastic` → batched bulk write to OpenSearch (configurable thresholds: element count >5 or size >4MB).

### External Dependencies

- **OpenSearch** (port 9200) — primary data store, configured via `OPENSEARCH_*` env vars
- **Consul** (port 8500) — externalized configuration at `config/{NAMESPACE}/`
- **Runtime Catalog service** (`qubership-integration-platform-runtime-catalog`) — resolves chainId → chainName via `/v1/chains/names`
- **OpenTelemetry collector** (optional) — distributed tracing with B3 Multi propagation

### Configuration

Main config: `src/main/resources/application.yaml`. Development profile: `application-development.yml` (enables DEBUG logging, makes Consul optional).

Key environment variables: `OPENSEARCH_HOST`, `OPENSEARCH_PORT`, `OPENSEARCH_USERNAME`, `OPENSEARCH_PASSWORD`, `CONSUL_URL`, `NAMESPACE`, `TRACING_ENABLED`.

### CI/CD

GitHub Actions workflows in `.github/workflows/`:
- `maven-build.yaml` — PR builds with SonarQube analysis
- `pr-conventional-commits.yaml` — enforces Conventional Commits format
- Checkstyle enforced via `qip-checkstyle` ruleset (zero violations)

### Conventions

- Conventional Commits required for PR titles/commits
- Checkstyle strictly enforced (max violations = 0)
- MapStruct for all DTO conversions (not manual mapping)
- Properties defined as Java records in `properties/` package
- CLA required for contributions

### Platform Context

This service handles **session observability** in the Qubership Integration Platform (QIP). See `README.md` for the repository layout.

#### Direct Dependencies (this service consumes)

| Service | Protocol | What For |
|---|---|---|
| **OpenSearch** | HTTP (`OPENSEARCH_*` env vars) | Primary data store. Sessions stored in index `qip-elements-{namespace}-session-elements`. |
| **Runtime Catalog** | REST (`GET /v1/chains/names`) | Resolve chainId → chainName for display in session listings |
| **Consul** | REST | Externalized configuration at `config/{NAMESPACE}/` |

#### Services That Depend on This

| Service | How |
|---|---|
| **UI** | Calls session search/export/import APIs via Nginx proxy (`/api/*/sessions-management/*`) |

#### Data Flow

Engine (and Micro Engine) write session elements directly to OpenSearch during chain execution. This service reads the same OpenSearch index to provide search, filtering, and export capabilities. There is no direct communication between Engine and Sessions Management — they share data through OpenSearch.
