# Java unit test templates

Each file comes from the micro-engine test named after it below, without the license header. Use them as style
examples rather than code to copy unchanged.

## Templates

* `plain-unit-test.java`: a plain JUnit Jupiter test (`GraphqlCustomComponentTest`).
* `unit-test-with-parameterized-test.java`: a plain test with `@ParameterizedTest` (`LongToInstantConverterTest`).
* `mockito-test.java`: a JUnit Jupiter and Mockito test (`LongStringSerializerTest`).
* `camel-processor-test.java`: a processor test on a real `Exchange` (`SplitAsyncProcessorTest`).
* `quarkus-component-config-test.java`: config injection with `@QuarkusComponentTest`, `@TestConfigProperty`, and
  `@InjectMock` (`CamelQuartzComponentCustomizerProducerTest`).
* `config-producer-test.java`: a producer method called directly, without CDI (`OpenSearchClientSupplierProducerTest`).
* `atlasmap-mapper-test.java`: a mapper test built on `MapperTestUtils` (`ComparisonExpressionEqualityTest`).

Prefer nearby existing tests when they are consistent with the skill. Use a template when adding a new test class or
when nearby tests disagree.
