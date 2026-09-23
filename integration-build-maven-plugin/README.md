# QIP Integration Build Maven Plugin

`qip-integration-build-maven-plugin` compiles QIP integration chains and services, whether exported from QIP
or created in the VS Code extension, into Kubernetes resources for a micro-domain deployment. It also
compiles service specifications into DTO libraries. It runs the same pipeline that runtime-catalog uses for
micro-domains, `qip-integration-build-pipeline`, inside a Maven build instead of behind the catalog's REST API.

Put your chain and service files under `src/main/integration`, add the plugin, and run `mvn compile`:

```xml
<plugin>
  <groupId>org.qubership.integration.platform</groupId>
  <artifactId>qip-integration-build-maven-plugin</artifactId>
  <version>1.3.0</version>
  <executions>
    <execution>
      <goals>
        <goal>build-crs</goal>
        <goal>build-libs</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

The generated YAML files and JAR files land in `target/`.

## Goals

| Goal | Default phase | Output |
| --- | --- | --- |
| `build-crs` | `compile` | One YAML file per Kubernetes resource: the Camel K `Integration`, its ConfigMaps, `Service`, `ServiceMonitor`, and the Istio route resources. |
| `build-libs` | `compile` | One `<specificationId>.jar` per service specification whose protocol has a code generator. |

Each goal starts its own Spring context, reads every source root, and fails the build on the first error.

## Source layout

Both goals walk each source root recursively and pick files by name. The output directory is skipped
during the walk, so a source root may contain it.

| File | Recognized names |
| --- | --- |
| Chain | `chain-*.yaml` or `*.chain.*.yaml`, `.yml` also accepted |
| Integration service | `service-*.yaml` or `*.service.*.yaml`, `.yml` also accepted |
| Specification group | `specGroup-*` or `*.specification-group.*.yaml` in the service's directory |
| Specification | `specification-*` or `*.specification.*.yaml` in the service's directory |

A chain is read from its directory: the chain YAML plus its property files, such as scripts and mappings,
in the same directory or in its `resources/` subdirectory. A directory holds one chain. When it holds two
chain YAML files, the reader takes the first one it lists and ignores the other.

A service is read from its YAML file. Specification groups and specifications are collected from every file
under the service file's directory, and each specification source is loaded from that directory or its
`resources/` subdirectory. Legacy exports that embed groups in the service YAML are read too.

Context service (`*.context-service.*`) and MCP service (`*.mcp-service.*`) exports are skipped without
a message. No resource builder reads them.

This is the layout the UI and runtime-catalog export produces, so an unpacked export archive works as a
source root:

```text
src/main/integration/
├── chains/
│   └── c0273e0b-d143-4704-b4ee-266f045c6631/
│       ├── c0273e0b-d143-4704-b4ee-266f045c6631.chain.qip.yaml
│       └── resources/
│           └── script-a81e8f34-72ef-4f95-a80b-7a5049d52547.groovy
└── services/
    └── 1acc68e3-.../
        ├── 1acc68e3-....service.qip.yaml
        ├── <groupId>.specification-group.qip.yaml
        ├── <specificationId>.specification.qip.yaml
        └── resources/
            └── openapi.json
