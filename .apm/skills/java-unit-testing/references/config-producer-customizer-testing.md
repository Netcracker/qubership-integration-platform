# Configuration, producer, and customizer testing

Read this file when the micro-engine class under test is a producer, a component customizer, a configuration class, or
code that reads `application.yml` defaults or system properties. When Quarkus has to inject the config, also read
`references/quarkus-component-testing.md`.

## Producers

Call the producer method directly when its inputs are method arguments or fields you can assign.
`OpenSearchClientSupplierProducerTest` (copied to `assets/templates/config-producer-test.java`) builds the producer with
`new`, passes mocks, and asserts on the returned object and the collaborator it was handed to. Assert that the produced
object has the expected type and values, and cover missing, empty, or `null` config and the fallback values.

When the producer reads a `@ConfigProperty` field and no direct setup is clean, use `@QuarkusComponentTest` with
`@TestConfigProperty`, as `CamelQuartzComponentCustomizerProducerTest` does.

## Customizers

Apply the customizer to a real lightweight target and assert the change:

```java
ComponentCustomizer customizer = producer.servletCustomComponentCustomizer(contextPropsProvider);
ServletCustomComponent component = new ServletCustomComponent();

customizer.configure("servletCustom", component);

assertInstanceOf(ServletCustomFilterStrategy.class, component.getHeaderFilterStrategy());
```

Mock the target only when it is heavy, final, or external. Cover the option applied, the values left unchanged, and a
disabled or empty configuration. For a Micrometer `MeterFilter` such as `MetricsFilterConfiguration`, build a real
`Meter.Id` and assert the tags it keeps and removes.

## Defaults

Read `micro-engine/src/main/resources/application.yml` for the property name and default before asserting either. Do
not copy a default from another test: the micro-engine `qip.*` properties were renamed to `cip.*`, and copied values go
stale. Assert a default only when the default is the behavior under test. Where the default
is a placeholder such as `cip-elements-${application.namespace}`, assert the resolved value the class sees, not the
placeholder string.

For a property map such as the one `PredeployCheckKafkaConfiguration` builds, assert the entries the branch sets, not
the whole map:

```java
assertEquals("PLAINTEXT", properties.get("security.protocol"));
```

## System properties and global state

`TruststoreConfiguration` reads the `javax.net.ssl.*` system properties, so `TruststoreConfigurationTest` sets them, and
a system property outlives the test. Capture the previous value and restore it in `@AfterEach`:

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

`TruststoreConfigurationTest` also carries `@ResourceLock(Resources.SYSTEM_PROPERTIES)`. Restore static caches, flags,
and registries the same way. Use a `@TempDir` when file existence is part of the
behavior. For `EngineInfoProducer`, cover hostname lookup success and failure, and wrap or mock the lookup only as
narrowly as that branch needs. Executor cleanup is in `references/async-lifecycle-testing.md`.
