# Quarkus component testing

Read this file when Java unit test work involves Quarkus CDI component tests, injected configuration, `@ConfigProperty`, or `@QuarkusComponentTest`.

Use this reference for tests around:

* Quarkus-filled `@ConfigProperty` values
* small CDI/component-level behavior
* `@QuarkusComponentTest`
* `@TestConfigProperty`
* injected beans that are part of the behavior under test
* producer/configuration classes that need Quarkus-managed injection
* choosing between a plain unit test and a Quarkus component test

## Default choice

Prefer plain JUnit 5 + Mockito tests by default. Use `@QuarkusComponentTest` only when Quarkus-managed injection is part of the behavior being tested.
A production class being a CDI bean is not enough reason to use `@QuarkusComponentTest`.

## When to use `@QuarkusComponentTest`

Use `@QuarkusComponentTest` when the test needs Quarkus to provide values or beans that would be awkward or misleading to set manually.

Good cases:

* the behavior depends on `@ConfigProperty`
* `@TestConfigProperty` makes the expected configuration explicit
* CDI producer behavior is the subject of the test
* injected config defaults or overrides are the branch being verified
* a small component-level test is clearer than reflection or manual field mutation
* the component can be started without application-level side effects

Example:

```java
@QuarkusComponentTest
@TestConfigProperty(key = "some.feature.enabled", value = "true")
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SomeConfigurationTest {
    @Inject
    SomeConfiguration configuration;

    @Test
    void shouldEnableFeatureWhenPropertyIsTrue() {
        assertTrue(configuration.isFeatureEnabled());
    }
}
```

## When to prefer a plain unit test

Use a plain unit test when the class can be instantiated directly.

Good cases:

* constructor or setter injection is available
* config values can be assigned directly
* the test verifies ordinary Java branching
* the test verifies mapping, validation, fallback, or error handling
* mocked dependencies are enough
* CDI wiring is not the subject
* Quarkus runtime behavior is not needed

Example:

```java
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SomeServiceTest {
    @Mock
    SomeDependency dependency;

    @InjectMocks
    SomeService service;

    @Test
    void shouldReturnFallbackWhenDependencyReturnsNull() {
        when(dependency.load()).thenReturn(null);

        String result = service.getValue();

        assertEquals("default", result);
    }
}
```

## Config property tests

Use `@TestConfigProperty` to make config inputs visible at the class level. Prefer explicit test properties over hidden defaults when the tested branch depends on a specific value.

Example:

```java
@QuarkusComponentTest
@TestConfigProperty(key = "qip.deployments.thread-pool.core-size", value = "3")
@TestConfigProperty(key = "qip.deployments.thread-pool.max-size", value = "3")
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DeploymentThreadPoolConfigurationTest {
}
```

When testing defaults, inspect `application.yml` and nearby tests before hardcoding expected values.
Use defaults only when they are part of the behavior being tested.

## Mocking CDI dependencies

Use Quarkus component-test mechanisms for CDI dependencies when the test runs with `@QuarkusComponentTest`. Prefer plain Mockito field mocks only in plain Mockito tests.
For component tests, keep injected and mocked dependencies easy to distinguish.

A good component test should make clear:

* which bean is under test
* which config properties are supplied
* which dependencies are mocked
* which behavior is asserted

## Field mutation and reflection

Use `@QuarkusComponentTest` when it avoids unsafe reflection or manual mutation of injected config fields.
When a plain unit test can set the required state through constructors, setters, or direct package-visible test setup, prefer the plain unit test.
Avoid reflection-based field injection for config properties when `@TestConfigProperty` can express the same setup.

## Startup and lifecycle side effects

Before using `@QuarkusComponentTest`, inspect whether the component or its dependencies have lifecycle side effects.

Check for:

* `@Startup`
* `@PostConstruct`
* background threads
* scheduled tasks
* static initialization
* system property access
* external clients
* file system access
* network calls
* global registry changes

If component startup creates side effects, prefer a plain unit test with direct instantiation or mocked collaborators.
If a component test is still needed, isolate side effects and add explicit cleanup.

## Normal-scoped beans

Be careful with normal-scoped beans in component tests. Prefer testing behavior through public methods and injected dependencies.
Use direct field mutation only when nearby tests already use the same safe pattern and the mutation does not depend on proxy internals.
When a normal-scoped bean makes direct state setup hard, prefer:

* constructor-friendly plain unit tests
* package-visible test seam
* small factory/provider seam
* `@TestConfigProperty` for config values

## System properties and global state

Tests that mutate global JVM state must restore it.

This includes:

* `System.setProperty(...)`
* `System.clearProperty(...)`
* truststore properties
* SSL-related properties
* static caches
* static flags
* global registries

Use `@AfterEach` cleanup when the test changes global state.

Example:

```java
private String previousValue;

@BeforeEach
void setUp() {
    previousValue = System.getProperty("some.property");
}

@AfterEach
void tearDown() {
    if (previousValue == null) {
        System.clearProperty("some.property");
    } else {
        System.setProperty("some.property", previousValue);
    }
}
```

Keep global-state tests narrow and explicit.

## Producer and configuration classes

For producer and configuration classes, choose the simplest test type.

Use plain unit tests when:

* the class can be instantiated directly
* fields can be set safely
* the test verifies value mapping or object creation
* CDI is not the behavior under test

Use `@QuarkusComponentTest` when:

* Quarkus config injection is the behavior
* the produced bean depends on injected properties
* `@TestConfigProperty` gives the cleanest setup
* the component can start safely

Read `references/config-producer-customizer-testing.md` when the test involves application defaults, producers, customizers, or configuration mapping.

## What to inspect before editing

Before adding or changing a Quarkus component test:

1. read the production class
2. identify whether config injection is part of the behavior
3. check whether a plain unit test can prove the same branch
4. inspect lifecycle annotations and startup side effects
5. inspect nearby tests for local patterns
6. choose `@QuarkusComponentTest` only when it adds value
7. use `@TestConfigProperty` for explicit config inputs
8. plan cleanup for system properties or global state
9. keep the test focused on one behavior branch

## Common pitfalls

Avoid these patterns unless the branch genuinely requires them:

* using `@QuarkusComponentTest` just because a class has CDI annotations
* starting component tests for simple getter or mapping logic
* mutating normal-scoped proxy internals
* relying on hidden application defaults when the branch needs explicit config
* leaving system properties changed after the test
* starting beans with `@Startup` side effects when a plain unit test would work
* mixing plain Mockito extension style with Quarkus component-test style without a clear reason
* using reflection when `@TestConfigProperty` would express the setup better

## Output expectations for Quarkus component test work

When reporting Quarkus component-test changes, mention:

* why `@QuarkusComponentTest` was used instead of a plain unit test
* which config properties were supplied
* which injected behavior was covered
* whether global state or lifecycle cleanup was needed
* which command was run
* whether any behavior was left to plain unit tests instead