```

A missing source root is logged as a warning (`Skipping source root '...': not a directory.`) and the goal
continues. With no chains found, `build-crs` writes nothing.

## The `build-crs` goal

### Parameters

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `sourceRoots` | `List<String>` | `${project.basedir}/src/main/integration` | Directories to read chains and services from. |
| `outputDirectory` | `String` | `${project.build.directory}` | Directory the resource files are written to. |
| `deployAll` | `boolean` | `false` | Builds every chain found, whatever its deployment settings. |
| `defaultDomain` | `String` | `me-domain` | Domain for a chain with no micro-domain deployment, reached only with `deployAll`. |
| `controlPlaneType` | `ISTIO` or `CORE` | `ISTIO` | `ISTIO` generates the route resources; `CORE` skips them. |
| `defaultSecretEnabled` | `boolean` | `false` | Sets `DEFAULT_SECRET_ENABLED` in the engine container's environment. |
| `options` | object | see [Resource options](#resource-options) | Shapes the generated deployment. |

None of the parameters has a user property, so set them in the plugin's `<configuration>`, not with `-D`.

### Which chains are built

Without `deployAll`, a chain is built only when both of these hold:

- its `deployAction` is `DEPLOY`;
- its `deployments` list names at least one domain other than `default`.

`default` is the classic, non-micro domain. It is never a build target: runtime-catalog deploys those chains
itself.

A chain goes into every micro-domain its `deployments` list names, so one chain can appear in several
domains. With `deployAll`, a chain that names no micro-domain goes into `defaultDomain`.

Each domain is built separately, into one set of resources: one engine deployment running every chain of
that domain. The domain name becomes the deployment name and prefixes the resource names, for example
`qip-engine-<domain>-routes`.

### Output

Each resource is written to `<outputDirectory>/<Kind>-<name>.yaml`, UTF-8 encoded. Every domain writes to
the same directory, and the domain prefix in resource names keeps the files apart. Two resources of one
kind with one name fail the build with `Duplicate resource '...'`.

Depending on the chains and options, a domain yields:

- a Camel K `Integration` running the micro-engine image;
- a ConfigMap with the Camel XML source of the domain's chains;
- a ConfigMap with the integrations configuration, unless `options.integrations` points elsewhere;
- a `Service`, unless `options.service.enabled` is `false`;
- a `ServiceMonitor`, when `options.monitoring.enabled` is `true`;
- `HTTPRoute` resources for the public, private, and engine routes, and for egress an `HTTPRoute`,
  `ServiceEntry`, and `DestinationRule`, all with `controlPlaneType` set to `ISTIO`.

Some values are Helm template expressions, such as `{{ .Values.monitoring.interval | default "30s" }}`
for a blank monitoring interval. The files are meant to go into a Helm chart's `templates/` directory, not
straight to `kubectl apply`.

### Resource options

`options` maps to `BuildCRsOptions`. Every field is optional.

| Option | Default | Description |
| --- | --- | --- |
| `replicas` | `1` | Engine replica count. |
| `serviceAccount` | `default` | Service account of the engine pod. |
| `environment` | empty | Extra environment variables for the engine container, one element per variable. |
| `container.image` | `ghcr.io/netcracker/qubership-integration-micro-engine:latest` | Engine image. A blank value takes the default, which `MICRO_DOMAIN_CONTAINER_IMAGE` overrides. |
| `container.imagePullPolicy` | `IfNotPresent` | `Always`, `Never`, or `IfNotPresent`. |
| `container.request`, `container.limit` | none | `cpu` and `memory` for the container. |
| `container.readOnlyRootFilesystem` | `true` | Security context setting. |
| `container.runAsNonRoot` | `true` | Security context setting. |
| `container.runAsUser`, `container.runAsGroup` | `0` | Security context setting. |
| `container.allowPrivilegeEscalation` | `false` | Security context setting. |
| `container.seccompProfileType` | `RuntimeDefault` | `RuntimeDefault`, `Unconfined`, or `Localhost`. |
| `container.capabilities.add`, `container.capabilities.drop` | empty | Linux capabilities. |
| `container.args` | empty | Container arguments. |
| `health.liveness`, `health.readiness`, `health.startup` | disabled | Probes: `enabled`, `schema`, `probe`, `port`, `initialDelay`, `timeout`, `period`, `successThreshold`, `failureThreshold`. |
| `jvm.jar`, `jvm.args` | none | JVM entry point and arguments. |
| `monitoring.enabled` | `false` | Generates a `ServiceMonitor` and sets `MONITORING_ENABLED` for the engine. |
| `monitoring.interval` | `30s` | Scrape interval. |
| `service.enabled` | `true` | Generates a `Service`. |
| `mount.emptyDirs`, `mount.resources` | empty | Volumes to mount. |
| `integrations.camelKSourcesUtilized` | `false` | The engine reads chains from Camel K sources, so no integrations configuration ConfigMap is generated. |
| `integrations.configurationLocation` | none | Location the engine loads the integrations configuration from; also suppresses the ConfigMap. |

The plugin always sets two environment variables itself, and they override the same names in
`environment`: `MONITORING_ENABLED` from `monitoring.enabled`, and `DEFAULT_SECRET_ENABLED` from
`defaultSecretEnabled`.

A configuration that exercises most of them:

```xml
<configuration>
  <controlPlaneType>ISTIO</controlPlaneType>
  <options>
    <replicas>2</replicas>
    <container>
      <image>registry.example.com/qip/micro-engine:1.3.0</image>
      <imagePullPolicy>Always</imagePullPolicy>
      <request>
        <cpu>500m</cpu>
        <memory>512Mi</memory>
      </request>
      <limit>
        <memory>1Gi</memory>
      </limit>
    </container>
    <monitoring>
      <enabled>true</enabled>
      <interval>15s</interval>
    </monitoring>
    <environment>
      <LOG_LEVEL>INFO</LOG_LEVEL>
    </environment>
  </options>
