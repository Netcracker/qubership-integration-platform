---
paths:
  - "runtime-catalog/**"
---

### Build & Test Commands

Run from the repository root.

```bash
# Build (checkstyle runs automatically at compile phase)
mvn -pl runtime-catalog package

# Build without tests
mvn -pl runtime-catalog package -DskipTests

# Run all tests
mvn -pl runtime-catalog test

# Run a single test class
mvn -pl runtime-catalog test -Dtest=ChainElementDifferenceServiceTest

# Run a single test method
mvn -pl runtime-catalog test -Dtest=ChainElementDifferenceServiceTest#testMethodName

# Test coverage report (runtime-catalog/target/site/jacoco/index.html)
mvn -pl runtime-catalog verify -Dgpg.skip=true

# Run locally with dev profile (debug port 18085)
mvn -pl runtime-catalog spring-boot:run -Dspring-boot.run.profiles=development

# Checkstyle only
mvn -pl runtime-catalog checkstyle:check
```

### Architecture Overview

**Single-module Spring Boot 3.5 application** (Java 21, Maven) that serves as the runtime catalog for the Qubership Integration Platform. It manages integration chains, deployments, and library elements, publishing deployment configurations to Consul for consumption by the Integration Engine.

#### Key Layers

- **REST controllers** (`rest/v1`, `rest/v2`, `rest/v3`) — versioned API; v3 is the latest with session-based import/export
- **Services** (`service/`) — core business logic: chain compilation, deployment building, import/export with versioned migrations, spec parsing (OpenAPI/GraphQL/WSDL/Protobuf)
- **Persistence** (`persistence/`) — JPA entities and Spring Data repositories; PostgreSQL with `catalog` schema
- **Mappers** (`rest/v*/mapper/`, `model/mapper/`, `service/exportimport/mapper/`) — MapStruct DTO and entity mapping
- **Consul** (`consul/`) — publishes compiled deployments and listens for library events

#### Important Subsystems

- **Deployment pipeline**: Chain → compiler → Camel XML builder (Handlebars) → Consul publish. The compiler, the
  element templates (`integration-build-pipeline/src/main/resources/elements/*/template.hbs`), and the AtlasMap
  mapper live in integration-build-pipeline.
- **Import/Export**: Session-based (v3) with a versioned migration chain in integration-build-pipeline
  (`io/readers/migrations/`)
  - Exported file names (`<id>.chain.cip.yaml`, `<id>.service.cip.yaml`, and the rest) take `cip` from
    `EXPORT_FILE_APP_NAME` in `util/ExportImportUtils.java`. Build a name with the
    `ExportImportUtils.generate*FileExportName` helpers. Import accepts `.cip.yaml` and the legacy `.qip.yaml`.
  - Check an imported `$schema` with the `ApplicationJsonSchemaProperties.is*` methods, which accept the configured
    URI (`cip.json.schemas.*`) and the legacy `http://qubership.org/schemas/product/qip/<type>`.

#### Code Generation & Processing

- **ANTLR4**: the mapper expression grammar is in `integration-build-pipeline/src/main/antlr4/`; the
  `antlr4-maven-plugin` execution in this POM has no grammar to process
- **MapStruct** for DTO/entity mapping (configured with Lombok binding)
- **Hibernate bytecode enhancement** enabled for lazy initialization

### Conventions

- **Checkstyle** enforced at compile time (rules from the `qip-checkstyle` artifact)
- Unit tests use `@ExtendWith(MockitoExtension.class)` by default. A test that needs Spring wiring uses
  `@ExtendWith(SpringExtension.class)` with `@ContextConfiguration` (lightweight, no full app context). Test
  resources include `src/test/resources`, `src/main/resources`, and
  `integration-build-pipeline/src/test/resources/testConfigurations/`

### The Protobuf toolchain

gRPC specification import shells out to `protoc` and `protoc-gen-grpc-java`. Both are resolved from
Maven Central by `maven-dependency-plugin` into `target/protobuf-toolchain/` and copied into the
image, so their versions follow `<protobuf-java.version>` and `<grpc.version>` in the pom — the
libraries the generated code is compiled and executed against. Bump those properties and the
toolchain follows; do not pin the binaries anywhere else.

### Local stack quirks

One behavior of the local Docker stack looks like a defect in this service but is not:

- `POST /v1/catalog/domains/{domain}/deployments/update` in full-request mode (`excludeDeployments: []`) is served
  from a per-domain in-memory cache. A deployment change made through this catalog clears it. A change made by a
  second catalog container clears it only through the Consul watcher, up to 2.5 s later, and a change made by SQL
  never does. Pass a bogus `excludeDeployments` entry to force the uncached delta path.

### Platform Context

This service is the **central brain** of the Qubership Integration Platform (QIP). See `README.md` for the repository layout.

#### Direct Dependencies (this service consumes)

| Service | Protocol | What For |
|---|---|---|
| **Consul** | REST (`/v1/kv/*`, `/v1/txn`) | Publish compiled deployment configs, listen for library events, configuration at `config/{NAMESPACE}/` |
| **PostgreSQL** | JDBC | Primary data store (schema: `catalog`). Chains, elements, deployments, snapshots, specs, action logs, import sessions. |
| **Engine pods** | REST (direct pod IPs via Kubernetes API) | `GET /v1/engine/live-exchanges` — fetch running exchanges; `DELETE /v1/engine/live-exchanges/{id}/{id}` — kill exchanges |
| **Kubernetes API** | kubernetes-client-java | Discover engine pods/deployments by label `cip_engine_app`, get domain info |

#### Services That Depend on This

| Service | How |
|---|---|
| **Engine / Micro Engine** | Fetches deployment descriptors via `POST /v1/catalog/domains/{domain}/deployments/update` and library JARs via `GET /v1/models/{id}/dto/jar` |
| **Sessions Management** | Resolves chainId → chainName via `GET /v1/chains/names` |
| **UI** | Calls catalog, variables-management, and systems-catalog APIs via Nginx proxy |

#### Key Role in Deployment Pipeline

```text
Chain definition (UI/import) → Runtime Catalog compiles → Camel XML (Handlebars) → Publishes to Consul KV → Engine detects update → Fetches full config → Creates Camel routes
```

### Verifying a change through the API

The end-to-end suite covers the API against a running stack: its typed client
`e2e/support/catalog.ts` holds the calls that work. When the `e2e-decision` instruction calls for
an end-to-end case, it goes in `e2e/specs/api/`. Load the `e2e-spec-authoring` skill before writing
one.

Exercise a change in four directions: once, twice (including into an entity that already
exists), from several threads at once, and with edge inputs (blank, forbidden characters,
duplicates, Unicode). A re-import into an existing folder tree and parallel imports into one new
path both returned 200 and broke the data. A 200 is not a success on its own: the import status
reports a failed chain in `result.chains[].status`, and the asynchronous import worker logs its
error after the request returns. Read the body, the `[ERROR]` lines of the container log, and the
rows the call should have written.
