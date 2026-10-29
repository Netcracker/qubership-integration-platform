# Quarkus component testing

Read this file when deciding whether a micro-engine test needs `@QuarkusComponentTest`, which comes from
`quarkus-junit-component`. The default is a plain JUnit Jupiter and Mockito test; the class under test being a CDI bean
is not a reason to change that.

## When to use it

Use `@QuarkusComponentTest` when Quarkus has to supply what the test checks:

* the behavior depends on a `@ConfigProperty` value, and `@TestConfigProperty` states it more clearly than assigning the
  field;
* a CDI producer or the wiring between a few beans is the subject;
* the beans start without application-level side effects.

Use a plain unit test when the class can be built through its constructor or by assigning a package-visible field, and
the test checks ordinary branching, mapping, validation, or error handling. `MapperProcessorTest` sets the
`cacheEnabled` config field directly for that reason.

## Example

`CamelQuartzComponentCustomizerProducerTest` (copied to `assets/templates/quarkus-component-config-test.java`) shows the
parts:

```java
@QuarkusComponentTest
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@TestConfigProperty(key = "cip.camel.component.quartz.thread-pool-count", value = "10")
class CamelQuartzComponentCustomizerProducerTest {

    @Inject
    CamelQuartzComponentCustomizerProducer producer;

    @InjectMock
    QuartzSchedulerService quartzSchedulerService;
```

* `@Inject` marks the bean under test.
* `@InjectMock` (from `io.quarkus.test`) replaces a dependency with a Mockito mock that you stub with `when(...)`. Do
  not mix it with `@Mock` and `MockitoExtension` in one class.
* `@TestConfigProperty` supplies each config value the branch reads. Read the real default in
  `micro-engine/src/main/resources/application.yml` before relying on it.

By default the types of the `@Inject` fields are the components under test, and every other dependency is mocked. When
the bean needs other real beans, list them: `@QuarkusComponentTest(value = {EngineStateService.class, ConsulKeyValidator.class})`, as
`consul/EngineStateServiceTest` does.

## Side effects

Before choosing a component test, check the bean and its dependencies for `@Startup`, `@PostConstruct`, background
threads, scheduled tasks, static initialization, system property access, and network or file system access. If startup
has side effects, test the class directly with mocked collaborators. Assert through public methods rather than by
mutating fields on a normal-scoped bean, which the test sees through a client proxy. System property cleanup is in
`references/config-producer-customizer-testing.md`.
