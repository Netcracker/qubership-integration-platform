# `cip.*` property prefix implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `qip.*` properties to `cip.*` and `QIP_*` environment variables to `CIP_*`. engine,
runtime-catalog, and sessions-management keep resolving `cip.x` from a legacy `qip.x` in any property source,
Consul included.

**Architecture:** Each Spring service gets its own `PropertyPrefixAliasEnvironmentPostProcessor`. It adds one
property source first in the chain; for `cip.x` it walks the other sources in precedence order and returns the first
`cip.x`, else `qip.x`. Every module in scope, including micro-engine, integration-build-pipeline,
integration-build-maven-plugin, and testing-service, gets a mechanical rename driven by one Perl command whose
exclusions keep meter names and Kubernetes label values intact.

**Tech Stack:** Java 21, Spring Boot 3.5.12, JUnit 5, Maven; Quarkus 3.33 (micro-engine, rename only); Go 1.22
(testing-service); APM CLI 0.26 for regenerating `AGENTS.md`.

**Spec:** `docs/superpowers/specs/2026-09-28-cip-property-prefix-design.md`

## Global Constraints

- New prefix `cip.`, legacy prefix `qip.`; new env var prefix `CIP_`, no fallback to the old `QIP_` names.
- Resolution: the first property source holding `cip.x` or `qip.x` decides; within one source `cip.x` wins.
- Unchanged: `app.prefix` / `application.prefix` and everything built from it (meter names `qip.engine.*`, error codes,
  CR names, the `qip-domain` label key, export file names), `qip_engine_app`, `qip-elements-*` indices,
  `…/schemas/product/qip/*` URIs, `qip.org/cleanup`, property *values* containing `qip`, `QIP_AI_*`, Go identifiers such
  as the `internal/qip` package, and chart or directory names such as `qip-testing-service`.
- No micro-engine `ConfigSourceInterceptor`.
- New files do not need the NetCracker copyright header.
- Checkstyle (`checkstyle/src/main/resources/checkstyle.xml`): imports grouped third-party, then `java.*`, then
  static, alphabetical within a group, blank line between groups; no star imports; braces on every `if`.
- Commit messages: unscoped Conventional Commits (`feat:`, `refactor!:`, `docs:`), ending with
  the `Co-Authored-By` trailer from the session's attribution instructions. Before the first commit, run
  `export CO_AUTHOR="Co-Authored-By: <the trailer text>"`; every commit command below passes `-m "$CO_AUTHOR"`.
- `testing-service` keeps `go 1.22` in `go.mod`; build and test from inside `testing-service/`.
- Never hand-edit a generated `AGENTS.md` or `.claude/rules/*.md`; edit `.apm/instructions/*` and run `apm compile`.
  `testing-service/AGENTS.md` is the hand-maintained exception.
- runtime-catalog and integration-build-maven-plugin resolve `qip-integration-build-pipeline:1.4.0-SNAPSHOT` from the
  reactor, so build them with `-am`.

## Review Focus

1. A legacy `qip.x` in Consul while the jar's `application.yml` now ships a `cip.x` default: the Consul value must win.
   Pinned by `legacyKeyInConsulBeatsNewDefaultInApplicationYml` in Tasks 1–3.
2. A renamed `application.yml` value such as `base-path: ${cip.camel.routes.prefix}` whose only source is a legacy
   `qip.camel.routes.prefix` in Consul: the placeholder must resolve. Pinned by
   `placeholderToNewKeyResolvesFromLegacyValue` in Tasks 1–3.
3. An old environment variable whose name matches a property path (`QIP_ISTIO_ENABLED`): Spring relaxed binding
   must still reach `cip.istio.enabled`. Pinned by `legacyEnvironmentVariableResolvesUnderTheNewPrefix` in Tasks 1–3.
4. `@ConfigurationProperties` and `Binder.bind("cip.cr.build.…")` over maps whose entries sit under `qip.` in one
   source and `cip.` in another: both entries must bind. Pinned by `mapBindsEntriesFromBothPrefixes` in Tasks 1–3.
5. A missing `spring.factories` entry makes the alias silently inert in production while every unit test still
   passes. Pinned by `isRegisteredAsEnvironmentPostProcessor` in Tasks 1–3.

## File structure

| File | Responsibility |
|---|---|
| `engine/src/main/java/org/qubership/integration/platform/engine/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java` | engine's alias (new) |
| `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java` | runtime-catalog's alias (new) |
| `sessions-management/src/main/java/org/qubership/integration/platform/sessions/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java` | sessions-management's alias (new) |
| `<service>/src/test/java/<same package>/PropertyPrefixAliasEnvironmentPostProcessorTest.java` | one test class per alias (new) |
| `<service>/src/main/resources/META-INF/spring.factories` | registers the alias (modified) |
| every `*.java`, `*.yml`, `*.yaml`, `*.properties`, `logback-spring.xml` tracked in the renamed modules | mechanical rename |
| `testing-service/cmd/testing-service/main.go`, `main_test.go`, `application.yaml`, `README.md` | `QIP_TESTING_` → `CIP_TESTING_` |
| `infrastructure/docker-compose.yml`, `infrastructure/qip-dev/charts/qip-testing-service/templates/*.yaml` | callers of `CIP_TESTING_*` |
| docs and `.apm/instructions/*` | documentation |

## The rename command

Tasks 4–7 and 9 run this command on a list of files. It is written out in full in each task. What it does:

- `^(\s*)qip:(\s*)$` → `cip:`: the YAML block key, at any indentation, keeping a trailing `\r`.
- `qip.` → `cip.` when not preceded by a letter, digit, `_`, `/`, `.`, or `-`. This leaves `*.qip.yaml`,
  `…/product/qip/…`, and `qip-…` untouched. It skips these tokens, which are meter names or label values:
  `qip.engine.http.…`, `qip.engine.chains.deployments`, `qip.engine.chain.session.…`,
  `qip.engine.chain.checkpoint.…`, and exactly `qip.domain`, `qip.bg-version`, `qip.microdomain`, `qip.bgVersion`,
  `qip.org/…`.
- `QIP_<name>` → `CIP_<name>` for the eleven in-scope variables only.

---

### Task 1: engine alias

