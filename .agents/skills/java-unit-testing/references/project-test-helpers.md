# Micro-engine test helpers

The shared helpers live in `micro-engine/src/test/java/org/qubership/integration/platform/engine/testutils/`. No other
module has them: the engine module uses the same package name, and an import from it there fails to compile. Read the
helper class before writing a local builder, and prefer the helper when it fits.

## Display names

```java
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
```

`ReplaceCamelCase` turns a `shouldXWhenY` method name into a readable display name, so use the two together.

## `MockExchanges`

| Helper | Returns |
| --- | --- |
| `defaultExchange()` | a real `DefaultExchange` on a new `DefaultCamelContext` |
| `defaultExchange(ctx, pattern)` | a real `DefaultExchange` on the given context with the given `ExchangePattern` |
| `basic()` | a lenient mock `Exchange` whose properties and exception are backed by a map |
| `withMessage()` | `basic()` plus a lenient mock `Message` from `getMessage()` and `getIn()` |
| `withMessageAndConverter()` | `withMessage()` plus a lenient mock `CamelContext` and `TypeConverter` |
| `withDefaultCamelContext()` | `basic()` plus a real `DefaultCamelContext` from `getContext()` |

Use `defaultExchange()` when the test reads back headers, properties, or body. The mock variants are created with
`withSettings().lenient()` and stub with `lenient()`, so strict stubbing does not report their unused stubs. The
setting belongs to the mock, so a stub you add on top of one is lenient too and goes unreported when unused: check the
interaction with `verify(...)` where it matters, or create a strict mock of your own.

## `ObjectMappers`

`ObjectMappers.getObjectMapper()` returns the project JSON mapper, and `ObjectMappers.getCheckpointMapper()` returns the
mapper the checkpoint processors use. Use them for normal serialization. Mock or spy an `ObjectMapper` only to force
`JsonProcessingException` or another error branch.

## `MapperTestUtils`

This is the micro-engine helper for AtlasMap tests. runtime-catalog has an unrelated `mapper/MapperTestUtils` with other
methods. It covers:

* expressions: `dummyExpression`, `expressionReturning`, `expressionReturningValue`, `expressionResolvingPath`,
  `booleanExpressionReturning`, and `assertBooleanResult`;
* simple fields: `simpleField`, `simpleFieldWithDocId`, `simpleFieldWithName`, `sourceField`, `child`, and `cloneOf`;
* complex fields and groups: `complexField`, `fieldGroup`, `fieldGroupSimple`, `collection`, and
  `itemWithCollectionKey`;
* JSON fields: `jsonField`, `jsonEnumField`, `jsonComplexType`, and `fieldWithNullActions`.

```java
Field source = MapperTestUtils.simpleField("/source");
Field target = MapperTestUtils.simpleField("/target");
```

Extend `MapperTestUtils` only with a helper that more than one test class needs.

## Other helpers

| Helper | Use |
| --- | --- |
| `ErrorHandlingTestUtils` | `assertExceptionDto(entity)` and `assertValidTimestamp(value)` for REST error responses |
| `KafkaBlueGreenTestUtils` | Kafka blue-green fixtures: `namespaceVersion`, `commitMarker`, `record`, and `consumerRecord` |
| `OpenSearchTestUtils` | document classes for OpenSearch mapping tests, `getMapping()`, and `assertJsonBodyEquals(...)` |
| `PubSubTestUtils` | `newComponent()`, `newEndpoint()`, and `newEndpointWithConcurrentConsumers(n)` for Google Pub/Sub |

`OpenSearchTestUtils.invoke(...)` and `invokeVoid(...)` call a private method through reflection. A new test does not
use them; see the reflection rule in the skill.