</configuration>
```

### Build-time environment variables

The plugin's `application.yml` reads a few environment variables of the Maven JVM. The defaults match a
standard QIP installation.

| Variable | Default | Effect |
| --- | --- | --- |
| `MICRO_DOMAIN_CONTAINER_IMAGE` | `ghcr.io/netcracker/qubership-integration-micro-engine:latest` | Engine image when `options.container.image` is blank. |
| `QIP_CATALOG_SERVICE_NAME` | `qip-runtime-catalog` | Host part of the DTO library URLs in the integrations configuration. |
| `DEPLOYMENT_VERSION` | `v1` | Suffix of that host, giving `qip-runtime-catalog-v1`. |
| `QIP_EGRESS_GATEWAY_URL` | `egress-gateway:8080` | Egress gateway address used in the routes. |
| `QIP_ISTIO_HOST_RESOURCES_ENABLED` | `true` | Generates the egress `ServiceEntry` and `DestinationRule`. |
| `QIP_REGISTER_INGRESS_CHAIN_ROUTES` | `true` | Generates the public and private gateway routes. |
| `QIP_REGISTER_EGRESS_CHAIN_ROUTES` | `true` | Generates the egress routes. |

Placeholder resolution is strict. A property the plugin needs and does not define fails the build with
`Could not resolve placeholder '...'` instead of leaking `${...}` into a generated resource.

## The `build-libs` goal

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `sourceRoots` | `List<String>` | `${project.basedir}/src/main/integration` | Directories to read services from. |
| `outputDirectory` | `String` | `${project.build.directory}` | Directory the JAR files are written to. |

For every specification of every integration service, the goal generates DTO classes with the code
generator for the service's protocol, compiles them, and writes `<outputDirectory>/<specificationId>.jar`
with the generator's manifest. A service whose protocol has no generator is skipped. A specification with
no DTO classes still gets a JAR, holding only the manifest.

The integrations configuration from `build-crs` points the engine at
`http://qip-runtime-catalog-v1:8080/v1/models/<specificationId>/dto/jar`, not at these files. Nothing in
the plugin publishes the JARs there, so a deployment that uses them has to serve them at that URL or
override the host through `QIP_CATALOG_SERVICE_NAME`.

## Migrations

Exported chain and service files carry a format version, and the pipeline migrates each file to the
current format before reading it. The plugin reuses runtime-catalog's import migrations unchanged, with
one difference: the fallback for files that carry no version at all.

### How the version of a file is found

The reader asks a list of strategies, in order, which migrations the file has already been through, and
takes the first answer:

