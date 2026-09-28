# Rename the `qip.*` configuration prefix to `cip.*`

Application properties move from `qip.*` to `cip.*`, and `QIP_*` environment variables move to `CIP_*`. The three
Spring Boot services keep accepting `qip.*` from every property source, Consul included, through a property-source
alias. Every other module gets a plain rename.

## Goals

- Every application property that the services read starts with `cip.`.
- engine, runtime-catalog, and sessions-management resolve a `cip.x` lookup from a `qip.x` value when no source with
  higher precedence sets `cip.x`. This covers Consul, `application.yml`, system properties, and environment variables.
- When one source sets both `cip.x` and `qip.x`, `cip.x` wins.
- Environment variables that start with `QIP_` start with `CIP_`, with no fallback to the old name.

## Non-goals

- A SmallRye `ConfigSourceInterceptor` for micro-engine. micro-engine gets the rename only.
- Anything built from `app.prefix` / `application.prefix`, which stays `qip`: Micrometer meter names (`qip.engine.*`),
  error-code prefixes, Kubernetes CR names (`qip-engine-…`), the CR label key (`qip-domain`), and export and import
  file names.
- The `qip_engine_app` pod label, the `qip-elements-*` OpenSearch indices, the `…/schemas/product/qip/*` JSON-schema
  URIs, and the `qip.org/cleanup` finalizer.
- Property values that contain `qip`, such as `/api/v1/qip/engine`, `qip-runtime-catalog`, and `qip-engine-session-`.
- Environment variables owned by services outside this scope, such as `QIP_AI_*`.
- Go identifiers such as the `internal/qip` package, and chart or directory names such as `qip-testing-service`.

## Resolution rule

The Spring alias resolves a lookup of `cip.x` like this:

1. Walk the environment's property sources in precedence order.
2. In each source, return `cip.x` if the source holds it, otherwise `qip.x` if the source holds it.
3. If no source holds either, the property is unset.

The first source that holds either name decides, so a `qip.x` in Consul beats a `cip.x` default in the jar's
`application.yml`. That is the case the rename has to keep working. The alternative, where `cip.x` wins from any source,
lets the renamed `application.yml` defaults shadow every legacy Consul key.

The Spring precedence order is: system properties and environment variables, then Consul, then `application.yml`.

## Spring alias component

Each of engine, runtime-catalog, and sessions-management gets its own copy of
`PropertyPrefixAliasEnvironmentPostProcessor`, placed in the service's `configuration` package:

| Service | Package |
|---|---|
| engine | `org.qubership.integration.platform.engine.configuration` |
| runtime-catalog | `org.qubership.integration.platform.runtime.catalog.configuration` |
| sessions-management | `org.qubership.integration.platform.sessions.configuration` |

The services are separate artifacts with no shared Java library, so the class is copied rather than extracted.

### Registration and ordering

- Registered under `org.springframework.boot.env.EnvironmentPostProcessor` in the service's existing
  `META-INF/spring.factories`, next to the `ConfigDataLocationResolver` entry.
- `getOrder()` returns `ConfigDataEnvironmentPostProcessor.ORDER + 1`. The Consul sources come from
  `spring.config.import: consul:`, which that post-processor loads, so they exist when the alias is added.
- The alias source is added with `addFirst`. Its position only matters for binding (see below), because it implements
  the precedence walk itself.

### `getProperty(name)`

- Returns `null` for any name that does not start with `cip.`.
- Otherwise walks `environment.getPropertySources()` in order and skips two sources: itself, and the
  `configurationProperties` adapter (`ConfigurationPropertySources.ATTACHED_PROPERTY_SOURCE_NAME`), which wraps every
  other source. In each remaining source it checks `cip.x`, then `qip.x`, and returns the first non-null value.
- Relaxed names come for free: `SystemEnvironmentPropertySource.getProperty("qip.istio.enabled")` finds
  `QIP_ISTIO_ENABLED`.

