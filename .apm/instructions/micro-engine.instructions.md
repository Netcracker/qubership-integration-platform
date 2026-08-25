---
description: "Quarkus execution engine: build commands, architecture, and private dependencies."
applyTo: "micro-engine/**"
---

### Project Overview

`qip-micro-engine` is the Quarkus variant of the QIP execution engine: it receives chain deployments from Runtime Catalog (via Consul), dynamically builds Apache Camel contexts/routes, runs integration chains, and records session traces to OpenSearch — same domain and API contracts as the Spring Boot `engine`, but on Quarkus for faster startup and lower memory in cloud-native deployments.

Stack: Java 21, Quarkus 3.33.2 (`quarkus-bom` + `quarkus-camel-bom`), Apache Camel 4.18.2 (via `camel-quarkus-*` extensions), Camel K runtime 3.15.3, Hibernate ORM Panache + Flyway on PostgreSQL, OpenSearch (`opensearch-java` 2.14.0), Vert.x Consul client, SmallRye (REST/OpenAPI/Health), Micrometer + OpenTelemetry, MapStruct 1.5.5 + Lombok 1.18.42.

### Build & Test Commands

```bash
# Single-module build from repo root (preferred; -am pulls parent + checkstyle)
mvn -pl micro-engine -am clean install -Dgpg.skip=true

# Building requires GitHub Packages credentials in ~/.m2/settings.xml (server id: github,
# a PAT with read:packages on maven.pkg.github.com/Netcracker/*) — the private
# com.netcracker.cloud.* deps (cloud-core, maas-client, dbaas, blue-green) are NOT on Central.

# Quarkus dev mode (live reload)
mvn -pl micro-engine quarkus:dev

# Run packaged app (quarkus-app fast-jar)
java -jar micro-engine/target/quarkus-app/quarkus-run.jar

# Unit tests only (**/*Test.java via surefire)
mvn -pl micro-engine test -Dgpg.skip=true

# Integration tests (**/*IT.java via failsafe; off by default, skipITs=true)
mvn -pl micro-engine verify -DskipITs=false -Dgpg.skip=true
# IT profile: quarkus.test.profile=development,no-dbaas,no-m2m

# Checkstyle (qip-checkstyle 0.0.3, maxAllowedViolations=0; runs in `compile` phase)
mvn -pl micro-engine checkstyle:check -Dgpg.skip=true
```

The `quarkus-maven-plugin` `build`/`generate-code` goals and `camel-component-maven-plugin` (generates custom-component sources into `target/generated-sources`) are wired into the build lifecycle. The `flatten-maven-plugin` produces `.flattened-pom.xml`. Released to GitHub Packages with the `github` Maven profile (`mvn deploy -P github`) — micro-engine is the platform exception that does not publish to Maven Central; CI fixes the profile to `github`.

### Architecture

Single Java root package `org.qubership.integration.platform.engine` (artifact and source share the `engine` namespace with the Spring engine), under `src/main/java`:

- `camel/` — custom Camel integration: `components/` (servlet, kafka, rabbitmq, pubsub, graphql, directvm, context), `processors/`, `reifiers/`, `dsl/`, `converters/`, `idempotency/`, `metrics/`, `history/`, `listeners/`, `ContextCustomizer`, `QipCustomClassResolver`.
- `consul/` — Consul KV client (`ConsulClientSupplierProducer`, `ConsulSessionService`), deployment/library/state polling under `updates/` with `parsers/`.
- `service/` — runtime services: `debugger/` (session recording + `ChainLogger`), `CheckpointSessionService`/`CheckpointRestService`, `LiveExchangesService`, `QuartzSchedulerService`, `BlueGreenSchedulerControllerService`/`BlueGreenStateService`, `ExternalLibraryService`, `VariablesService`, `IdempotencyRecordService`, `contextstorage/`, `groovy/`.
- `rest/v1/controller/` — JAX-RS endpoints (`RestApiConstants.V1_ROUTE_PREFIX = /v1/engine`, public prefix `/api/v1/cip/engine`): `SessionController`, `LiveExchangesController` (`/live-exchanges/{deploymentId}/{exchangeId}`), `CheckpointSessionController` (`/sessions/{sessionId}/retry`, `/checkpoint-elements/.../retry`, `/sessions/failed`); paired `dto/` + MapStruct `mapper/`.
- `opensearch/` — session-element index client (index `qip-elements-{namespace}`, optional Kafka/MaaS write path).
- `persistence/` — Hibernate ORM Panache entities (`...persistence.shared.entity`, schema `engine`).
- Also: `configuration/`, `consul/`, `kafka/`, `jms/`, `maas/`, `kubernetes/`, `controlplane/`, `scheduler/`, `state/`, `registry/`, `interceptors/`, `errorhandling/`, `forms/`, `security/`, `metadata/`, `mapper/`, `util/`.

Config: `src/main/resources/application.yml` (+ `application-development.yml`, `application-no-m2m.yml`); Quarkus build profile `development` gates dev-only beans (`@IfBuildProfile("development")`). HTTP on port 8080. Two Flyway migration sets under `db/migration/postgresql/`: `static/` and `configs/` (schema `engine`); dev profile points the checkpoints datasource at `engine_checkpoints_db` and the Quartz scheduler at `engine_qrtz_db`. `Dockerfile` is a layered Quarkus fast-jar image on `alpine/java:21-jdk` (non-root uid 10001, exposes 8080).

### Conventions

- Java 21 (`maven.compiler.release=21`, `<parameters>true</parameters>`).
- Lombok + MapStruct for DTO mapping, wired via `lombok-mapstruct-binding` in the compiler `annotationProcessorPaths`.
- Conventional Commits enforced on PR titles/commits (CI gate).
- Checkstyle zero-violations using the shared `qip-checkstyle` artifact; module-local exceptions in `checkstyle-suppressions.xml`.
- Apache License 2.0; existing source files carry the NetCracker copyright header, new files do not need it.
- PostgreSQL type guidelines: `timestamptz`, `text`, `bytea`.

### Platform Context

The micro-engine is the cloud-native (Quarkus) execution engine that runs deployed integration chains; it is functionally interchangeable with the Spring Boot `engine` module. See `README.md` for the repository layout.

#### Direct Dependencies (this service consumes)

| Service | Protocol | What For |
|---|---|---|
| Consul | HTTP (Vert.x consul client) | Poll deployment/library/runtime-config/state KV under `config/{namespace}`; fetch chains, common variables; publish engine state |
| Runtime Catalog | REST | Fetch chain deployment descriptors; download compiled external-library JARs |
| PostgreSQL 14 | JDBC (Hibernate ORM Panache + Flyway) | Checkpoints/session/idempotency/context storage (`engine` schema, `engine_checkpoints_db`); Quartz scheduler (`engine_qrtz_db`) |
| OpenSearch 2.18 | HTTP (`opensearch-java`), optional Kafka | Record session element traces to index `qip-elements-{namespace}` |
| Kafka / RabbitMQ / Google Pub/Sub | Camel (`camel-quarkus-kafka`/`-amqp`/`-spring-rabbitmq`/`-google-pubsub`), MaaS client | Messaging endpoints inside integration chains; optional MaaS-managed brokers and blue-green consumers |
| Control Plane / Kubernetes | REST, K8s client | Route registration (egress/ingress) and reading secured-variable secrets |

#### Services That Depend on This

| Service | Protocol | What For |
|---|---|---|
| UI (via Nginx) | REST (`/api/*/engine/*` → `/v1/engine/*`) | Live exchanges, session/checkpoint retry, failed-session views |
| Sessions Management | OpenSearch (shared index) | Reads session elements this engine records |
