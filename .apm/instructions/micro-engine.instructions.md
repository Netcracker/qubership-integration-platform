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

### Endpoint mocking

`EndpointMockTestingService` implements `TestingService`, and `HttpClientConfigurerBuilder.build()` asks it for a route
planner and a request interceptor while it configures the HTTP client of a chain element. The interceptor points scheme
and authority at the testing service, rewrites the request target to `/api/v1/endpoint-mocks/call`, and attaches a
`Testing-Service-Context` header.

The header contract, the base64 encoding and the behavior of the mocks are the same in both engines and are written up
in `engine/AGENTS.md`, including the warning that the testing service receives whatever credentials the element was
sending. Read that first; this section covers what differs here.

| Property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `qip.testing.enabled` | `TESTING_SERVICE_ENABLED` | `false` | makes the bean lookup resolve, through `@LookupIfProperty` |
| `qip.testing.address` | `TESTING_SERVICE_ADDRESS` | `http://testing-service:8080` | base address of the testing service; a base path in it is kept, so an ingress-style address works |

**Enable it only where the testing service is deployed, and only while someone is testing.** With mocking on and the
service absent, every outbound HTTP call from every chain fails to connect; with the service present, a call with no
matching mock is answered `404` instead of reaching the real endpoint.

A toggle takes effect on an engine restart, which re-processes every deployment and rebuilds the HTTP client, so no
chain needs a redeploy. That was verified on the Spring engine only, since `micro-engine` is in neither the local
compose stack nor a Helm chart. Its environment comes from the runtime catalog, which creates micro-engine domains at
runtime: set the two variables in `MICRO_DOMAIN_ENVIRONMENT` there, next to `MICRO_DOMAIN_CONTAINER_IMAGE`.

#### Registration takes two annotations

`HttpClientConfigurerBuilder` resolves the bean programmatically, through
`InjectUtil.injectOptional(CDI.current().select(TestingService.class))`, so the implementation carries **both**
`@LookupIfProperty` and `@Unremovable`. ArC removes a bean nothing injects and `@LookupIfProperty` does not mark one
unremovable; drop `@Unremovable` and the lookup returns empty, so mocking never happens and nothing reports it. The
neighboring `MetricTagsHelper`, resolved the same way two lines above, carries `@Unremovable` for the same reason.

A `@QuarkusComponentTest` cannot cover the switch. Component tests run neither the build step that generates the
`@LookupIfProperty` suppression nor bean removal, so such a test passes whatever the property says. What is covered is
the annotations themselves: `EndpointMockTestingServiceTest` reflects over the class and asserts that both are present
and that `@LookupIfProperty` names `qip.testing.enabled`. Verify the on/off switch itself by hand.

#### `EndpointInfo.path` is not a request path

`TestingService` here takes `EndpointInfo` where `engine` takes `ElementProperties`, which is why the two
implementations are duplicated rather than shared. The field mapping follows from that:

| Context field | Source |
| --- | --- |
| `chainId` | the builder's chain id |
| `elementId` | `endpointInfo.getElementId()`, already the design-time id, so `engine`'s snapshot-id trap has no counterpart here |
| `operationPath` | `endpointInfo.getPath()` |
| `path` | the live request target, read inside the interceptor, query included |

`EndpointInfo.path` carries the **operation template**, not a request path. The builder's `operationPath(String)` setter
writes the field, and runtime-catalog's `HttpSenderBeansBinder` fills it with a single expression,
`contextPath ?? integrationOperationPath ?? "null"`, for every element type it handles: `http-sender`, `graphql-sender`
and a `service-call` over `http` or `graphql`. A `service-call` with a `contextPath` therefore sends that value, and an
`http-sender` has neither property, so the literal string `null` arrives. The value is passed through unchanged;
path-parameter matching then degrades to a no-op in the Go matcher, which is expected.

Do not extend `EndpointInfo` to carry more. The builder has no data of its own, so a new field means a matching change
in runtime-catalog, and Camel binds those properties by setter name: a catalog writing a property an older engine cannot
bind fails at route-build time.