1. `content.migrations`, a list such as `[100, 101, 102]` inside `content`.
2. `migrations` at the top level.
3. `fileVersion`, the oldest format: `fileVersion: N` means migrations `1` through `N`.
4. The plugin's `AssumeActualVersion` fallback, for a file with none of these fields.

The migrations the file lacks are then applied in ascending order. A file that lists a migration the
plugin does not know was exported by a newer QIP version, and the build fails with
`Unable to import an entity exported from a newer version`. Upgrade the plugin to read it.

### The fallback: files without migration metadata

In runtime-catalog, a file with no migration metadata is an error. The plugin instead treats it as
already current: `AssumeActualVersion` answers with every migration the plugin knows, so none is applied.
This fits chains written by hand or by the VS Code extension in the current format. An old file that lost
its metadata is read as current and fails later, on the first field the old format names differently.

The fallback tells files apart by `$schema`: a value containing `service` gets the service migrations,
anything else the chain migrations. A service file with neither metadata nor `$schema` is therefore told it
carries chain migrations, and fails as coming from a newer version. Add a `migrations`
field or a `$schema` to such a file.

Specification group and specification files have no version of their own. A file already in the `content`
layout is read as is; an older one is migrated with the versions of the service file it belongs to.

The revert migrations in the pipeline, which rewrite documents into the legacy export format, run only on
export and never in the plugin.

## Snapshots

runtime-catalog builds resources from snapshots stored in its database. The plugin has no database, so
`SnapshotBuildService` builds an in-memory snapshot from each chain it reads, once per domain the chain
goes into. The result has the shape the catalog's snapshots have, so the shared resource builders cannot
tell them apart.

What building a snapshot does:

- **Verifies element properties first.** Every verification error is logged with its chain, element, and
  type, and the build fails with the first one: `Failed to build snapshot for chain '<name>' (<id>): ...`.
- **Gives each element a new random id**, keeping the export id as the original id. Connections and
  swimlane references are rewired to the new ids.
- **Builds each element once.** The chain's element list is flat, containers and their children side by
  side. The snapshot is built from the root elements down and then flattened, and a repeated id fails the
  build.
- **Attaches a service environment** to every `service-call`, `async-api-trigger`, and `http-trigger` that
  names a service, looked up among the loaded services. An element that names a service missing from the
  source roots fails the build with `Integration service not found: <id>`.

The environment is chosen as follows: the environment whose id matches the service's
`activeEnvironmentId`, otherwise the service's first environment, otherwise an empty placeholder marked
not activated. runtime-catalog decides per service type and does not always fall back to the first
environment, so an `EXTERNAL` service without an active environment can resolve differently here.

The snapshot name is the build timestamp. Together with the random ids, this makes two builds of the same
sources differ in the source and integrations configuration ConfigMaps, and every rebuild reads as a change
in a GitOps diff. A design for reproducible output is in
[`docs/superpowers/specs/2026-09-21-reproducible-maven-plugin-output-design.md`](../docs/superpowers/specs/2026-09-21-reproducible-maven-plugin-output-design.md).

## Limitations

- One invalid chain fails the whole build; there is no per-chain skip.
- Generated files are not attached to the project as build artifacts.
- No `skip` parameter and no user properties; the goals are not marked thread-safe.
- Output is not reproducible, see [Snapshots](#snapshots).

[`FIXES.md`](FIXES.md) tracks the status of each review finding, and [`REVIEW.md`](REVIEW.md) has the
analysis behind them.

## Building the plugin

```bash
mvn -pl integration-build-maven-plugin -am clean install -Dgpg.skip=true
```

The plugin depends on `qip-integration-build-pipeline` at the version pinned by
`qip-integration-build-pipeline.version` in `pom.xml`, with `${changelist}` appended. A development build
resolves the library from the reactor; a release resolves the published JAR.
`scripts/check-version-invariants.sh` keeps the pin equal to the library's revision.
