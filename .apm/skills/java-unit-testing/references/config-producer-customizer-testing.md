# Configuration, producer, and customizer testing

Read this file when Java unit test work involves configuration classes, producer methods, customizers, application defaults, environment-dependent values, or system-property-dependent behavior.

Use this reference for tests around:

* configuration classes
* producer methods
* customizer classes
* application defaults from `application.yml`
* `@ConfigProperty`-backed values
* thread pool configuration
* metrics/tracing/customizer behavior
* OpenSearch, Kafka, truststore, SSL, Quartz, and deployment configuration
* system properties and global JVM state

If the test needs Quarkus-managed config injection, also read `references/quarkus-component-testing.md`.

## Default testing approach

Prefer plain JUnit 5 + Mockito tests when the class can be instantiated directly.

Use plain unit tests for:

* simple getters
* value mapping
* object creation
* fallback branches
* default value logic that can be set directly
* producer methods that can be called directly
* customizers that can be applied to mocked or real lightweight targets

Use `@QuarkusComponentTest` only when Quarkus-managed injection is part of the behavior being tested.

A class being a configuration bean is not enough reason to use `@QuarkusComponentTest`.

## What to verify

For configuration classes, verify observable configuration behavior:

* produced value
* default value
* overridden value
* fallback value
* generated bean name or qualifier behavior
* created object type
* created object state
* applied customizer effect
* exception or fallback branch
* interaction with a target builder/customizer object

Prefer asserting returned objects and public properties over inspecting private fields.

## Producer method tests

For producer methods, call the producer directly when possible.

Good assertions include:

* produced object is not null
* produced object has expected type
* produced object contains expected values
* produced executor/thread pool has expected configuration
* produced filter/customizer changes the target as expected
* producer handles missing/empty/null config as expected
* producer uses fallback values correctly

Example pattern:

```java
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SomeProducerTest {
    private final SomeProducer producer = new SomeProducer();

    @Test
    void shouldCreateClientWithConfiguredTimeout() {
        producer.timeout = 5000;

        SomeClient client = producer.createClient();

        assertEquals(5000, client.getTimeout());
    }
}
```

If fields are injected by Quarkus and there is no clean direct setup, consider `@QuarkusComponentTest` with `@TestConfigProperty`.

## Customizer tests

For customizer classes, verify the effect on the customized target. Prefer a real lightweight target object when possible.
Use mocks when the target is external, heavy, final, or difficult to instantiate.

Good assertions include:

* expected option is applied
* expected tag/property/header is removed or added
* expected target method is called with the correct value
* unrelated values are preserved
* disabled configuration leaves the target unchanged
* null or empty configuration is handled correctly

Example with a mock target:

```java
@Test
void shouldApplyConfiguredOption() {
    SomeTarget target = mock(SomeTarget.class);

    customizer.customize(target);

    verify(target).setOption("expected");
}
```

Example with a real target:

```java
@Test
void shouldRemoveUnsupportedTags() {
    Meter.Id result = customizer.map(originalId);

    assertFalse(result.getTags().stream().anyMatch(tag -> tag.getKey().equals("client.id")));
}
```

## Application defaults

When testing defaults, inspect `application.yml` and nearby tests before hardcoding expected values.
Use defaults only when the default itself is the behavior under test.
Known defaults commonly used in this project include:

```text
application.name=qip-engine
cloud_service_name=qip-engine-v1
namespace=local
qip.deployments thread pool core/max=3
truststore path=/tmp/truststore/defaulttruststore.jks
empty truststore password
certs location=:/tmp/cert/${SSL_SECRET:defaultsslcertificate}
quartz thread pool count=10
metrics disabled by environment defaults
tracing disabled by environment defaults
opensearch index=qip-elements-${application.namespace}
```

Confirm the exact property name and value in the current repository before using these values in a test.
When a default comes from an expression or property placeholder, assert the resolved behavior that the class actually sees, not the placeholder string, unless placeholder preservation is the contract.

## Thread pool configuration tests

For thread pool producer/configuration classes, verify:

* core size
* max size
* queue or scheduling behavior if exposed
* thread naming if it is part of the contract
* executor type
* shutdown behavior if the class owns lifecycle
* default and overridden config values

Prefer asserting public executor configuration when available. If the produced executor starts threads or schedules work, ensure the test shuts it down.
Use `@AfterEach` cleanup for executors created during tests.

Example cleanup pattern:

```java
private ExecutorService executor;

@AfterEach
void tearDown() {
    if (executor != null) {
        executor.shutdownNow();
    }
}
```

## Truststore, SSL, and certificate configuration

For truststore, SSL, and certificate configuration tests, isolate global JVM state.
These tests may touch:

* `javax.net.ssl.trustStore`
* `javax.net.ssl.trustStorePassword`
* `javax.net.ssl.trustStoreType`
* custom truststore properties
* certificate location properties
* file paths
* environment-derived defaults