The lookup cannot recurse. The alias only asks individual sources, never the `Environment`, so it never sees its own
query again.

### `getPropertyNames()`

Returns `cip.<rest>` for every name starting with `qip.` or `cip.` in any enumerable source, except the two sources
skipped above, with duplicates removed. `@ConfigurationProperties` binding takes a name from the first source that
enumerates it, and the alias is first, so binding sees the same answer as `getProperty`. This is what makes
`@ConfigurationProperties(prefix = "cip.deploy")` and `Binder.bind("cip.cr.build.container", …)` pick up legacy keys.

Names are rescanned on every call. That cost is paid at startup and on refresh only.

### Consul refresh

The alias holds no state and reads the live sources on every lookup. A Consul watch refresh replaces sources by name
and leaves unknown sources, such as the alias, in place.

### Known limits

- A map or list set only through environment variables under the old prefix, such as `QIP_CR_BUILD_ENVIRONMENT_FOO`,
  is not enumerated, because the raw variable name does not start with `qip.`. Scalar reads work. No such variable is
  known to be in use.
- An old environment variable whose name matches a property path keeps working through relaxed binding. For example,
  `QIP_ISTIO_ENABLED` maps to `qip.istio.enabled` and then to `cip.istio.enabled`. This is a side effect of the
  property alias, not a supported fallback, and the docs name only `CIP_*`.

## Rename inventory

### Properties: `qip.*` → `cip.*`

| Module | Where |
|---|---|
| engine | `application.yml` and `application-development.yml`: the `qip:` block and `${qip.…}` references. Java: `@Value`, `@ConditionalOnProperty` (`name`, `value`, and `prefix = "qip"`), `getProperty(...)`, `@OpenSearchDocument(documentNameProperty = …)`. Tests: `withPropertyValues`, `shippedProperty(...)`. |
| runtime-catalog | `application.yml` and `application-development.yml`. Java: `@Value`, `@ConditionalOnProperty(prefix = "qip.deploy.…")`, `@ConfigurationProperties(prefix = "qip.json.schemas" / "qip.deploy")`, `Binder.bind("qip.cr.build.…")`. Javadoc that names a key. Tests that set keys. |
| sessions-management | `application.yaml` and `application-development.yml`. `PropertiesConstants.PROPERTIES_ROOT = "qip"` → `"cip"`. `@Value`. |
| micro-engine | `application.yml` and `application-development.yml`. Java: `@ConfigMapping(prefix = …)`, `@ConfigProperty`, `@IfBuildProperty`, `@LookupIfProperty`, `getOptionalValue(...)`, the `LogFormatInterceptor` lookup of `qip.logging.format`, `@OpenSearchDocument`. Tests: `@TestConfigProperty`, `DeploymentTestProfile`, and the assertions in `EndpointMockTestingServiceTest`. |
| integration-build-pipeline | `@Value` and `@ConditionalOnProperty` on the builders (`qip.cr.labels.*`, `qip.istio.*`, `qip.gateway.*`, `qip.control-plane.*`, `qip.chains.*`). runtime-catalog loads these beans, so they must read the same prefix as its `application.yml`. |
| integration-build-maven-plugin | `application.yml`: the `qip:` block. |

Keys under `app.prefix` and `application.prefix` are not in the `qip` namespace and stay as they are.

### Environment variables: `QIP_*` → `CIP_*`

