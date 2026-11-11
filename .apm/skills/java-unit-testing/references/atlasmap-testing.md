# AtlasMap testing

Read this file when the micro-engine class under test uses AtlasMap: `MapperProcessor`, `CustomAtlasContext`,
`AtlasMapping`, the expression classes under `mapper/atlasmap/expressions/`, or mapping fields. The `MapperTestUtils`
builders are listed in `references/project-test-helpers.md`.

## Testing level

Use plain JUnit Jupiter and Mockito tests with real AtlasMap model objects. Build only the fields the branch needs, with
`MapperTestUtils` rather than local builders. Mock external collaborators, the Camel registry, and error branches that
real model objects cannot reach.

Assert the mapper contract: the produced mapping, the transformed fields, the selected expression, the validation
result, registry hits and misses, and the output body, headers, or properties. Do not write tests that only call model
setters, and assert a few properties rather than a whole object graph.

## Model API

Use the AtlasMap API in this repository's version, and check the model class before calling an unfamiliar method. For
example, `AtlasMapping` has no `setDataSource(...)`: add data sources through the list.

```java
mapping.getDataSource().add(dataSource);
```

## `MapperProcessor`

Read `MapperProcessor` before editing its test. It depends on:

* `DefaultAtlasContextFactory.getInstance()`, taken in the constructor;
* the `@Identifier("jsonMapper") ObjectMapper` constructor argument;
* the `cip.mapper.cache-enabled` property in the `cacheEnabled` field;
* the Camel registry, where the cache stores `AtlasMapping` and `ValidationResult` under the `Properties.MAPPING_ID`
  exchange property;
* a `new CustomAtlasContext(factory, atlasMapping)` for each exchange.

`MapperProcessorTest` shows the setup: construct the processor with a spy on `ObjectMappers.getObjectMapper()`, set
`cacheEnabled` directly, mock the `CamelContext` and its `Registry`, and scope `mockConstruction(CustomAtlasContext.class)`
to one test. Cover the cache disabled, a cache hit, a cache miss that binds the mapping, a missing mapping ID, and a
validation or processing failure. When the test reads or writes the `Exchange`, also read `references/camel-testing.md`.

## Expressions and fields

For expression classes, `MapperTestUtils.expressionReturning(...)` and `assertBooleanResult(...)` cover most setups; see
`assets/templates/atlasmap-mapper-test.java`. For collection, group, JSON, and enum fields, assert the path, field type,
doc ID, collection key, parent, and action list that the branch changes.