When a test changes system properties, capture previous values and restore them in `@AfterEach`.

Example:

```java
private String previousTrustStore;

@BeforeEach
void setUp() {
    previousTrustStore = System.getProperty("javax.net.ssl.trustStore");
}

@AfterEach
void tearDown() {
    if (previousTrustStore == null) {
        System.clearProperty("javax.net.ssl.trustStore");
    } else {
        System.setProperty("javax.net.ssl.trustStore", previousTrustStore);
    }
}
```

Prefer temporary files/directories when file existence is part of the behavior. Avoid relying on machine-specific files.

## Kafka and predeploy-check configuration

For Kafka/predeploy-check configuration, verify:

* enabled/disabled branches
* bootstrap servers
* security protocol
* SASL mechanism
* timeout values
* truststore path
* truststore password
* truststore type
* fallback to PLAINTEXT/PLAIN defaults when applicable
* behavior when optional config is absent

Prefer asserting the produced property map or configuration object.

Use focused map assertions:

```java
assertEquals("PLAINTEXT", properties.get("security.protocol"));
assertEquals("PLAIN", properties.get("sasl.mechanism"));
```

Avoid full-map equality unless the complete map is the contract.

## Metrics and tracing customizers

For metrics and tracing customizers, verify the specific customization effect.

Good branches to cover:

* metrics enabled
* metrics disabled
* tracing enabled
* tracing disabled
* unwanted tags removed
* expected tags preserved
* customizer returns the original object when no change is needed
* filter denies or accepts the expected meter/span

For `MeterFilter`-style tests, prefer real lightweight Micrometer objects when practical.

Assert the tag or ID behavior directly.

## OpenSearch configuration

For OpenSearch configuration tests, verify:

* index name resolution
* namespace substitution
* default index pattern
* configured index override
* host/port/protocol mapping
* authentication properties if present
* fallback behavior when optional values are missing

Known index pattern:

```text
qip-elements-${application.namespace}
```

When the test expects a resolved local namespace, confirm the resolved value from current config before asserting.

## Application and engine info configuration

For application/engine info producers, verify:

* application name
* cloud service name
* namespace
* deployment name fallback
* hostname resolution
* fallback when hostname lookup fails
* produced engine info fields

For hostname failure branches, mock or wrap the hostname lookup only as narrowly as needed.

Prefer testing the returned `EngineInfo` or configuration object instead of private fields.

## System properties and global state

Tests that mutate global JVM state must restore it.

Global state includes:

* system properties
* environment-like static config
* static caches
* static flags
* global registries
* default locale/timezone if changed
* SSL/truststore properties
* shared executors or schedulers

Use `@AfterEach` cleanup.

Keep global-state tests narrow.

Do not let one test depend on another test's global setup.

If global state makes the class hard to test, report that a small provider/seam would make the code more testable.

## Config branch coverage

Good branches to cover:

* default value used
* explicit value used
* empty value handled
* null optional value handled
* enabled flag true
* enabled flag false
* fallback value used after exception
* invalid value branch
* producer creates expected object
* customizer changes target
* customizer leaves target unchanged
* system property present
* system property absent
* hostname lookup succeeds
* hostname lookup fails

Avoid tests that only instantiate the configuration class without asserting behavior.

## What to inspect before editing

Before adding or changing a configuration/producer/customizer test:

1. read the production class
2. inspect `application.yml` for relevant defaults
3. inspect nearby tests for config setup patterns
4. decide whether plain unit test or `@QuarkusComponentTest` is needed
5. identify the produced object or customized target
6. identify global state touched by the test
7. plan cleanup for system properties, executors, files, or static state
8. cover one meaningful branch per test
9. prefer focused assertions over full-object or full-map equality

If Quarkus config injection is needed, read `references/quarkus-component-testing.md`.

If async executors or schedulers are involved, read `references/async-lifecycle-testing.md`.

## Common pitfalls

Avoid these patterns unless the branch genuinely requires them:

* using `@QuarkusComponentTest` for simple getters
* hardcoding defaults without checking `application.yml`
* relying on hidden environment state
* leaving system properties changed after the test
* creating executors without shutting them down
* asserting private injected fields instead of produced behavior
* comparing a whole properties map when only a few entries are part of the contract
* mocking configuration value objects that can be instantiated directly
* weakening assertions because the produced object is hard to inspect

## Output expectations for configuration test work

When reporting configuration, producer, or customizer test changes, mention:

* which config/default branch was covered
* whether plain unit test or `@QuarkusComponentTest` was used
* which properties or defaults were asserted
* whether global state cleanup was needed
* whether an executor, scheduler, temporary file, or system property was created
* which command was run
* any behavior that remains untested because it would require a larger testability seam
