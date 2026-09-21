# Reproducible Maven Plugin Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the integration build Maven plugin produce byte-identical resources for identical input, without changing what runtime-catalog generates.

**Architecture:** Four id generators move behind interfaces in `integration-build-pipeline`, each with a default implementation that draws a random UUID so runtime-catalog is unaffected. The plugin supplies `@Primary` implementations that derive ids from stable seeds. Separately, the plugin derives its snapshot and element ids and takes its build timestamp from `project.build.outputTimestamp`.

**Tech Stack:** Java 21, Maven, Spring (component scan, no Boot auto-configuration in the plugin), JUnit 5, Mockito.

**Spec:** [`docs/superpowers/specs/2026-09-21-reproducible-maven-plugin-output-design.md`](../specs/2026-09-21-reproducible-maven-plugin-output-design.md)

## Global Constraints

- **runtime-catalog's generated output must not change.** Every default implementation keeps today's random behavior. Any task that alters catalog output is wrong.
- Java 21. Both hosts component-scan `org.qubership.integration.platform`, so a `@Component` or `@Configuration` anywhere under it is picked up by both.
- Checkstyle runs on every module build and **fails on unused imports**. Remove imports you orphan.
- Conventional Commits are enforced on commit messages. Repository history uses past tense in the summary (`fix: fixed …`, `feat: added …`); match it.
- Build locally with `-Dgpg.skip=true`. Verify a module with `mvn -o -pl <module> install -Dgpg.skip=true -Dmaven.javadoc.skip=true`.
- Derived ids always use `UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8))`.
- Three generators seed from an element id, so each carries a discriminator: `"route"`, `"registration"`, `"mapping"`. Do not "tidy" these away — without them an HTTP trigger drives two generators from the identical string.

---

### Task 1: Build timestamp overload on BuildInfoFactory

