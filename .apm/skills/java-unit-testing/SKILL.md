---
name: java-unit-testing
description: 'Use this skill when adding, fixing, reviewing, or improving micro-engine (Quarkus) Java unit tests: JUnit Jupiter and Mockito tests, Quarkus component tests, JaCoCo coverage, or unit test failures under micro-engine/src/test. For other Java modules, follow the neighbouring tests of that module.'
---

# Java unit testing in micro-engine

This skill covers the unit tests of `micro-engine`, the Quarkus engine. `DisplayNameUtils`, the `testutils` helpers, the
`shouldXWhenY` naming, and `@QuarkusComponentTest` exist only in that module's tests. The engine module uses the same
`org.qubership.integration.platform.engine` package, so an import of `testutils.DisplayNameUtils` looks valid there and
fails to compile. For any other Java module, follow the neighbouring tests of that module.

Snapshot and route tests tagged `component` are out of scope: Surefire excludes them (`<groups>!component</groups>`), and
the `component-testing` skill owns them.

Test method names, comments, and failure messages are developer-facing text, so apply `english-developer-style` to them.

## Load references when needed

* `references/project-test-helpers.md`: the micro-engine `testutils` helpers (`MockExchanges`, `ObjectMappers`,
  `MapperTestUtils`, and others).
* `references/camel-testing.md`: the class uses Camel `Exchange`, `Message`, `CamelContext`, processors, route loading,
  or context propagation.
* `references/atlasmap-testing.md`: the class uses AtlasMap, `MapperProcessor`, `CustomAtlasContext`, or mapping fields.
* `references/quarkus-component-testing.md`: deciding on `@QuarkusComponentTest`, `@TestConfigProperty`, or
  `@InjectMock`.
* `references/config-producer-customizer-testing.md`: producers, customizers, `application.yml` defaults, or system
  properties.
* `references/async-lifecycle-testing.md`: the class starts threads, executors, or schedulers, or returns a Mutiny `Uni`.

## Default test style

The default is a plain JUnit Jupiter test with Mockito. micro-engine resolves JUnit Jupiter 6 through `quarkus-bom`, so
use the `org.junit.jupiter.api` APIs and nothing from JUnit 4.

```java
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SomeServiceTest {

    @InjectMocks
    SomeService service;

    @Mock
    SomeDependency dependency;

    @Test
    void shouldReturnFallbackWhenDependencyReturnsNull() {
        when(dependency.load()).thenReturn(null);

        assertEquals("default", service.getValue());
    }
}
```

* Name test methods `shouldXWhenY`.
* Order fields as the class under test, then mocks, then fixtures and other helpers.
* Do not add Arrange, Act, or Assert comments.

Use `@QuarkusComponentTest` only when Quarkus-filled `@ConfigProperty` values or CDI wiring are the behavior under test.
A class being a CDI bean is not a reason; `references/quarkus-component-testing.md` has the decision.

## Checkstyle on test sources

The micro-engine Checkstyle run sets `includeTestSourceDirectory` to `true`, fails on the first violation
(`maxAllowedViolations` is `0`), and runs in the `compile` phase, so a violation in a test file stops the build before
any test runs. The rule most often hit is `CustomImportOrder`:

1. third-party packages, including `jakarta.*` and `org.*`;
2. `java.*` and `javax.*`;
3. static imports.

Each group is sorted alphabetically, with one empty line between groups. Unused imports also fail the build.

## Mockito rules

Mockito runs strict through `MockitoExtension`. Keep it strict.

* When Mockito reports `UnnecessaryStubbingException`, remove or move the unused stub instead of adding `lenient()`.
* When Mockito reports `PotentialStubbingProblem`, check whether production code calls a different overload, argument,
  or instance than the stub expects.
* Stub inside each test. Put a stub in `@BeforeEach` only when every test uses it.
* Use `lenient()` only for shared infrastructure stubs. `MockExchanges.basic()` and the helpers built on it already
  return lenient mocks.
* For `anyMap()`, pass the type arguments when inference fails: `Mockito.<String, Object>anyMap()`.

Prefer real objects where they keep the test simpler: maps, lists, value objects, `ObjectMappers.getObjectMapper()`, and
`MockExchanges.defaultExchange()` when `Exchange` state matters. Mock what is external, expensive, or controlled by the
test. Mock `ObjectMapper` only to force an error branch.

## Static mocking, construction mocking, and reflection

A test should keep passing after an internal refactoring that leaves behavior unchanged. Assert returned values, thrown
exceptions, `Exchange` headers, properties, and body, produced objects, and calls on collaborators, rather than private
fields or third-party builder state.

`mockStatic(...)` and `mockConstruction(...)` are for a targeted branch that no collaborator reaches, such as a failing
static utility or a `new CustomAtlasContext(...)` inside `MapperProcessor`. When one is needed:

* open it in try-with-resources inside the one test that needs it;
* mock only the method or type that branch needs;
* assert the externally visible result, not every constructor argument.

```java
try (MockedStatic<MessageHelper> messageHelper = mockStatic(MessageHelper.class)) {
    messageHelper.when(() -> MessageHelper.extractBody(exchange)).thenReturn("body");

    processor.process(exchange);

    assertEquals("body", exchange.getProperty("processedBody"));
}
```

Do not add `getDeclaredField(...)`, `setAccessible(true)`, or private-method invocation to a new test.
`OpenSearchTestUtils.invoke(...)` calls private methods through reflection and falls under the same rule. Keep existing
reflection tests unless the task is to improve them. Where a branch is reachable only through internals, name the
production seam that would expose it (an injected factory, clock, executor, or wrapper) in the summary.

## Workflow

1. Read the production class, its existing test, and the nearby tests in the package; follow their style.
2. Load only the references the class needs, and check `testutils` before writing a local helper.
3. Add focused tests to the existing class instead of rewriting it.
4. Keep a test-only task in test sources. A testability problem goes into the summary.
5. Run the changed test class.

## Running tests

micro-engine has no Maven wrapper. Run plain `mvn` from the repository root, where the aggregator `pom.xml` lists the
module:

```bash
mvn -q -pl micro-engine test -Dtest=<TestClassName> -Dsurefire.failIfNoSpecifiedTests=false -Dgpg.skip=true -nsu
```

`-Dgpg.skip=true` is required locally. `-nsu` skips snapshot update checks. `-q` does not silence Quarkus logging, so
read the exit code and the Surefire summary, and filter long output:

```bash
mvn -pl micro-engine test -Dtest=<TestClassName> -Dsurefire.failIfNoSpecifiedTests=false -Dgpg.skip=true -nsu 2>&1 \
  | grep -E 'Tests run|ERROR|FAIL|BUILD|checkstyle'
```

On a failure, read the first compiler, Checkstyle, or assertion error, not the whole log. Never run `mvn install`: it
writes snapshots to the `~/.m2` that every other checkout builds against.

### Coverage

Run coverage only when the user asks for JaCoCo, a coverage report, or coverage verification. The JaCoCo `report` goal
runs in `verify`, which also runs the Quarkus build, so pass the local profile from `micro-engine/README.md`:

```bash
mvn -q -pl micro-engine verify -Dgpg.skip=true -nsu -Dquarkus.profile=development,no-m2m
```

The report lands in `micro-engine/target/site/jacoco/`. Cover branches with behavior: fallback, validation, null,
empty, exception, and boundary cases. Do not write tests that only execute lines.

## Output expectations

Report the tests added or changed, the behavior or branches they cover, the command run, and its result. Report
brittle or flaky tests you found, and any production seam they would need, separately from the new tests.
