---
paths:
  - "engine/**"
---

### Project overview

The engine is the Spring Boot service that runs integration chains on Apache Camel. It receives deployment
descriptors from Runtime Catalog and builds and manages one Camel context per chain.

### Build commands

Run from the repository root. `package` stops short of the `verify` phase, where the parent POM signs artifacts with
GPG; anything that reaches `verify` or `install` needs `-Dgpg.skip=true`.

```bash
mvn -pl engine -am package                       # compile, checkstyle, tests
mvn -pl engine -am verify -Dgpg.skip=true        # adds the JaCoCo report in engine/target/site/jacoco/
mvn -pl engine spring-boot:run                   # development profile, JDWP debug port 18085
```

Checkstyle runs in the `compile` phase and allows zero violations. Its rules live in
`checkstyle/src/main/resources/checkstyle.xml`: no tab characters, no Cyrillic characters, and imports grouped as
third-party, then `java.*`, then static, sorted alphabetically with an empty line between groups. Star imports are
allowed.

### Architecture

#### Core flow

Deployment descriptors arrive through Consul events or REST → `DeploymentsUpdateService` fetches chain definitions from
Runtime Catalog → `IntegrationRuntimeService` creates a `SpringCamelContext` per chain with routes built from the
element descriptors → Camel processors handle each integration step.

#### Key packages (`org.qubership.integration.platform.engine`)

- `camel/components/`: custom Camel components with the URI schemes `kafka-custom`, `rabbitmq-custom`,
  `servlet-custom`, `graphql-custom`, `google-pubsub`, and `cip-chain` (inter-chain calls, in `directvm/`).
- `service/deployment/`: the deployment lifecycle, described below.
- `service/debugger/`: `CamelDebugger` extends `DefaultDebugger`, intercepts exchange events, records sessions to
  OpenSearch, supports checkpoint and restore, and masks data through `MaskedFieldUtils`.

#### Deployment lifecycle

`DeploymentProcessingService` runs action beans selected by qualifier annotations, in four phases. The actions live in
`service/deployment/processing/actions/context/{before,create,start,stop}/`:

1. `@OnBeforeDeploymentContextCreated`: connectivity and requirement checks, control-plane route registration.
2. `@OnAfterDeploymentContextCreated`: dependency binders for the chain's elements (HTTP, Kafka, JMS, AMQP, gRPC, SDS
   jobs, MCP tools).
3. `@OnAfterDeploymentContextStarted`: publication of what the second phase prepared for clients to call, such as a
   chain's MCP tools. A deployment that fails to compile or to start leaves the running deployment's tools in place.
4. `@OnStopDeploymentContext`: cleanup of what the earlier phases registered.

#### Datasources

The engine uses two PostgreSQL databases, both in the `engine` schema:

| Datasource key | Database | Repositories | Flyway migrations |
| --- | --- | --- | --- |
| `checkpoints-datasource` | `engine_checkpoints_db` | `persistence.shared.repository` | `db/migration/postgresql/static/` |
| `qrtz-datasource` | `engine_qrtz_db` | `persistence.configs.repository` | `db/migration/postgresql/configs/` |

The Quartz datasource also holds the `configs` entities. `FlywayInitializer` runs both migration sets, and Spring
Boot's own Flyway auto-configuration stays off. With `cip.standalone=true`, the shipped default, the datasources are built with
HikariCP from `db.hikari.datasources` in `PersistenceStandaloneAutoConfiguration`; with `false` they come from DBaaS.
`PersistenceCheckpointAutoConfiguration` and `PersistenceQuartzAutoConfiguration` build the DBaaS datasources and each
datasource's entity manager and transaction manager. Column naming uses Hypersistence
`CamelCaseToSnakeCaseNamingStrategy`.

### Code conventions

- `IntegrationEngineApplication` excludes `CamelAutoConfiguration`, because the engine builds Camel contexts per chain.
  The other exclusions, Flyway, Kafka, RabbitMQ, and Camel's `QuartzComponentAutoConfiguration` among them, are in
  `spring.autoconfigure.exclude` in `application.yml`.
- The `development` profile makes Consul optional, enables the Pub/Sub emulator and debug logging, and points at local
  truststore paths.
- Properties use the `cip.` prefix. `PropertyPrefixAliasEnvironmentPostProcessor` still resolves the legacy `qip.`
  names, and its Javadoc gives the precedence. Declare and read new keys under `cip.` only.

### Build peculiarities

- `maven-compiler-plugin` runs a second `recompile` execution in `process-classes`, because `camel-component-maven-plugin`
  generates sources in that phase and they have to be compiled too.
- `spring-boot-maven-plugin` repackages with the `exec` classifier, so the runnable jar is `qip-engine-*-exec.jar`.

### Neighbors

- The engine consumes Runtime Catalog (deployment updates, external library JARs), Consul, OpenSearch, PostgreSQL, and
  Control Plane.
- Runtime Catalog calls the live-exchanges endpoints, the UI calls the engine through the nginx proxy, and
  sessions-management reads the OpenSearch index the engine writes.
- `micro-engine/` is the Quarkus variant with the same API contracts. A contract change lands in both modules.

### Endpoint mocking

When you change endpoint mocking, the testing context header, or how a test-case run is sessioned or metered, load the
`endpoint-mocking` skill.