**Files:**
- Modify: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/services/BuildInfoFactory.java`
- Test: `integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/services/BuildInfoFactoryTest.java` (create)

**Interfaces:**
- Consumes: nothing
- Produces: `BuildInfo createBuildInfo(ResourceBuildOptions options, String createdBy, Instant timestamp)`. Task 6 calls it.

- [ ] **Step 1: Write the failing test**

```java
package org.qubership.integration.platform.camelk.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildInfoFactoryTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    private final BuildInfoFactory factory = new BuildInfoFactory(context -> "build-name");

    @Test
    void usesTheSuppliedTimestampInsteadOfTheClock() {
        BuildInfo first = factory.createBuildInfo(ResourceBuildOptions.builder().build(), "tester", TIMESTAMP);
        BuildInfo second = factory.createBuildInfo(ResourceBuildOptions.builder().build(), "tester", TIMESTAMP);

        assertEquals(TIMESTAMP, first.getTimestamp());
        assertEquals(TIMESTAMP, second.getTimestamp());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -pl integration-build-pipeline test -Dtest=BuildInfoFactoryTest -Dgpg.skip=true`
Expected: compilation failure — no three-argument `createBuildInfo`.

- [ ] **Step 3: Add the overload**

Replace the body of the existing two-argument method and add the three-argument one:

```java
    public BuildInfo createBuildInfo(ResourceBuildOptions options, String createdBy) {
        return createBuildInfo(options, createdBy, Instant.now());
    }

    /**
     * Builds with a caller-supplied timestamp, for a host that needs the same input to produce the
     * same output. The timestamp reaches the generated resources as the build name and the
     * {@code DeploymentInfo} timestamp, so a clock reading makes every build differ.
     */
    public BuildInfo createBuildInfo(ResourceBuildOptions options, String createdBy, Instant timestamp) {
        String id = UUID.randomUUID().toString();
        BuildNamingContext buildNamingContext = BuildNamingContext.builder()
            .id(id)
            .timestamp(timestamp)
            .build();
        return BuildInfo.builder()
            .id(id)
            .timestamp(timestamp)
            .name(buildNamingStrategy.getName(buildNamingContext))
            .options(options)
            .createdBy(createdBy)
            .build();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o -pl integration-build-pipeline test -Dtest=BuildInfoFactoryTest -Dgpg.skip=true`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/services/BuildInfoFactory.java integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/services/BuildInfoFactoryTest.java
git commit -m "feat: added a build timestamp overload to BuildInfoFactory"
```

---

### Task 2: RouteIdGenerator behind ChainRouteBuilder

**Files:**
- Create: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids/RouteIdGenerator.java`
- Create: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids/RandomRouteIdGenerator.java`
- Modify: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/io/writers/camel/xml/ChainRouteBuilder.java`
- Test: `integration-build-pipeline/src/test/java/org/qubership/integration/platform/ids/RandomRouteIdGeneratorTest.java` (create)

**Interfaces:**
- Consumes: nothing
- Produces: `RouteIdGenerator.generate(String elementId)`. Task 8 supplies a `@Primary` implementation.

- [ ] **Step 1: Write the failing test**

```java
package org.qubership.integration.platform.ids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RandomRouteIdGeneratorTest {
    private final RouteIdGenerator generator = new RandomRouteIdGenerator();

    @Test
    void drawsAFreshIdEachCallSoTheCatalogKeepsItsBehaviour() {
        assertNotEquals(generator.generate("element-1"), generator.generate("element-1"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -pl integration-build-pipeline test -Dtest=RandomRouteIdGeneratorTest -Dgpg.skip=true`
Expected: compilation failure — `RouteIdGenerator` and `RandomRouteIdGenerator` do not exist.

- [ ] **Step 3: Create the interface and the default**

`RouteIdGenerator.java`:

```java
package org.qubership.integration.platform.ids;

/**
 * Names the route a given element heads. The route id becomes an XML route id and a {@code direct:}
 * endpoint, so it must not collide with the raw element ids {@code ChainRouteBuilder} already uses for
 * container and branch routes.
 */
@FunctionalInterface
public interface RouteIdGenerator {
    String generate(String elementId);
}
```

`RandomRouteIdGenerator.java`:

```java
package org.qubership.integration.platform.ids;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** The default: a fresh id per route, which is what runtime-catalog has always produced. */
@Component
public class RandomRouteIdGenerator implements RouteIdGenerator {
    @Override
    public String generate(String elementId) {
        return UUID.randomUUID().toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o -pl integration-build-pipeline test -Dtest=RandomRouteIdGeneratorTest -Dgpg.skip=true`
Expected: PASS

- [ ] **Step 5: Wire it into ChainRouteBuilder**

Add the field and constructor parameter:

```java
    private final LibraryElementsService libraryService;
    private final CompositeTriggerHelper compositeTriggerHelper;
    private final RouteIdGenerator routeIdGenerator;

    @Autowired
    public ChainRouteBuilder(
            LibraryElementsService libraryService,
            CompositeTriggerHelper compositeTriggerHelper,
            RouteIdGenerator routeIdGenerator
    ) {
        this.libraryService = libraryService;
        this.compositeTriggerHelper = compositeTriggerHelper;
        this.routeIdGenerator = routeIdGenerator;
    }
```

Replace both bare `new ChainRoute()` calls in `collectRoutes`. The first:

```java
            ChainRoute route = !BuilderConstants.REUSE_ELEMENT_TYPE.equals(startElement.getType())
                    ? new ChainRoute(routeIdGenerator.generate(startElement.getId()))
                    : new ChainRoute(startElement.getOriginalId().orElse(startElement.getId()));
```

The second, inside the `for (Connection connection : ...)` loop:

```java
                    if (completeRoute || nextElement.getInputConnections().size() > 1) {
                        route = new ChainRoute(routeIdGenerator.generate(nextElement.getId())); // start new route
```

Add the import `org.qubership.integration.platform.ids.RouteIdGenerator`.

- [ ] **Step 6: Verify the module still builds**

Run: `mvn -o -pl integration-build-pipeline install -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids integration-build-pipeline/src/main/java/org/qubership/integration/platform/io/writers/camel/xml/ChainRouteBuilder.java integration-build-pipeline/src/test/java/org/qubership/integration/platform/ids
git commit -m "feat: moved route id generation behind a bean"
```

---

### Task 3: RouteRegistrationIdGenerator behind RoutesGetterService

**Files:**
- Create: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids/RouteRegistrationIdGenerator.java`
- Create: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids/RandomRouteRegistrationIdGenerator.java`
- Modify: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/services/RoutesGetterService.java`
- Modify: `integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/services/RoutesGetterServiceTest.java:48`

**Interfaces:**
- Consumes: nothing
- Produces: `RouteRegistrationIdGenerator.generate(String elementId)`. Task 8 supplies a `@Primary` implementation.

- [ ] **Step 1: Create the interface and the default**

`RouteRegistrationIdGenerator.java`:

```java
package org.qubership.integration.platform.ids;

/**
 * Names the control-plane route registration a given element produces. Each element yields at most one:
 * the three call sites in {@code RoutesGetterService} filter on mutually exclusive element types, and
 * the service site iterates a {@code groupingBy} partition.
 */
@FunctionalInterface
public interface RouteRegistrationIdGenerator {
    String generate(String elementId);
}
```

`RandomRouteRegistrationIdGenerator.java`:

```java
package org.qubership.integration.platform.ids;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** The default: a fresh id per registration, which is what runtime-catalog has always produced. */
@Component
public class RandomRouteRegistrationIdGenerator implements RouteRegistrationIdGenerator {
    @Override
    public String generate(String elementId) {
        return UUID.randomUUID().toString();
    }
}
```

- [ ] **Step 2: Give RoutesGetterService a constructor**

The class currently has none — it uses `@Value` field injection. Add above the existing `@Value` fields:

```java
    private final RouteRegistrationIdGenerator routeRegistrationIdGenerator;

    @Autowired
    public RoutesGetterService(RouteRegistrationIdGenerator routeRegistrationIdGenerator) {
        this.routeRegistrationIdGenerator = routeRegistrationIdGenerator;
    }
```

Add imports `org.springframework.beans.factory.annotation.Autowired` and
`org.qubership.integration.platform.ids.RouteRegistrationIdGenerator`.

- [ ] **Step 3: Replace the id at the sender site**

In `buildHttpSendersRoutes`, the element in scope is `sender`:

```java
                        Route.RouteBuilder builder = Route.builder()
                                .id(routeRegistrationIdGenerator.generate(sender.getId()))
```

- [ ] **Step 4: Replace the id at the service site**

In `buildServicesRoutes`, inside `for (Element element : elements)`:

```java
                routes.add(Route.builder()
                        .id(routeRegistrationIdGenerator.generate(element.getId()))
```

- [ ] **Step 5: Restructure the trigger site so the element stays in scope**

`buildTriggersRoutes` currently maps `Element` to `ElementRoute` and loses the element. Collapse the two `map` calls into one:

```java
    private List<Route> buildTriggersRoutes(Snapshot snapshot) {
        return snapshot.getElements().stream()
                .filter(element -> HTTP_TRIGGER_COMPONENT.equals(element.getType()))
                .map(element -> {
                    ElementRoute elementRoute = TriggerUtils.getHttpTriggerRoute(element);
                    return Route.builder()
                            .id(routeRegistrationIdGenerator.generate(element.getId()))
                            .path("/" + elementRoute.getPath())
                            .type(RouteType.convertTriggerType(elementRoute.isExternal(), elementRoute.isPrivate()))
                            .connectTimeout(elementRoute.getConnectionTimeout())
                            .build();
                })
                .collect(Collectors.toList());
    }
```

Add the import `org.qubership.integration.platform.camelk.model.routes.ElementRoute`.

- [ ] **Step 6: Fix the existing test that constructs the service**

`RoutesGetterServiceTest:48` calls `new RoutesGetterService()`. Pass a stub that makes the id readable in assertions:

```java
        routesGetterService = new RoutesGetterService(elementId -> "registration-" + elementId);
```

- [ ] **Step 7: Run the module tests**

Run: `mvn -o -pl integration-build-pipeline install -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Expected: BUILD SUCCESS. If `RoutesGetterServiceTest` asserts on route ids, update those assertions to the stub's format rather than weakening them.

- [ ] **Step 8: Commit**

```bash
git add integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/services/RoutesGetterService.java integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/services/RoutesGetterServiceTest.java
git commit -m "feat: moved route registration id generation behind a bean"
```

---

### Task 4: MappingIdGenerator behind AtlasMapInterpreter

**Files:**
- Create: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids/MappingIdGenerator.java`
- Create: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids/RandomMappingIdGenerator.java`
- Modify: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/mapper/build/atlasmap/AtlasMapInterpreter.java:419`
- Modify: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/mapper/AtlasMapInterpreterTest.java:38`

**Interfaces:**
- Consumes: nothing
- Produces: `MappingIdGenerator.generate(String actionId)`, returning the complete id including the `mapping.` prefix. Task 8 supplies a `@Primary` implementation.

- [ ] **Step 1: Create the interface and the default**

`MappingIdGenerator.java`:

```java
package org.qubership.integration.platform.ids;

/**
 * Names one AtlasMap mapping. The seed is the mapping action's own id, which the parser requires, so it
 * is stateless — an ordinal would put a counter in a singleton that runtime-catalog uses concurrently.
 * The returned value is the complete id, {@code mapping.} prefix included, so only one place knows that
 * format.
 */
@FunctionalInterface
public interface MappingIdGenerator {
    String generate(String actionId);
}
```

`RandomMappingIdGenerator.java`:

```java
package org.qubership.integration.platform.ids;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** The default: a fresh id per mapping, which is what runtime-catalog has always produced. */
@Component
public class RandomMappingIdGenerator implements MappingIdGenerator {
    @Override
    public String generate(String actionId) {
        return "mapping.".concat(UUID.randomUUID().toString());
    }
}
```

- [ ] **Step 2: Inject it into AtlasMapInterpreter**

```java
    private final ObjectMapper objectMapper;
    private final MappingIdGenerator mappingIdGenerator;
    private final DataTypeToFieldTypeConverter dataTypeToFieldTypeConverter;

    @Autowired
    public AtlasMapInterpreter(
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            MappingIdGenerator mappingIdGenerator
    ) {
        this.objectMapper = objectMapper;
        this.mappingIdGenerator = mappingIdGenerator;
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.dataTypeToFieldTypeConverter = new DataTypeToFieldTypeConverter();
    }
```

Add the import `org.qubership.integration.platform.ids.MappingIdGenerator`.

- [ ] **Step 3: Replace the id at line 419**

`action` is already in scope — it is passed to `validateForSupportedMappings` on the line above.

```java
        Mapping mapping = new Mapping();
        mapping.setId(mappingIdGenerator.generate(action.getId()));
```

- [ ] **Step 4: Fix the runtime-catalog test that constructs the interpreter**

`AtlasMapInterpreterTest:38`:

```java
        interpreter = new AtlasMapInterpreter(
                MapperTestUtils.OBJECT_MAPPER,
                actionId -> "mapping.".concat(UUID.randomUUID().toString()));
```

Add `import java.util.UUID;` if it is not already there.

- [ ] **Step 5: Run both affected modules**

Run: `mvn -o -pl integration-build-pipeline install -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Then: `mvn -o -pl runtime-catalog test -Dtest=AtlasMapInterpreterTest -Dgpg.skip=true -Dcheckstyle.skip=true`
Expected: BUILD SUCCESS for both.

- [ ] **Step 6: Commit**

```bash
git add integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids integration-build-pipeline/src/main/java/org/qubership/integration/platform/mapper/build/atlasmap/AtlasMapInterpreter.java runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/mapper/AtlasMapInterpreterTest.java
git commit -m "feat: moved AtlasMap mapping id generation behind a bean"
```

---

### Task 5: MapperMappingIdGenerator behind MapperInterpreterHelper

**Files:**
- Create: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids/MapperMappingIdGenerator.java`
- Create: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids/RandomMapperMappingIdGenerator.java`
- Modify: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/io/writers/camel/xml/templates/helpers/MapperInterpreterHelper.java`

**Interfaces:**
- Consumes: nothing
- Produces: `MapperMappingIdGenerator.generate(String elementId)`. Task 8 supplies a `@Primary` implementation.

- [ ] **Step 1: Create the interface and the default**

`MapperMappingIdGenerator.java`:

```java
package org.qubership.integration.platform.ids;

/**
 * Names the mapping a mapper element carries, written into the {@code internalProperty_mappingId}
 * exchange property. This is the one generator with no naturally unique seed of its own: it can only
 * use the mapper element's id, which {@code RouteIdGenerator} may also be seeded with, so a derived
 * implementation must discriminate.
 */
@FunctionalInterface
public interface MapperMappingIdGenerator {
    String generate(String elementId);
}
```

`RandomMapperMappingIdGenerator.java`:

```java
package org.qubership.integration.platform.ids;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** The default: a fresh id per mapper element, which is what runtime-catalog has always produced. */
@Component
public class RandomMapperMappingIdGenerator implements MapperMappingIdGenerator {
    @Override
    public String generate(String elementId) {
        return UUID.randomUUID().toString();
    }
}
```

- [ ] **Step 2: Inject it and use the element from the template context**

Add the constructor parameter:

```java
    private final ObjectMapper objectMapper;
    private final MappingInterpreter interpreter;
    private final MappingDescriptionValidator validator;
    private final MapperMappingIdGenerator mapperMappingIdGenerator;

    @Autowired
    public MapperInterpreterHelper(
            MappingInterpreter interpreter,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            MappingDescriptionValidator validator,
            MapperMappingIdGenerator mapperMappingIdGenerator
    ) {
        this.interpreter = interpreter;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.mapperMappingIdGenerator = mapperMappingIdGenerator;
    }
```

Replace `mappingId`:

```java
    public CharSequence mappingId(Options options) {
        Element element = (Element) options.context.model();
        return mapperMappingIdGenerator.generate(element.getId());
    }
```

The cast is deliberate. The helper is invoked only from an element template, and the same class already
treats `options.context.model()` as an `Element` in `apply`. A context that is not an element is a
template wiring error and should fail loudly rather than fall back to a value that quietly varies.

Remove the now-unused `import java.util.UUID;` — checkstyle fails on unused imports. Add the import
`org.qubership.integration.platform.ids.MapperMappingIdGenerator`.

- [ ] **Step 3: Verify the module builds**

Run: `mvn -o -pl integration-build-pipeline install -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add integration-build-pipeline/src/main/java/org/qubership/integration/platform/ids integration-build-pipeline/src/main/java/org/qubership/integration/platform/io/writers/camel/xml/templates/helpers/MapperInterpreterHelper.java
git commit -m "feat: moved mapper mapping id generation behind a bean"
```

---

### Task 6: Build timestamp from project.build.outputTimestamp

**Files:**
- Modify: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/mojos/BuildCRsMojo.java`
- Modify: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/tasks/BuildCRsTaskParameters.java`
- Modify: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/services/MicroDomainResourceBuildContextFactory.java`
- Modify: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/services/MicroDomainResourcesBuildService.java`
- Modify: `integration-build-maven-plugin/src/test/java/org/qubership/integration/platform/maven/plugin/domain/services/MicroDomainResourceBuildContextFactoryTest.java`
- Modify: `integration-build-maven-plugin/src/test/java/org/qubership/integration/platform/maven/plugin/domain/services/MicroDomainResourcesBuildServiceTest.java`

**Interfaces:**
- Consumes: `BuildInfoFactory.createBuildInfo(options, createdBy, timestamp)` from Task 1.
- Produces: `BuildCRsTaskParameters.getBuildTimestamp()` returning `Instant`, used by Task 7.

- [ ] **Step 1: Add the field to BuildCRsTaskParameters**

```java
    ControlPlaneType controlPlaneType;
    Instant buildTimestamp;
```

Add `import java.time.Instant;`.

- [ ] **Step 2: Add the mojo parameter and its parser**

In `BuildCRsMojo`, after the `controlPlaneType` parameter:

```java
    /**
     * Build timestamp, taken from Maven's reproducible-build property. Set it and the same sources
     * build byte-identical resources; leave it unset and the build stamps the current time.
     */
    @Parameter(name = "outputTimestamp", defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;
```

Add the parser and the builder call:

```java
    /**
     * Reads {@code outputTimestamp} the way the reproducible-build convention defines it: epoch seconds
     * or an ISO-8601 instant, with a blank or single-character value meaning unset, since projects leave
     * a placeholder there until they opt in.
     */
    private Instant buildTimestamp() {
        if (outputTimestamp == null || outputTimestamp.trim().length() < 2) {
            return Instant.now();
        }
        String value = outputTimestamp.trim();
        return value.chars().allMatch(Character::isDigit)
            ? Instant.ofEpochSecond(Long.parseLong(value))
            : OffsetDateTime.parse(value).toInstant();
    }
```

```java
            .controlPlaneType(controlPlaneType)
            .buildTimestamp(buildTimestamp())
```

Add imports `java.time.Instant` and `java.time.OffsetDateTime`.

- [ ] **Step 3: Thread it to BuildInfoFactory**

`MicroDomainResourceBuildContextFactory`:

```java
    public ResourceBuildContext<List<Snapshot>> createResourceBuildContext(
        List<Snapshot> snapshots,
        ResourceBuildOptions options,
        Instant buildTimestamp
    ) {
        BuildInfo buildInfo = buildInfoFactory.createBuildInfo(options, CREATED_BY, buildTimestamp);
        return ResourceBuildContext.create(buildInfo, integrationServiceCatalog)
            .updateTo(snapshots);
    }
```

Add `import java.time.Instant;`.

In `MicroDomainResourcesBuildService.buildChainResourcesForDomain`:

```java
        ResourceBuildContext<List<Snapshot>> buildContext =
            resourceBuildContextFactory.createResourceBuildContext(
                snapshots, resourceBuildOptions, parameters.getBuildTimestamp());
```

- [ ] **Step 4: Update the two affected tests**

`MicroDomainResourceBuildContextFactoryTest` — add a constant and pass it:

```java
    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");
```

```java
        ResourceBuildContext<List<Snapshot>> context =
            factory.createResourceBuildContext(snapshots, options, TIMESTAMP);
```

`MicroDomainResourcesBuildServiceTest` — widen the two stubs and the verification:

```java
        when(buildContextFactory.createResourceBuildContext(any(), any(), any())).thenAnswer(invocation -> {
```

```java
            .createResourceBuildContext(snapshots.capture(), options.capture(), any());
```

- [ ] **Step 5: Run the module build**

Run: `mvn -o -pl integration-build-maven-plugin install -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add integration-build-maven-plugin/src
git commit -m "feat: took the plugin build timestamp from project.build.outputTimestamp"
```

---

### Task 7: Chain content digest and derived snapshot and element ids

**Files:**
- Modify: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/services/SnapshotBuildService.java`
- Modify: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/services/MicroDomainResourcesBuildService.java`
- Modify: `integration-build-maven-plugin/src/test/java/org/qubership/integration/platform/maven/plugin/domain/services/SnapshotBuildServiceTest.java`
- Modify: `integration-build-maven-plugin/src/test/java/org/qubership/integration/platform/maven/plugin/domain/services/MicroDomainResourcesBuildServiceTest.java`

**Interfaces:**
- Consumes: `BuildCRsTaskParameters.getBuildTimestamp()` from Task 6.
- Produces: `SnapshotBuildService.build(Chain chain, String contentDigest, Instant timestamp)`.

- [ ] **Step 1: Write the failing test**

Add to `SnapshotBuildServiceTest`, alongside the existing constants:

```java
    private static final String DIGEST = "0".repeat(64);
    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");
```

```java
    @Test
    void derivesTheSameIdsFromTheSameInput() {
        Chain chain = chain(List.of(element("e1", SENDER)), List.of());

        Snapshot first = snapshotBuildService.build(chain, DIGEST, TIMESTAMP);
        Snapshot second = snapshotBuildService.build(chain, DIGEST, TIMESTAMP);

        assertEquals(first.getId(), second.getId());
        assertEquals(first.getName(), second.getName());
        assertEquals(
            first.getElements().iterator().next().getId(),
            second.getElements().iterator().next().getId());
    }

    /** The snapshot id carries the content, so a changed chain gets a new one; its elements do not. */
    @Test
    void movesTheSnapshotIdWithTheContentButNotTheElementIds() {
        Chain chain = chain(List.of(element("e1", SENDER)), List.of());

        Snapshot before = snapshotBuildService.build(chain, DIGEST, TIMESTAMP);
        Snapshot after = snapshotBuildService.build(chain, "1".repeat(64), TIMESTAMP);

        assertNotEquals(before.getId(), after.getId());
        assertEquals(
            before.getElements().iterator().next().getId(),
            after.getElements().iterator().next().getId());
    }
```

Change every other `snapshotBuildService.build(chain)` call in the file to
`snapshotBuildService.build(chain, DIGEST, TIMESTAMP)`. Add `import java.time.Instant;`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -o -pl integration-build-maven-plugin test -Dtest=SnapshotBuildServiceTest -Dgpg.skip=true`
Expected: compilation failure — `build` takes one argument.

- [ ] **Step 3: Derive the ids in SnapshotBuildService**

```java
    /**
     * Builds the snapshot for {@code chain}.
     *
     * <p>Ids are derived rather than drawn at random, so the same input builds the same resources. The
     * snapshot id carries {@code contentDigest}, so it moves when the chain's content does, the way a
     * new catalog snapshot row would. Element ids come from the element's own id, so editing a chain
     * does not renumber the elements it did not touch.
     *
     * @param contentDigest fingerprint of the chain as it was read
     * @param timestamp     build timestamp, recorded as the snapshot name
     */
    public Snapshot build(Chain chain, String contentDigest, Instant timestamp) {
        verifyElementProperties(chain);

        SnapshotImpl snapshot = new SnapshotImpl();
        // Both parts are fixed width -- a 36-character id and a 64-character digest -- so they cannot
        // run together ambiguously. A variable-length part would need a separator.
        snapshot.setId(derivedId(chain.getId() + contentDigest));
        snapshot.setName(timestamp.toString());
```

Replace the element id line in `createElementIdMap`:

```java
        forEachElement(elements, element -> idMap.put(element.getId(), derivedId(element.getId())));
```

Add the helper above `createElementIdMap`:

```java
    /** A UUID derived from {@code seed}, so the same input always yields the same id. */
    private static String derivedId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
```

Add `import java.nio.charset.StandardCharsets;`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -o -pl integration-build-maven-plugin test -Dtest=SnapshotBuildServiceTest -Dgpg.skip=true`
Expected: PASS

- [ ] **Step 5: Compute the digest and carry it with its chain**

In `MicroDomainResourcesBuildService`, add the record below the constant:

```java
    /**
     * A chain together with a fingerprint of the bytes it was read from. The digest covers the whole
     * chain directory, so it includes the separately exported property files -- scripts, mapping
     * descriptions -- that the reader folds into element properties.
     */
    private record ChainSource(ImportChain chain, String contentDigest) {}
```

Add the digest function:

```java
    /**
     * SHA-256 over every file in {@code directory}, walked in path order so the result does not depend
     * on how the file system enumerates it. The relative path goes into the digest alongside the bytes,
     * so renaming a file changes it.
     */
    private static String digestDirectory(File directory) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Path root = directory.toPath();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String relativePath = root.relativize(file).toString().replace(File.separatorChar, '/');
                digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
```

Change `readChains` to return `Collection<ChainSource>`:

```java
        return Failable.stream(chainDirectories)
            .map(directory -> new ChainSource(
                processFile(directory, chainReader::read), digestDirectory(directory)))
            .stream()
            .toList();
```

Change `groupChainsByDomain` to take and return `ChainSource`, reading the deployments through
`chain.chain().getDeployments()` and building `Collection<ChainSource>` in the `compute` branch. Change
`buildChainResourcesForDomain` to accept `Collection<ChainSource>` and build with the digest:

```java
        List<Snapshot> snapshots = chains.stream()
            .map(source -> snapshotBuildService.build(
                source.chain(), source.contentDigest(), parameters.getBuildTimestamp()))
            .collect(Collectors.toList());
```

Add imports `java.nio.charset.StandardCharsets`, `java.security.MessageDigest` and
`java.security.NoSuchAlgorithmException`. `HexFormat` comes from the existing `java.util.*` import.

- [ ] **Step 6: Update the build-service test stub**

In `MicroDomainResourcesBuildServiceTest`:

```java
        when(snapshotBuildService.build(any(), any(), any())).thenAnswer(invocation -> {
```

- [ ] **Step 7: Run the module build**

Run: `mvn -o -pl integration-build-maven-plugin install -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add integration-build-maven-plugin/src
git commit -m "feat: derived the plugin snapshot and element ids from the chain"
```

---

### Task 8: The plugin's derived generator overrides

**Files:**
- Create: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/ids/DerivedIds.java`
- Create: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/ids/DerivedRouteIdGenerator.java`
- Create: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/ids/DerivedRouteRegistrationIdGenerator.java`
- Create: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/ids/DerivedMappingIdGenerator.java`
- Create: `integration-build-maven-plugin/src/main/java/org/qubership/integration/platform/maven/plugin/domain/ids/DerivedMapperMappingIdGenerator.java`
- Test: `integration-build-maven-plugin/src/test/java/org/qubership/integration/platform/maven/plugin/domain/ids/DerivedIdGeneratorsTest.java` (create)

**Interfaces:**
- Consumes: the four interfaces from Tasks 2 to 5.
- Produces: four `@Primary` beans; nothing later depends on their names.

- [ ] **Step 1: Write the failing test**

```java
package org.qubership.integration.platform.maven.plugin.domain.ids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedIdGeneratorsTest {
    private static final String ELEMENT_ID = "b1a0c4de-0000-4000-8000-000000000001";

    private final DerivedRouteIdGenerator routeIds = new DerivedRouteIdGenerator();
    private final DerivedRouteRegistrationIdGenerator registrationIds = new DerivedRouteRegistrationIdGenerator();
    private final DerivedMappingIdGenerator mappingIds = new DerivedMappingIdGenerator();
    private final DerivedMapperMappingIdGenerator mapperMappingIds = new DerivedMapperMappingIdGenerator();

    @Test
    void repeatsItselfForOneSeed() {
        assertEquals(routeIds.generate(ELEMENT_ID), routeIds.generate(ELEMENT_ID));
        assertEquals(registrationIds.generate(ELEMENT_ID), registrationIds.generate(ELEMENT_ID));
        assertEquals(mappingIds.generate("action-1"), mappingIds.generate("action-1"));
        assertEquals(mapperMappingIds.generate(ELEMENT_ID), mapperMappingIds.generate(ELEMENT_ID));
    }

    /**
     * An HTTP trigger is both a route head and a route registration, and a mapper element can head a
     * route too, so all three would otherwise derive one id from the same element.
     */
    @Test
    void keepsTheThreeElementSeededGeneratorsApart() {
        String route = routeIds.generate(ELEMENT_ID);
        String registration = registrationIds.generate(ELEMENT_ID);
        String mapperMapping = mapperMappingIds.generate(ELEMENT_ID);

        assertNotEquals(route, registration);
        assertNotEquals(route, mapperMapping);
        assertNotEquals(registration, mapperMapping);
    }

    /** A route id that equalled the element's own id would clash with a container sub-route endpoint. */
    @Test
    void neverReturnsTheElementIdItWasGiven() {
        assertNotEquals(ELEMENT_ID, routeIds.generate(ELEMENT_ID));
    }

    @Test
    void keepsTheMappingPrefixTheTemplateExpects() {
        assertTrue(mappingIds.generate("action-1").startsWith("mapping."));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -o -pl integration-build-maven-plugin test -Dtest=DerivedIdGeneratorsTest -Dgpg.skip=true`
Expected: compilation failure — none of the four classes exist.

- [ ] **Step 3: Write the shared helper**

`DerivedIds.java`:

```java
package org.qubership.integration.platform.maven.plugin.domain.ids;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Turns a stable seed into a stable UUID, so the same input always builds the same resources. */
final class DerivedIds {
    private DerivedIds() {}

    static String from(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
```

- [ ] **Step 4: Write the four generators**

`DerivedRouteIdGenerator.java`:

```java
package org.qubership.integration.platform.maven.plugin.domain.ids;

import org.qubership.integration.platform.ids.RouteIdGenerator;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Seeds from the element id, not the element's original id. The route-id namespace already holds raw
 * element ids -- {@code ChainRouteBuilder} names container and branch routes after them -- and a
 * snapshot element id is itself derived from the original id, so seeding from the original would land
 * exactly on the endpoint a container sub-route already uses.
 *
 * <p>The discriminator keeps this apart from the other two generators seeded with an element id.
 */
@Primary
@Component
public class DerivedRouteIdGenerator implements RouteIdGenerator {
    @Override
    public String generate(String elementId) {
        return DerivedIds.from("route" + elementId);
    }
}
```

`DerivedRouteRegistrationIdGenerator.java`:

```java
package org.qubership.integration.platform.maven.plugin.domain.ids;

import org.qubership.integration.platform.ids.RouteRegistrationIdGenerator;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Seeds from the element id, which is a UUID and so unique across chains and domains on its own. The
 * discriminator keeps this apart from the other two generators seeded with an element id.
 */
@Primary
@Component
public class DerivedRouteRegistrationIdGenerator implements RouteRegistrationIdGenerator {
    @Override
    public String generate(String elementId) {
        return DerivedIds.from("registration" + elementId);
    }
}
```

`DerivedMappingIdGenerator.java`:

```java
package org.qubership.integration.platform.maven.plugin.domain.ids;

import org.qubership.integration.platform.ids.MappingIdGenerator;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Seeds from the mapping action's own id, which the parser requires and which is unique within a
 * mapping description. No discriminator is needed: an action id is 32 undashed hex characters and an
 * element id is a dashed UUID, so the two seeds cannot coincide.
 */
@Primary
@Component
public class DerivedMappingIdGenerator implements MappingIdGenerator {
    @Override
    public String generate(String actionId) {
        return "mapping.".concat(DerivedIds.from(actionId));
    }
}
```

`DerivedMapperMappingIdGenerator.java`:

```java
package org.qubership.integration.platform.maven.plugin.domain.ids;

import org.qubership.integration.platform.ids.MapperMappingIdGenerator;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Seeds from the mapper element's id, the only seed available here. The discriminator keeps this apart
 * from the route id a mapper element would otherwise share when it heads a route.
 */
@Primary
@Component
public class DerivedMapperMappingIdGenerator implements MapperMappingIdGenerator {
    @Override
    public String generate(String elementId) {
        return DerivedIds.from("mapping" + elementId);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -o -pl integration-build-maven-plugin test -Dtest=DerivedIdGeneratorsTest -Dgpg.skip=true`
Expected: PASS

- [ ] **Step 6: Verify the beans actually override the defaults**

Add to `ApplicationConfigurationTest`:

```java
    @Test
    void prefersTheDerivedIdGeneratorsOverTheRandomDefaults() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(ApplicationConfiguration.class)) {
            assertInstanceOf(DerivedRouteIdGenerator.class, context.getBean(RouteIdGenerator.class));
            assertInstanceOf(DerivedRouteRegistrationIdGenerator.class,
                context.getBean(RouteRegistrationIdGenerator.class));
            assertInstanceOf(DerivedMappingIdGenerator.class, context.getBean(MappingIdGenerator.class));
            assertInstanceOf(DerivedMapperMappingIdGenerator.class,
                context.getBean(MapperMappingIdGenerator.class));
        }
    }
```

Run: `mvn -o -pl integration-build-maven-plugin test -Dtest=ApplicationConfigurationTest -Dgpg.skip=true`
Expected: PASS. A failure here means `@Primary` is not winning and the plugin is still generating random ids.

- [ ] **Step 7: Commit**

```bash
git add integration-build-maven-plugin/src
git commit -m "feat: derived the plugin id generators from stable seeds"
```

---

### Task 9: Prove the output is reproducible

**Files:**
- Test: `integration-build-maven-plugin/src/test/java/org/qubership/integration/platform/maven/plugin/ReproducibleOutputTest.java` (create)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

This is the task that decides whether F7 is closed. Everything before it is a claim.

- [ ] **Step 1: Write the test**

The test runs the goal's task twice over one chain directory from the pipeline's test resources, into
two output directories, and compares the files byte for byte. It reaches the sources through a relative
filesystem path rather than the classpath: surefire runs with the working directory set to the module
base directory, so `../integration-build-pipeline/src/test/resources/...` resolves and no test-jar
dependency is needed.

```java
package org.qubership.integration.platform.maven.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.maven.plugin.domain.TaskRunner;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTask;
import org.qubership.integration.platform.maven.plugin.domain.tasks.BuildCRsTaskParameters;
import org.qubership.integration.platform.maven.plugin.mojos.BuildCRsOptions;
import org.qubership.integration.platform.maven.plugin.mojos.ControlPlaneType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReproducibleOutputTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void buildsIdenticalResourcesFromIdenticalSources(@TempDir Path workspace) throws IOException {
        Path sources = Path.of("..", "integration-build-pipeline", "src", "test", "resources",
            "testConfigurations", "routing", "try-catch-finally-2");

        Map<String, String> first = build(sources, workspace.resolve("first"));
        Map<String, String> second = build(sources, workspace.resolve("second"));

        assertFalse(first.isEmpty(), "the build produced no resources, so this proves nothing");
        assertEquals(first, second);
    }

    private static Map<String, String> build(Path sources, Path output) throws IOException {
        Files.createDirectories(output);
        new TaskRunner().execute(new BuildCRsTask(), BuildCRsTaskParameters.builder()
            .sourceRoots(List.of(sources.toString()))
            .outputDirectory(output.toString())
            .defaultDomain("default")
            .controlPlaneType(ControlPlaneType.ISTIO)
            .buildTimestamp(TIMESTAMP)
            .options(new BuildCRsOptions())
            .build());
        try (Stream<Path> files = Files.list(output)) {
            return files.collect(Collectors.toMap(
                file -> file.getFileName().toString(),
                file -> readString(file)));
        }
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvn -o -pl integration-build-maven-plugin test -Dtest=ReproducibleOutputTest -Dgpg.skip=true`
Expected: PASS. If it fails, the assertion message names the differing file; diff the two temp
directories to find which id or timestamp is still moving.

- [ ] **Step 3: Prove the test can fail**

Temporarily change `DerivedRouteIdGenerator.generate` to `return UUID.randomUUID().toString();`, run the
test again, and confirm it fails. Restore the method. A reproducibility test that cannot fail is worse
than none.

- [ ] **Step 4: Run the full build of both modules**

Run: `mvn -o -pl integration-build-pipeline,integration-build-maven-plugin install -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Expected: BUILD SUCCESS with every test passing and checkstyle clean.

- [ ] **Step 5: Commit**

```bash
git add integration-build-maven-plugin/src/test
git commit -m "test: asserted the plugin builds identical resources from identical sources"
```

---

## After the plan

Update `integration-build-maven-plugin/FIXES.md`: move F7 to `fixed` with the final commit, and record
that reproducibility is opt-in through `project.build.outputTimestamp`. Leave the ledger's other rows
alone.