**Files:**
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java`
- Create: `engine/src/test/java/org/qubership/integration/platform/engine/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java`
- Modify: `engine/src/main/resources/META-INF/spring.factories`

**Interfaces:**
- Consumes: nothing.
- Produces: `public class PropertyPrefixAliasEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered`
  with package-private `static final String SOURCE_NAME = "propertyPrefixAliases"`.

- [ ] **Step 1: Write the failing test**

Create `engine/src/test/java/org/qubership/integration/platform/engine/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java`:

```java
package org.qubership.integration.platform.engine.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyPrefixAliasEnvironmentPostProcessorTest {

    private final StandardEnvironment environment = new StandardEnvironment();
    private final Map<String, Object> osEnvironment = new HashMap<>();
    private final Map<String, Object> consul = new HashMap<>();
    private final Map<String, Object> applicationYml = new HashMap<>();

    // Mirrors the Spring Boot order: environment variables, then Consul, then application.yml.
    @BeforeEach
    void setUp() {
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("testEnvironment", osEnvironment));
        sources.addLast(new MapPropertySource("consul", consul));
        sources.addLast(new MapPropertySource("applicationYml", applicationYml));
        ConfigurationPropertySources.attach(environment);
        new PropertyPrefixAliasEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
    }

    @Test
    void legacyKeyResolvesUnderTheNewPrefix() {
        applicationYml.put("qip.opensearch.rollover.min_index_age", "2d");

        assertEquals("2d", environment.getProperty("cip.opensearch.rollover.min_index_age"));
    }

    @Test
    void newKeyWinsWithinOneSource() {
        consul.put("qip.opensearch.rollover.min_index_age", "legacy");
        consul.put("cip.opensearch.rollover.min_index_age", "new");

        assertEquals("new", environment.getProperty("cip.opensearch.rollover.min_index_age"));
    }

    @Test
    void legacyKeyInConsulBeatsNewDefaultInApplicationYml() {
        consul.put("qip.opensearch.rollover.min_index_age", "consul");
        applicationYml.put("cip.opensearch.rollover.min_index_age", "default");

        assertEquals("consul", environment.getProperty("cip.opensearch.rollover.min_index_age"));
    }

    @Test
    void newKeyInConsulBeatsLegacyKeyInApplicationYml() {
        consul.put("cip.opensearch.rollover.min_index_age", "consul");
        applicationYml.put("qip.opensearch.rollover.min_index_age", "default");

        assertEquals("consul", environment.getProperty("cip.opensearch.rollover.min_index_age"));
    }

    @Test
    void legacyEnvironmentVariableResolvesUnderTheNewPrefix() {
        osEnvironment.put("QIP_ISTIO_ENABLED", "false");
        applicationYml.put("cip.istio.enabled", "true");

        assertEquals("false", environment.getProperty("cip.istio.enabled"));
    }

    @Test
    void placeholderToNewKeyResolvesFromLegacyValue() {
        consul.put("qip.camel.routes.prefix", "/routes");
        applicationYml.put("cip.chains.external-routes.base-path", "${cip.camel.routes.prefix}/external");

        assertEquals("/routes/external", environment.getProperty("cip.chains.external-routes.base-path"));
    }

    @Test
    void mapBindsEntriesFromBothPrefixes() {
        consul.put("cip.cr.build.environment.NEW_VAR", "new");
        applicationYml.put("qip.cr.build.environment.OLD_VAR", "old");

        Map<String, String> bound = Binder.get(environment)
                .bind("cip.cr.build.environment", Bindable.mapOf(String.class, String.class))
                .get();

        assertEquals(Map.of("NEW_VAR", "new", "OLD_VAR", "old"), bound);
    }

    @Test
    void onlyNamesUnderTheNewPrefixAreAliased() {
        applicationYml.put("qip.opensearch.rollover.min_index_age", "2d");

        assertNull(environment.getPropertySources()
                .get(PropertyPrefixAliasEnvironmentPostProcessor.SOURCE_NAME)
                .getProperty("qip.opensearch.rollover.min_index_age"));
        assertEquals("2d", environment.getProperty("qip.opensearch.rollover.min_index_age"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void isRegisteredAsEnvironmentPostProcessor() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader())
                .contains(PropertyPrefixAliasEnvironmentPostProcessor.class.getName()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl engine -am test -Dgpg.skip=true -Dtest=PropertyPrefixAliasEnvironmentPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class PropertyPrefixAliasEnvironmentPostProcessor`.

- [ ] **Step 3: Write the implementation**

Create `engine/src/main/java/org/qubership/integration/platform/engine/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java`:

```java
package org.qubership.integration.platform.engine.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;

/**
 * Resolves {@code cip.*} properties from their legacy {@code qip.*} names.
 *
 * <p>For a lookup of {@code cip.x}, the first property source that holds {@code cip.x} or {@code qip.x} decides, and
 * within one source {@code cip.x} wins. A {@code qip.x} set in Consul therefore overrides a {@code cip.x} default
 * shipped in {@code application.yml}.
 */
public class PropertyPrefixAliasEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "propertyPrefixAliases";

    private static final String NEW_PREFIX = "cip.";
    private static final String OLD_PREFIX = "qip.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addFirst(new PrefixAliasPropertySource(environment));
    }

    // The Consul sources come from spring.config.import, so they exist once the config data processor has run.
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static final class PrefixAliasPropertySource extends EnumerablePropertySource<ConfigurableEnvironment> {

        PrefixAliasPropertySource(ConfigurableEnvironment environment) {
            super(SOURCE_NAME, environment);
        }

        // Asks each source directly, never the Environment, so a lookup cannot come back to this source.
        @Override
        public Object getProperty(String name) {
            if (!name.startsWith(NEW_PREFIX)) {
                return null;
            }
            String legacyName = OLD_PREFIX + name.substring(NEW_PREFIX.length());
            for (PropertySource<?> source : getSource().getPropertySources()) {
                if (!isDelegate(source)) {
                    continue;
                }
                Object value = source.getProperty(name);
                if (value == null) {
                    value = source.getProperty(legacyName);
                }
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        // Lists every cip.* and qip.* name under the new prefix, so binding takes each one from this source.
        @Override
        public String[] getPropertyNames() {
            return getSource().getPropertySources().stream()
                    .filter(source -> isDelegate(source) && source instanceof EnumerablePropertySource)
                    .flatMap(source -> Arrays.stream(((EnumerablePropertySource<?>) source).getPropertyNames()))
                    .filter(name -> name.startsWith(NEW_PREFIX) || name.startsWith(OLD_PREFIX))
                    .map(name -> NEW_PREFIX + name.substring(OLD_PREFIX.length()))
                    .distinct()
                    .toArray(String[]::new);
        }

        // The attached configurationProperties source wraps every other source, this one included.
        private boolean isDelegate(PropertySource<?> source) {
            return source != this && !ConfigurationPropertySources.isAttachedConfigurationPropertySource(source);
        }
    }
}
```

`NEW_PREFIX` and `OLD_PREFIX` have the same length, which `getPropertyNames` relies on when it strips either one.

- [ ] **Step 4: Register the post-processor**

Replace the contents of `engine/src/main/resources/META-INF/spring.factories` with:

```properties
org.springframework.boot.context.config.ConfigDataLocationResolver=\
org.qubership.integration.platform.engine.consul.ConsulUrlWithSchemaDataLocationResolver
org.springframework.boot.env.EnvironmentPostProcessor=\
org.qubership.integration.platform.engine.configuration.PropertyPrefixAliasEnvironmentPostProcessor
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl engine -am test -Dgpg.skip=true -Dtest=PropertyPrefixAliasEnvironmentPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 9, Failures: 0, Errors: 0`, `BUILD SUCCESS` (checkstyle runs at compile).

- [ ] **Step 6: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java \
        engine/src/test/java/org/qubership/integration/platform/engine/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java \
        engine/src/main/resources/META-INF/spring.factories
git commit -m "feat: resolve cip.* engine properties from legacy qip.* names" \
           -m "$CO_AUTHOR"
```

---

### Task 2: runtime-catalog alias

**Files:**
- Create: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java`
- Create: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java`
- Modify: `runtime-catalog/src/main/resources/META-INF/spring.factories`

**Interfaces:**
- Consumes: nothing.
- Produces: `org.qubership.integration.platform.runtime.catalog.configuration.PropertyPrefixAliasEnvironmentPostProcessor`
  with package-private `SOURCE_NAME = "propertyPrefixAliases"`.

- [ ] **Step 1: Write the failing test**

Create `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java`:

```java
package org.qubership.integration.platform.runtime.catalog.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyPrefixAliasEnvironmentPostProcessorTest {

    private final StandardEnvironment environment = new StandardEnvironment();
    private final Map<String, Object> osEnvironment = new HashMap<>();
    private final Map<String, Object> consul = new HashMap<>();
    private final Map<String, Object> applicationYml = new HashMap<>();

    // Mirrors the Spring Boot order: environment variables, then Consul, then application.yml.
    @BeforeEach
    void setUp() {
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("testEnvironment", osEnvironment));
        sources.addLast(new MapPropertySource("consul", consul));
        sources.addLast(new MapPropertySource("applicationYml", applicationYml));
        ConfigurationPropertySources.attach(environment);
        new PropertyPrefixAliasEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
    }

    @Test
    void legacyKeyResolvesUnderTheNewPrefix() {
        applicationYml.put("qip.actions-log.cleanup.cron", "0 0 1 * * ?");

        assertEquals("0 0 1 * * ?", environment.getProperty("cip.actions-log.cleanup.cron"));
    }

    @Test
    void newKeyWinsWithinOneSource() {
        consul.put("qip.actions-log.cleanup.cron", "legacy");
        consul.put("cip.actions-log.cleanup.cron", "new");

        assertEquals("new", environment.getProperty("cip.actions-log.cleanup.cron"));
    }

    @Test
    void legacyKeyInConsulBeatsNewDefaultInApplicationYml() {
        consul.put("qip.actions-log.cleanup.cron", "consul");
        applicationYml.put("cip.actions-log.cleanup.cron", "default");

        assertEquals("consul", environment.getProperty("cip.actions-log.cleanup.cron"));
    }

    @Test
    void newKeyInConsulBeatsLegacyKeyInApplicationYml() {
        consul.put("cip.actions-log.cleanup.cron", "consul");
        applicationYml.put("qip.actions-log.cleanup.cron", "default");

        assertEquals("consul", environment.getProperty("cip.actions-log.cleanup.cron"));
    }

    @Test
    void legacyEnvironmentVariableResolvesUnderTheNewPrefix() {
        osEnvironment.put("QIP_ISTIO_ENABLED", "false");
        applicationYml.put("cip.istio.enabled", "true");

        assertEquals("false", environment.getProperty("cip.istio.enabled"));
    }

    @Test
    void placeholderToNewKeyResolvesFromLegacyValue() {
        consul.put("qip.camel.routes.prefix", "/routes");
        applicationYml.put("cip.chains.external-routes.base-path", "${cip.camel.routes.prefix}/external");

        assertEquals("/routes/external", environment.getProperty("cip.chains.external-routes.base-path"));
    }

    @Test
    void mapBindsEntriesFromBothPrefixes() {
        consul.put("cip.cr.build.environment.NEW_VAR", "new");
        applicationYml.put("qip.cr.build.environment.OLD_VAR", "old");

        Map<String, String> bound = Binder.get(environment)
                .bind("cip.cr.build.environment", Bindable.mapOf(String.class, String.class))
                .get();

        assertEquals(Map.of("NEW_VAR", "new", "OLD_VAR", "old"), bound);
    }

    @Test
    void onlyNamesUnderTheNewPrefixAreAliased() {
        applicationYml.put("qip.actions-log.cleanup.cron", "0 0 1 * * ?");

        assertNull(environment.getPropertySources()
                .get(PropertyPrefixAliasEnvironmentPostProcessor.SOURCE_NAME)
                .getProperty("qip.actions-log.cleanup.cron"));
        assertEquals("0 0 1 * * ?", environment.getProperty("qip.actions-log.cleanup.cron"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void isRegisteredAsEnvironmentPostProcessor() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader())
                .contains(PropertyPrefixAliasEnvironmentPostProcessor.class.getName()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl runtime-catalog -am test -Dgpg.skip=true -Dtest=PropertyPrefixAliasEnvironmentPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class PropertyPrefixAliasEnvironmentPostProcessor`.

- [ ] **Step 3: Write the implementation**

Create `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java`:

```java
package org.qubership.integration.platform.runtime.catalog.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;

/**
 * Resolves {@code cip.*} properties from their legacy {@code qip.*} names.
 *
 * <p>For a lookup of {@code cip.x}, the first property source that holds {@code cip.x} or {@code qip.x} decides, and
 * within one source {@code cip.x} wins. A {@code qip.x} set in Consul therefore overrides a {@code cip.x} default
 * shipped in {@code application.yml}.
 */
public class PropertyPrefixAliasEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "propertyPrefixAliases";

    private static final String NEW_PREFIX = "cip.";
    private static final String OLD_PREFIX = "qip.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addFirst(new PrefixAliasPropertySource(environment));
    }

    // The Consul sources come from spring.config.import, so they exist once the config data processor has run.
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static final class PrefixAliasPropertySource extends EnumerablePropertySource<ConfigurableEnvironment> {

        PrefixAliasPropertySource(ConfigurableEnvironment environment) {
            super(SOURCE_NAME, environment);
        }

        // Asks each source directly, never the Environment, so a lookup cannot come back to this source.
        @Override
        public Object getProperty(String name) {
            if (!name.startsWith(NEW_PREFIX)) {
                return null;
            }
            String legacyName = OLD_PREFIX + name.substring(NEW_PREFIX.length());
            for (PropertySource<?> source : getSource().getPropertySources()) {
                if (!isDelegate(source)) {
                    continue;
                }
                Object value = source.getProperty(name);
                if (value == null) {
                    value = source.getProperty(legacyName);
                }
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        // Lists every cip.* and qip.* name under the new prefix, so binding takes each one from this source.
        @Override
        public String[] getPropertyNames() {
            return getSource().getPropertySources().stream()
                    .filter(source -> isDelegate(source) && source instanceof EnumerablePropertySource)
                    .flatMap(source -> Arrays.stream(((EnumerablePropertySource<?>) source).getPropertyNames()))
                    .filter(name -> name.startsWith(NEW_PREFIX) || name.startsWith(OLD_PREFIX))
                    .map(name -> NEW_PREFIX + name.substring(OLD_PREFIX.length()))
                    .distinct()
                    .toArray(String[]::new);
        }

        // The attached configurationProperties source wraps every other source, this one included.
        private boolean isDelegate(PropertySource<?> source) {
            return source != this && !ConfigurationPropertySources.isAttachedConfigurationPropertySource(source);
        }
    }
}
```

- [ ] **Step 4: Register the post-processor**

Replace the contents of `runtime-catalog/src/main/resources/META-INF/spring.factories` with:

```properties
org.springframework.boot.context.config.ConfigDataLocationResolver=\
org.qubership.integration.platform.runtime.catalog.consul.ConsulUrlWithSchemaDataLocationResolver
org.springframework.boot.env.EnvironmentPostProcessor=\
org.qubership.integration.platform.runtime.catalog.configuration.PropertyPrefixAliasEnvironmentPostProcessor
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl runtime-catalog -am test -Dgpg.skip=true -Dtest=PropertyPrefixAliasEnvironmentPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 9, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java \
        runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java \
        runtime-catalog/src/main/resources/META-INF/spring.factories
git commit -m "feat: resolve cip.* runtime-catalog properties from legacy qip.* names" \
           -m "$CO_AUTHOR"
```

---

### Task 3: sessions-management alias

**Files:**
- Create: `sessions-management/src/main/java/org/qubership/integration/platform/sessions/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java`
- Create: `sessions-management/src/test/java/org/qubership/integration/platform/sessions/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java`
- Modify: `sessions-management/src/main/resources/META-INF/spring.factories`

**Interfaces:**
- Consumes: nothing.
- Produces: `org.qubership.integration.platform.sessions.configuration.PropertyPrefixAliasEnvironmentPostProcessor`
  with package-private `SOURCE_NAME = "propertyPrefixAliases"`.

- [ ] **Step 1: Write the failing test**

Create `sessions-management/src/test/java/org/qubership/integration/platform/sessions/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java`:

```java
package org.qubership.integration.platform.sessions.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyPrefixAliasEnvironmentPostProcessorTest {

    private final StandardEnvironment environment = new StandardEnvironment();
    private final Map<String, Object> osEnvironment = new HashMap<>();
    private final Map<String, Object> consul = new HashMap<>();
    private final Map<String, Object> applicationYml = new HashMap<>();

    // Mirrors the Spring Boot order: environment variables, then Consul, then application.yml.
    @BeforeEach
    void setUp() {
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("testEnvironment", osEnvironment));
        sources.addLast(new MapPropertySource("consul", consul));
        sources.addLast(new MapPropertySource("applicationYml", applicationYml));
        ConfigurationPropertySources.attach(environment);
        new PropertyPrefixAliasEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
    }

    @Test
    void legacyKeyResolvesUnderTheNewPrefix() {
        applicationYml.put("qip.opensearch.index.prefix", "legacy-prefix");

        assertEquals("legacy-prefix", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void newKeyWinsWithinOneSource() {
        consul.put("qip.opensearch.index.prefix", "legacy");
        consul.put("cip.opensearch.index.prefix", "new");

        assertEquals("new", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void legacyKeyInConsulBeatsNewDefaultInApplicationYml() {
        consul.put("qip.opensearch.index.prefix", "consul");
        applicationYml.put("cip.opensearch.index.prefix", "default");

        assertEquals("consul", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void newKeyInConsulBeatsLegacyKeyInApplicationYml() {
        consul.put("cip.opensearch.index.prefix", "consul");
        applicationYml.put("qip.opensearch.index.prefix", "default");

        assertEquals("consul", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void legacyEnvironmentVariableResolvesUnderTheNewPrefix() {
        osEnvironment.put("QIP_OPENSEARCH_INDEX_PREFIX", "from-env");
        applicationYml.put("cip.opensearch.index.prefix", "default");

        assertEquals("from-env", environment.getProperty("cip.opensearch.index.prefix"));
    }

    @Test
    void placeholderToNewKeyResolvesFromLegacyValue() {
        consul.put("qip.sessions.bulk-request.max-size-kb", "512");
        applicationYml.put("cip.sessions.bulk-request.payload-size-threshold-kb",
                "${cip.sessions.bulk-request.max-size-kb}");

        assertEquals("512", environment.getProperty("cip.sessions.bulk-request.payload-size-threshold-kb"));
    }

    @Test
    void mapBindsEntriesFromBothPrefixes() {
        consul.put("cip.internal-services.catalog", "new");
        applicationYml.put("qip.internal-services.engine", "old");

        Map<String, String> bound = Binder.get(environment)
                .bind("cip.internal-services", Bindable.mapOf(String.class, String.class))
                .get();

        assertEquals(Map.of("catalog", "new", "engine", "old"), bound);
    }

    @Test
    void onlyNamesUnderTheNewPrefixAreAliased() {
        applicationYml.put("qip.opensearch.index.prefix", "legacy-prefix");

        assertNull(environment.getPropertySources()
                .get(PropertyPrefixAliasEnvironmentPostProcessor.SOURCE_NAME)
                .getProperty("qip.opensearch.index.prefix"));
        assertEquals("legacy-prefix", environment.getProperty("qip.opensearch.index.prefix"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void isRegisteredAsEnvironmentPostProcessor() {
        assertTrue(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader())
                .contains(PropertyPrefixAliasEnvironmentPostProcessor.class.getName()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl sessions-management -am test -Dgpg.skip=true -Dtest=PropertyPrefixAliasEnvironmentPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class PropertyPrefixAliasEnvironmentPostProcessor`.

- [ ] **Step 3: Write the implementation**

Create `sessions-management/src/main/java/org/qubership/integration/platform/sessions/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java`:

```java
package org.qubership.integration.platform.sessions.configuration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;

/**
 * Resolves {@code cip.*} properties from their legacy {@code qip.*} names.
 *
 * <p>For a lookup of {@code cip.x}, the first property source that holds {@code cip.x} or {@code qip.x} decides, and
 * within one source {@code cip.x} wins. A {@code qip.x} set in Consul therefore overrides a {@code cip.x} default
 * shipped in {@code application.yml}.
 */
public class PropertyPrefixAliasEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "propertyPrefixAliases";

    private static final String NEW_PREFIX = "cip.";
    private static final String OLD_PREFIX = "qip.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addFirst(new PrefixAliasPropertySource(environment));
    }

    // The Consul sources come from spring.config.import, so they exist once the config data processor has run.
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static final class PrefixAliasPropertySource extends EnumerablePropertySource<ConfigurableEnvironment> {

        PrefixAliasPropertySource(ConfigurableEnvironment environment) {
            super(SOURCE_NAME, environment);
        }

        // Asks each source directly, never the Environment, so a lookup cannot come back to this source.
        @Override
        public Object getProperty(String name) {
            if (!name.startsWith(NEW_PREFIX)) {
                return null;
            }
            String legacyName = OLD_PREFIX + name.substring(NEW_PREFIX.length());
            for (PropertySource<?> source : getSource().getPropertySources()) {
                if (!isDelegate(source)) {
                    continue;
                }
                Object value = source.getProperty(name);
                if (value == null) {
                    value = source.getProperty(legacyName);
                }
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        // Lists every cip.* and qip.* name under the new prefix, so binding takes each one from this source.
        @Override
        public String[] getPropertyNames() {
            return getSource().getPropertySources().stream()
                    .filter(source -> isDelegate(source) && source instanceof EnumerablePropertySource)
                    .flatMap(source -> Arrays.stream(((EnumerablePropertySource<?>) source).getPropertyNames()))
                    .filter(name -> name.startsWith(NEW_PREFIX) || name.startsWith(OLD_PREFIX))
                    .map(name -> NEW_PREFIX + name.substring(OLD_PREFIX.length()))
                    .distinct()
                    .toArray(String[]::new);
        }

        // The attached configurationProperties source wraps every other source, this one included.
        private boolean isDelegate(PropertySource<?> source) {
            return source != this && !ConfigurationPropertySources.isAttachedConfigurationPropertySource(source);
        }
    }
}
```

- [ ] **Step 4: Register the post-processor**

Replace the contents of `sessions-management/src/main/resources/META-INF/spring.factories` with:

```properties
org.springframework.boot.context.config.ConfigDataLocationResolver=\
org.qubership.integration.platform.sessions.consul.ConsulUrlWithSchemaDataLocationResolver
org.springframework.boot.env.EnvironmentPostProcessor=\
org.qubership.integration.platform.sessions.configuration.PropertyPrefixAliasEnvironmentPostProcessor
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl sessions-management -am test -Dgpg.skip=true -Dtest=PropertyPrefixAliasEnvironmentPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 9, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add sessions-management/src/main/java/org/qubership/integration/platform/sessions/configuration/PropertyPrefixAliasEnvironmentPostProcessor.java \
        sessions-management/src/test/java/org/qubership/integration/platform/sessions/configuration/PropertyPrefixAliasEnvironmentPostProcessorTest.java \
        sessions-management/src/main/resources/META-INF/spring.factories
git commit -m "feat: resolve cip.* sessions-management properties from legacy qip.* names" \
           -m "$CO_AUTHOR"
```

---

### Task 4: engine rename

**Files:**
- Modify: every tracked `*.java`, `*.yml`, `*.yaml`, `*.properties`, and `logback-spring.xml` under `engine/` that
  holds a `qip.` key or `QIP_ISTIO_*` (among them `application.yml`, `application-development.yml`,
  `logback-spring.xml`, about 50 Java sources, `TestingHttpComponentConfigurationTest`, `TestingServiceConditionTest`).
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/configuration/opensearch/OpenSearchStandaloneKafkaAutoConfiguration.java:22`

**Interfaces:**
- Consumes: Task 1's alias, so legacy `qip.*` in Consul keeps working after this rename.
- Produces: engine reads only `cip.*` keys and `CIP_ISTIO_ENABLED`, `CIP_ISTIO_HOST_RESOURCES_ENABLED`.

- [ ] **Step 1: Run the rename**

From the repository root, in Git Bash:

```bash
git ls-files engine | grep -E '(\.(java|ya?ml|properties)|logback-spring\.xml)$' | xargs perl -pi -e '
  s/^(\s*)qip:(\s*)$/$1cip:$2/;
  s/(?<![A-Za-z0-9_\/.-])qip\.(?!engine\.(?:http\.|chains\.deployments|chain\.(?:session|checkpoint)\.)|(?:domain|bg-version|microdomain)(?![.\w-])|bgVersion|org\/)/cip./g;
  s/\bQIP_(ISTIO_ENABLED|ISTIO_HOST_RESOURCES_ENABLED|EXPORT_LEGACY_FORMAT|EXPORT_LEGACY_RESOURCE_NAMES|EXPORT_REMOVE_UNUSED_SPECS|EGRESS_GATEWAY_URL|REGISTER_INGRESS_CHAIN_ROUTES|REGISTER_EGRESS_CHAIN_ROUTES|CHAINS_CONFIGURATION_URL|LIBRARIES_PATH|ENGINE_DOMAIN)\b/CIP_$1/g;
'
```

- [ ] **Step 2: Fix the bare prefix the command cannot see**

In `OpenSearchStandaloneKafkaAutoConfiguration.java` line 22, change `prefix = "qip"` to `prefix = "cip"`:

```java
@ConditionalOnProperty(prefix = "cip", name = {"opensearch.kafka-client.enabled", "standalone"}, havingValue = "true")
```

- [ ] **Step 3: Verify nothing in scope is left**

Run:

```bash
git ls-files engine | grep -E '(\.(java|ya?ml|properties)|logback-spring\.xml)$' | xargs grep -nE '(^|[^A-Za-z0-9_/.-])qip\.|^\s*qip:\s*$|prefix = "qip"|QIP_ISTIO'
```

Expected: no output. Then `git diff --stat engine` and read `git diff engine/src/main/resources`: every changed line
swaps `qip` for `cip` in a key, a `${…}` reference, or `QIP_ISTIO_*`, and nothing else.

- [ ] **Step 4: Build and test engine**

Run: `mvn -pl engine -am test -Dgpg.skip=true`
Expected: `BUILD SUCCESS`; `TestingServiceConditionTest` passes against the renamed `cip.testing.*` keys.

- [ ] **Step 5: Commit**

```bash
git add -u engine
git commit -m "refactor!: rename engine qip.* properties to cip.*" \
           -m "The alias added earlier still resolves qip.* keys from Consul and the other property sources." \
           -m "BREAKING CHANGE: QIP_ISTIO_ENABLED and QIP_ISTIO_HOST_RESOURCES_ENABLED are now CIP_ISTIO_ENABLED and CIP_ISTIO_HOST_RESOURCES_ENABLED." \
           -m "$CO_AUTHOR"
```

---

### Task 5: sessions-management rename

**Files:**
- Modify: `sessions-management/src/main/resources/application.yaml`, `application-development.yml`,
  `logback-spring.xml`, and the Java sources holding `qip.` keys (`OpenSearchStandaloneAutoConfiguration`,
  `OpenSearchDefaultAutoConfiguration`, `FakeMicroserviceMSInfoProvider`).
- Modify: `sessions-management/src/main/java/org/qubership/integration/platform/sessions/properties/PropertiesConstants.java:20`

**Interfaces:**
- Consumes: Task 3's alias.
- Produces: `PropertiesConstants.PROPERTIES_ROOT == "cip"`.

- [ ] **Step 1: Run the rename**

```bash
git ls-files sessions-management | grep -E '(\.(java|ya?ml|properties)|logback-spring\.xml)$' | xargs perl -pi -e '
  s/^(\s*)qip:(\s*)$/$1cip:$2/;
  s/(?<![A-Za-z0-9_\/.-])qip\.(?!engine\.(?:http\.|chains\.deployments|chain\.(?:session|checkpoint)\.)|(?:domain|bg-version|microdomain)(?![.\w-])|bgVersion|org\/)/cip./g;
  s/\bQIP_(ISTIO_ENABLED|ISTIO_HOST_RESOURCES_ENABLED|EXPORT_LEGACY_FORMAT|EXPORT_LEGACY_RESOURCE_NAMES|EXPORT_REMOVE_UNUSED_SPECS|EGRESS_GATEWAY_URL|REGISTER_INGRESS_CHAIN_ROUTES|REGISTER_EGRESS_CHAIN_ROUTES|CHAINS_CONFIGURATION_URL|LIBRARIES_PATH|ENGINE_DOMAIN)\b/CIP_$1/g;
'
```

- [ ] **Step 2: Change the properties root**

In `PropertiesConstants.java` line 20:

```java
    public static final String PROPERTIES_ROOT = "cip";
```

- [ ] **Step 3: Verify nothing in scope is left**

Run:

```bash
git ls-files sessions-management | grep -E '(\.(java|ya?ml|properties)|logback-spring\.xml)$' | xargs grep -nE '(^|[^A-Za-z0-9_/.-])qip\.|^\s*qip:\s*$|PROPERTIES_ROOT = "qip"'
```

Expected: no output.

- [ ] **Step 4: Build and test sessions-management**

Run: `mvn -pl sessions-management -am test -Dgpg.skip=true`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -u sessions-management
git commit -m "refactor: rename sessions-management qip.* properties to cip.*" \
           -m "The alias added earlier still resolves qip.* keys from Consul and the other property sources." \
           -m "$CO_AUTHOR"
```

---

### Task 6: runtime-catalog, integration-build-pipeline, and integration-build-maven-plugin rename

These three change together: runtime-catalog loads the pipeline's builders as beans, so both must read the same
prefix, and the plugin runs the same builders against its own `application.yml`.

**Files:**
- Modify: tracked `*.java`, `*.yml`, `*.yaml`, `*.properties`, `logback-spring.xml` under `runtime-catalog/`,
  `integration-build-pipeline/`, and `integration-build-maven-plugin/` that hold a `qip.` key or an in-scope `QIP_*`
  (among them `runtime-catalog/src/main/resources/application.yml`, `application-development.yml`,
  `src/test/resources/application-test.yml`, `ApplicationJsonSchemaProperties`, `DomainProperties`,
  `ResourceBuildOptionsProvider`, `MicroDomainResourceBuildContextFactory` Javadoc,
  `SnapshotBundleProducerIntegrationTest`, the pipeline builders, `ElementDescriptorProperties`,
  `CamelKIntegrationResourceBuilder`, `integration-build-maven-plugin/src/main/resources/application.yml`).

**Interfaces:**
- Consumes: Task 2's alias.
- Produces: `CamelKIntegrationResourceBuilder` writes `CIP_ENGINE_DOMAIN` and `CIP_CHAINS_CONFIGURATION_URL`, which
  Task 7's micro-engine reads.

- [ ] **Step 1: Run the rename**

```bash
git ls-files runtime-catalog integration-build-pipeline integration-build-maven-plugin | grep -E '(\.(java|ya?ml|properties)|logback-spring\.xml)$' | xargs perl -pi -e '
  s/^(\s*)qip:(\s*)$/$1cip:$2/;
  s/(?<![A-Za-z0-9_\/.-])qip\.(?!engine\.(?:http\.|chains\.deployments|chain\.(?:session|checkpoint)\.)|(?:domain|bg-version|microdomain)(?![.\w-])|bgVersion|org\/)/cip./g;
  s/\bQIP_(ISTIO_ENABLED|ISTIO_HOST_RESOURCES_ENABLED|EXPORT_LEGACY_FORMAT|EXPORT_LEGACY_RESOURCE_NAMES|EXPORT_REMOVE_UNUSED_SPECS|EGRESS_GATEWAY_URL|REGISTER_INGRESS_CHAIN_ROUTES|REGISTER_EGRESS_CHAIN_ROUTES|CHAINS_CONFIGURATION_URL|LIBRARIES_PATH|ENGINE_DOMAIN)\b/CIP_$1/g;
'
```

- [ ] **Step 2: Verify only the intended tokens are left**

Run:

```bash
git ls-files runtime-catalog integration-build-pipeline integration-build-maven-plugin | grep -E '(\.(java|ya?ml|properties)|logback-spring\.xml)$' | xargs grep -nE '(^|[^A-Za-z0-9_/.-])qip\.|^\s*qip:\s*$|\bQIP_(ISTIO|EXPORT|EGRESS|REGISTER|CHAINS_CONFIGURATION|LIBRARIES|ENGINE_DOMAIN)'
```

Expected: exactly these label values in tests, nothing else:

```text
integration-build-pipeline/src/test/java/.../EgressRouteResourceBuilderTest.java:53:  ... "qip.domain");
integration-build-pipeline/src/test/java/.../EgressRouteResourceBuilderTest.java:54:  ... "qip.bg-version");
runtime-catalog/src/test/java/.../KubeOperatorQueryTest.java:229:  ... "qip.microdomain" ...
runtime-catalog/src/test/java/.../KubeOperatorQueryTest.java:233:  ... qip.microdomain=default ...
runtime-catalog/src/test/java/.../MicroDomainServiceTest.java:106,107,252,327,329,344,401,416,419  (qip.domain, qip.bgVersion, qip.org/cleanup)
```

- [ ] **Step 3: Build and test all three**

Run: `mvn -pl runtime-catalog,integration-build-maven-plugin -am install -Dgpg.skip=true`
Expected: `BUILD SUCCESS` for `qip-integration-build-pipeline`, `integration-build-maven-plugin`, and runtime-catalog.

- [ ] **Step 4: Commit**

```bash
git add -u runtime-catalog integration-build-pipeline integration-build-maven-plugin
git commit -m "refactor!: rename catalog and build pipeline qip.* properties to cip.*" \
           -m "runtime-catalog loads the pipeline builders as beans, so the three modules move together. The runtime-catalog alias still resolves qip.* keys from Consul. The pipeline must be released before runtime-catalog." \
           -m "BREAKING CHANGE: QIP_EXPORT_LEGACY_FORMAT, QIP_EXPORT_LEGACY_RESOURCE_NAMES, QIP_EXPORT_REMOVE_UNUSED_SPECS, QIP_EGRESS_GATEWAY_URL, QIP_ISTIO_ENABLED, QIP_ISTIO_HOST_RESOURCES_ENABLED, QIP_REGISTER_INGRESS_CHAIN_ROUTES, and QIP_REGISTER_EGRESS_CHAIN_ROUTES are renamed to CIP_*. Micro-engine pods now receive CIP_ENGINE_DOMAIN and CIP_CHAINS_CONFIGURATION_URL, so runtime-catalog and micro-engine upgrade together." \
           -m "$CO_AUTHOR"
```

---

### Task 7: micro-engine rename

**Files:**
- Modify: tracked `*.java`, `*.yml`, `*.yaml`, `*.properties` under `micro-engine/` that hold a `qip.` key or
  `QIP_CHAINS_CONFIGURATION_URL`, `QIP_LIBRARIES_PATH`, `QIP_ENGINE_DOMAIN` (among them `application.yml`,
  `application-development.yml`, `StartupErrorHandlingConfiguration`, `OpenSearchProperties`, `PostgresProperties`,
  `ControlPlaneServiceProperties`, `RoutesRegistrator`, `LogFormatInterceptor`, `DeploymentTestProfile`).
- Modify: `micro-engine/src/test/java/org/qubership/integration/platform/engine/service/testing/EndpointMockTestingServiceTest.java:374,376`

**Interfaces:**
- Consumes: Task 6's `CIP_ENGINE_DOMAIN` and `CIP_CHAINS_CONFIGURATION_URL`.
- Produces: micro-engine reads only `cip.*`. There is no alias, per the spec.

- [ ] **Step 1: Run the rename**

```bash
git ls-files micro-engine | grep -E '\.(java|ya?ml|properties)$' | xargs perl -pi -e '
  s/^(\s*)qip:(\s*)$/$1cip:$2/;
  s/(?<![A-Za-z0-9_\/.-])qip\.(?!engine\.(?:http\.|chains\.deployments|chain\.(?:session|checkpoint)\.)|(?:domain|bg-version|microdomain)(?![.\w-])|bgVersion|org\/)/cip./g;
  s/\bQIP_(ISTIO_ENABLED|ISTIO_HOST_RESOURCES_ENABLED|EXPORT_LEGACY_FORMAT|EXPORT_LEGACY_RESOURCE_NAMES|EXPORT_REMOVE_UNUSED_SPECS|EGRESS_GATEWAY_URL|REGISTER_INGRESS_CHAIN_ROUTES|REGISTER_EGRESS_CHAIN_ROUTES|CHAINS_CONFIGURATION_URL|LIBRARIES_PATH|ENGINE_DOMAIN)\b/CIP_$1/g;
'
```

- [ ] **Step 2: Fix the YAML root the test reads by name**

`EndpointMockTestingServiceTest` walks the parsed `application.yml` from its root key. Change both
`root.get("qip")` calls on lines 374 and 376 to `root.get("cip")`:

```java
                if (root != null && root.get("cip") instanceof Map) {
```

```java
                            (Map<String, Object>) ((Map<String, Object>) root.get("cip")).get("testing");
```

- [ ] **Step 3: Verify only the meter names are left**

Run:

```bash
git ls-files micro-engine | grep -E '\.(java|ya?ml|properties)$' | xargs grep -nE '(^|[^A-Za-z0-9_/.-])qip\.|^\s*qip:\s*$|get\("qip"\)|\bQIP_(CHAINS_CONFIGURATION|LIBRARIES|ENGINE_DOMAIN)'
```

Expected: only `MetricsStoreTest.java` lines 63, 99, 189, 200, and 204 (`qip.engine.http…`, `qip.engine.chains.deployments`,
`qip.engine.chain.session.size`, `qip.engine.chain.checkpoint.size`).

- [ ] **Step 4: Build and test micro-engine**

Run: `mvn -pl micro-engine -am test -Dgpg.skip=true`
Expected: `BUILD SUCCESS`. The build needs GitHub Packages credentials in `~/.m2/settings.xml`; a resolution failure
for `com.netcracker.cloud.*` is a credential problem, not a code problem.

- [ ] **Step 5: Commit**

```bash
git add -u micro-engine
git commit -m "refactor!: rename micro-engine qip.* properties to cip.*" \
           -m "BREAKING CHANGE: micro-engine no longer reads qip.* keys, including from Consul, and reads CIP_CHAINS_CONFIGURATION_URL, CIP_LIBRARIES_PATH, and CIP_ENGINE_DOMAIN instead of the QIP_ names." \
           -m "$CO_AUTHOR"
```

---

### Task 8: testing-service environment prefix

Read `testing-service/AGENTS.md` before this task.

**Files:**
- Modify: `testing-service/cmd/testing-service/main.go` (`envPrefix` on line 62, comments on lines 128, 166, 192)
- Modify: `testing-service/cmd/testing-service/main_test.go`
- Modify: `testing-service/application.yaml` (comments on lines 4, 5, 13)
- Modify: `testing-service/README.md`
- Modify: `infrastructure/docker-compose.yml:155-157`
- Modify: `infrastructure/qip-dev/charts/qip-testing-service/templates/qip-testing-service-deployment.yaml`
- Modify: `infrastructure/qip-dev/charts/qip-testing-service/templates/testing-service-env-configmap.yaml`

**Interfaces:**
- Consumes: nothing.
- Produces: the binary reads `CIP_TESTING_*`; `envKey("CIP_TESTING_POSTGRES_DSN") == "postgres.dsn"`.

- [ ] **Step 1: Update the tests first**

```bash
perl -pi -e 's/\bQIP_TESTING_/CIP_TESTING_/g' testing-service/cmd/testing-service/main_test.go
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd testing-service && go test ./cmd/...`
Expected: FAIL. `envKey("CIP_TESTING_POSTGRES_USER")` returns a key that is not `postgres.user`, and the tests that
set `CIP_TESTING_POSTGRES_DSN` do not see it.

- [ ] **Step 3: Rename the prefix everywhere else**

```bash
perl -pi -e 's/\bQIP_TESTING_/CIP_TESTING_/g' \
  testing-service/cmd/testing-service/main.go \
  testing-service/application.yaml \
  testing-service/README.md \
  infrastructure/docker-compose.yml \
  infrastructure/qip-dev/charts/qip-testing-service/templates/qip-testing-service-deployment.yaml \
  infrastructure/qip-dev/charts/qip-testing-service/templates/testing-service-env-configmap.yaml
```

This leaves `envPrefix = "CIP_TESTING_"` on `main.go` line 62. The ConfigMap keys and the deployment's
`configMapKeyRef.key` values change together, so the chart stays consistent.

- [ ] **Step 4: Verify, build, and test**

Run:

```bash
grep -rnE 'QIP_TESTING_' testing-service infrastructure --exclude-dir=node_modules
cd testing-service && go build ./... && go test ./... && grep -n '^go ' go.mod
```

Expected: no `QIP_TESTING_` matches, build and tests pass, and `go.mod` still says `go 1.22`. If `AGENTS.md` names a
`QIP_TESTING_` variable (it does not at the time of writing), rename it there too.

- [ ] **Step 5: Commit**

```bash
git add -u testing-service infrastructure/docker-compose.yml infrastructure/qip-dev/charts/qip-testing-service
git commit -m "refactor!: rename testing-service QIP_TESTING_ variables to CIP_TESTING_" \
           -m "BREAKING CHANGE: the testing service reads CIP_TESTING_* and ignores QIP_TESTING_*." \
           -m "$CO_AUTHOR"
```

---

### Task 9: documentation and APM instructions

Invoke the `english-developer-style` skill for the new instruction paragraph and the `apm-authoring` skill before
editing `.apm/instructions/`.

**Files:**
- Modify: `runtime-catalog/README.md`, `integration-build-maven-plugin/README.md`
- Modify: `infrastructure/README.md`, `infrastructure/qip-dev/README.md`
- Modify: `help/docs/01__Chains/8__Testing/testing.md`,
  `help/docs/06__Functionality_and_Features/6__Retention_Settings/retention_settings.md`
- Modify: `.apm/instructions/engine.instructions.md`, `micro-engine.instructions.md`,
  `infrastructure.instructions.md`, `runtime-catalog.instructions.md`, `sessions-management.instructions.md`
- Regenerated by `apm compile`: `engine/AGENTS.md`, `micro-engine/AGENTS.md`, `infrastructure/AGENTS.md`,
  `runtime-catalog/AGENTS.md`, `sessions-management/AGENTS.md`, root `AGENTS.md`, `.claude/rules/*.md`,
  `.cursor/rules/*.mdc`

**Interfaces:**
- Consumes: the key and variable names from Tasks 4–8.
- Produces: nothing code depends on.

- [ ] **Step 1: Run the rename on the docs**

```bash
perl -pi -e '
  s/^(\s*)qip:(\s*)$/$1cip:$2/;
  s/(?<![A-Za-z0-9_\/.-])qip\.(?!engine\.(?:http\.|chains\.deployments|chain\.(?:session|checkpoint)\.)|(?:domain|bg-version|microdomain)(?![.\w-])|bgVersion|org\/)/cip./g;
  s/\bQIP_(ISTIO_ENABLED|ISTIO_HOST_RESOURCES_ENABLED|EXPORT_LEGACY_FORMAT|EXPORT_LEGACY_RESOURCE_NAMES|EXPORT_REMOVE_UNUSED_SPECS|EGRESS_GATEWAY_URL|REGISTER_INGRESS_CHAIN_ROUTES|REGISTER_EGRESS_CHAIN_ROUTES|CHAINS_CONFIGURATION_URL|LIBRARIES_PATH|ENGINE_DOMAIN|TESTING_[A-Z_]+)\b/CIP_$1/g;
' runtime-catalog/README.md integration-build-maven-plugin/README.md \
  infrastructure/README.md infrastructure/qip-dev/README.md \
  help/docs/01__Chains/8__Testing/testing.md \
  help/docs/06__Functionality_and_Features/6__Retention_Settings/retention_settings.md \
  .apm/instructions/engine.instructions.md .apm/instructions/micro-engine.instructions.md \
  .apm/instructions/infrastructure.instructions.md .apm/instructions/runtime-catalog.instructions.md \
  .apm/instructions/sessions-management.instructions.md
```

The replacements keep string length, so Markdown table columns stay aligned.

- [ ] **Step 2: Review the doc diff**

Run: `git diff --word-diff -- '*.md'`
Expected: only `qip.`→`cip.` and `QIP_`→`CIP_` swaps in property and variable names. Revert any hunk that renames a
meter name, index, label, schema URI, or product name.

- [ ] **Step 3: Document the alias in the three Spring service instructions**

Append this section to the end of `.apm/instructions/engine.instructions.md`,
`.apm/instructions/runtime-catalog.instructions.md`, and `.apm/instructions/sessions-management.instructions.md`:

```markdown
### Legacy `qip.*` property names

Properties use the `cip.` prefix. `PropertyPrefixAliasEnvironmentPostProcessor` keeps the old `qip.` names working from
every property source, Consul included. For a lookup of `cip.x`, the first source that holds `cip.x` or `qip.x` wins,
and within one source `cip.x` wins, so a `qip.x` in Consul still overrides a `cip.x` default in `application.yml`.
Read and declare new keys under `cip.` only.
```

Append this section to the end of `.apm/instructions/micro-engine.instructions.md`:

```markdown
### No legacy `qip.*` property names

Properties use the `cip.` prefix, and micro-engine has no alias for the old `qip.` names: a `qip.x` key, in Consul or
anywhere else, is ignored. The Spring services keep reading `qip.*` through their own alias; this service does not.
```

- [ ] **Step 4: Regenerate the compiled instructions**

Run: `apm compile`
Expected: the `AGENTS.md` files, `.claude/rules/*.md`, and `.cursor/rules/*.mdc` listed above change, with the same
swaps and the new sections. Check `git status` for any generated file outside that list and read its diff.

- [ ] **Step 5: Commit**

```bash
git add -u runtime-catalog/README.md integration-build-maven-plugin/README.md infrastructure help/docs .apm \
           AGENTS.md engine/AGENTS.md micro-engine/AGENTS.md runtime-catalog/AGENTS.md sessions-management/AGENTS.md \
           .claude/rules .cursor/rules
git commit -m "docs: document the cip.* property prefix" \
           -m "$CO_AUTHOR"
```

---

### Task 10: Final sweep

**Files:** none changed unless the sweep finds a leftover.

- [ ] **Step 1: Search every in-scope module for leftovers**

```bash
git ls-files engine micro-engine runtime-catalog sessions-management integration-build-pipeline integration-build-maven-plugin testing-service infrastructure help .apm \
  | grep -vE '^testing-service/docs/|/node_modules/' \
  | xargs grep -nE '(^|[^A-Za-z0-9_/.-])qip\.[a-z]|^\s*qip:\s*$|\bQIP_(ISTIO|EXPORT|EGRESS|REGISTER|CHAINS_CONFIGURATION|LIBRARIES|ENGINE_DOMAIN|TESTING)' 2>/dev/null
```

Expected: only the kept tokens listed in Tasks 6 and 7 (test label values and the micro-engine meter names). Also
`QIP_AI_*`, which is out of scope.

- [ ] **Step 2: Full build of the changed Maven modules**

Run: `mvn -pl engine,runtime-catalog,sessions-management,micro-engine,integration-build-maven-plugin -am install -Dgpg.skip=true`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit a leftover fix if Step 1 found one**

```bash
git add -u
git commit -m "refactor: rename remaining qip.* keys to cip.*" \
           -m "$CO_AUTHOR"
```

Skip this step when Step 1 printed only the expected tokens.