| Module | Variables |
|---|---|
| engine | `QIP_ISTIO_ENABLED`, `QIP_ISTIO_HOST_RESOURCES_ENABLED` |
| runtime-catalog | `QIP_EXPORT_LEGACY_FORMAT`, `QIP_EXPORT_LEGACY_RESOURCE_NAMES`, `QIP_EXPORT_REMOVE_UNUSED_SPECS`, `QIP_EGRESS_GATEWAY_URL`, `QIP_ISTIO_ENABLED`, `QIP_ISTIO_HOST_RESOURCES_ENABLED`, `QIP_REGISTER_INGRESS_CHAIN_ROUTES`, `QIP_REGISTER_EGRESS_CHAIN_ROUTES` |
| micro-engine | `QIP_CHAINS_CONFIGURATION_URL`, `QIP_LIBRARIES_PATH`, `QIP_ENGINE_DOMAIN` |
| integration-build-pipeline | `CamelKIntegrationResourceBuilder` writes `CIP_ENGINE_DOMAIN` and `CIP_CHAINS_CONFIGURATION_URL` into micro-engine pods |
| integration-build-maven-plugin | `QIP_EGRESS_GATEWAY_URL`, `QIP_ISTIO_HOST_RESOURCES_ENABLED`, `QIP_REGISTER_INGRESS_CHAIN_ROUTES`, `QIP_REGISTER_EGRESS_CHAIN_ROUTES` |
| testing-service | `envPrefix = "QIP_TESTING_"` → `"CIP_TESTING_"` in `cmd/testing-service/main.go`, with its comments, `main_test.go`, and the comments in `application.yaml` |
| infrastructure | `docker-compose.yml` (three `QIP_TESTING_*`), and in the `qip-testing-service` chart `qip-testing-service-deployment.yaml` (`name` and `configMapKeyRef.key`) and `testing-service-env-configmap.yaml` (keys and comment) |

### Documentation

- `runtime-catalog/README.md`, `integration-build-maven-plugin/README.md`, `testing-service/README.md`.
- `infrastructure/README.md`, `infrastructure/qip-dev/README.md`.
- `help/docs/01__Chains/8__Testing/testing.md`, `help/docs/06__Functionality_and_Features/6__Retention_Settings/retention_settings.md`.
- `.apm/instructions` for engine, micro-engine, runtime-catalog, and infrastructure, followed by `apm compile` to
  regenerate the `AGENTS.md` and `.claude/rules` outputs. `testing-service/AGENTS.md` is hand-maintained and edited
  directly if it names a variable.
- Each Spring service's instruction gets a short note on the alias and the resolution rule.

## Upgrade consequences

- micro-engine has no alias. After upgrading, a `qip.*` key in Consul and the variables `QIP_CHAINS_CONFIGURATION_URL`,
  `QIP_LIBRARIES_PATH`, and `QIP_ENGINE_DOMAIN` are ignored. Deployments must set `cip.*` and `CIP_*`.
- runtime-catalog and micro-engine upgrade together. A new runtime-catalog writes `CIP_ENGINE_DOMAIN` into micro-engine
  pods, which an old micro-engine does not read, and an old runtime-catalog writes `QIP_ENGINE_DOMAIN`, which a new
  micro-engine does not read.
- testing-service, integration-build-maven-plugin, and the Spring services stop reading the renamed `QIP_*` variables
  (apart from the relaxed-binding side effect above), so Helm values and compose files that set them must switch.

## Testing

- One unit test class per Spring service for the post-processor, on a `StandardEnvironment` with `MapPropertySource`s
  standing in for environment variables, Consul, and `application.yml`. Cases:
  - only `qip.x` set: `cip.x` resolves to it;
  - both set in one source: `cip.x` wins;
  - `qip.x` in a higher-precedence source, `cip.x` in a lower one: `qip.x` wins;
  - `cip.x` in a higher-precedence source, `qip.x` in a lower one: `cip.x` wins;
  - `Binder.get(environment).bind("cip.…", …)` sees a map set under `qip.…`;
  - a name outside `cip.` returns `null`, and `qip.x` itself still resolves.
- Existing tests are updated to the new keys and variables.
- Each Maven module builds and passes its tests. testing-service passes `go build ./...` and `go test ./...` from its
  directory.
- A final search over the in-scope modules finds no `qip.` property keys and no in-scope `QIP_` variables.
