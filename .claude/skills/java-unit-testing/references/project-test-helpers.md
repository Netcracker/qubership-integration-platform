# Project test helpers

Read this file when Java unit test work needs shared project test helpers.

Use this reference for:

* display name generation
* Camel Exchange and Message helper objects
* ObjectMapper helpers
* AtlasMap and mapper test builders
* reducing duplicated local test helper methods

Keep helper usage consistent with nearby tests. Prefer an existing helper over adding a local builder.

## Display name generation

Use:

```java
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
```

Import:

```java
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
```

Use this together with `shouldXWhenY` test method names.

Example:

```java
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SomeServiceTest {
    @Test
    void shouldReturnDefaultValueWhenInputIsMissing() {
    }
}
```

## Camel exchange helpers

Prefer:

```java
import org.qubership.integration.platform.engine.testutils.MockExchanges;
```

Available helpers:

```java
MockExchanges.basic()
MockExchanges.withMessage()
MockExchanges.withMessageAndConverter()
MockExchanges.withDefaultCamelContext()
MockExchanges.defaultExchange()
```

Use `MockExchanges.defaultExchange()` when the test benefits from real Exchange state, such as:

* headers
* properties
* body
* message state
* Camel context interaction

Use `MockExchanges.withMessage()` when the test needs a mocked Exchange with a controlled Message.
Use `MockExchanges.withMessageAndConverter()` when the test needs Message and TypeConverter behavior.
Prefer these helpers over manually mocking `Exchange`, `Message`, `CamelContext`, or `TypeConverter` unless the test needs a very specific interaction.

## ObjectMapper helpers

Prefer:

```java
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
```

Available helpers:

```java
ObjectMappers.getObjectMapper()
ObjectMappers.getCheckpointMapper()
```

Use real ObjectMapper helpers for normal JSON serialization and deserialization behavior.
Mock ObjectMapper only for targeted error, exception, or fallback branches.
Examples of good real-mapper usage:

* parsing valid JSON
* writing normal JSON output
* verifying field mapping behavior
* testing checkpoint serialization with the project mapper

Examples where mocking ObjectMapper can be acceptable:

* forcing `JsonProcessingException`
* forcing fallback behavior
* verifying error handling when mapper fails

## AtlasMap and mapper helpers

Prefer:

```java
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;
```

Use shared helpers for:

* expression helpers
* boolean assertion helpers
* simple field builders
* field group builders
* complex field builders
* JSON field builders
* JSON enum field builders
* collection helpers
* typed casts/assertions
* fields with null actions

Prefer these helpers over local helper methods in individual test classes.

Before adding a new local helper:

1. inspect `MapperTestUtils`
2. inspect nearby mapper tests
3. reuse an existing helper if it fits
4. extend `MapperTestUtils` only when the behavior is reusable across tests

## Helper selection rules

Use real helper-created objects when they make assertions clearer. Prefer project helpers when they reduce:

* repeated setup
* fragile mocks
* local builders
* reflection
* unnecessary static mocking

Use local helper methods only when the setup is specific to one test class and unlikely to be reused. Keep local helpers small and behavior-focused.

## Examples

Good:

```java
Exchange exchange = MockExchanges.defaultExchange();
exchange.getMessage().setHeader("X-Request-Id", "request-id");

processor.process(exchange);

assertEquals("request-id", exchange.getMessage().getHeader("X-Request-Id"));
```

Good:

```java
ObjectMapper objectMapper = ObjectMappers.getObjectMapper();

MyDto result = objectMapper.readValue(json, MyDto.class);

assertEquals("value", result.getName());
```

Good:

```java
Field source = MapperTestUtils.simpleField("/source");
Field target = MapperTestUtils.simpleField("/target");
```

Avoid creating local variants of these helpers when the shared helper already supports the same setup.
