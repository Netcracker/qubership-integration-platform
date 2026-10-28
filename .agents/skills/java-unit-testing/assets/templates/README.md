# Java unit test templates

Use these files as style examples, not as copy-paste targets.

## Templates

* `plain-unit-test.java` — default JUnit5
* `unit-test-with-parameterized-test` — default JUnit5 with `@ParameterizedTest`
* `mockito-test.java` — default JUnit 5 + Mockito structure.
* `camel-processor-test.java` — processor test with real Exchange.
* `quarkus-component-config-test.java` — config injection with `@QuarkusComponentTest`.
* `config-producer-test.java` — direct producer/config class test.
* `atlasmap-mapper-test.java` — mapper-oriented test using shared helpers.

Prefer nearby existing tests when they are consistent with the skill. Use templates when adding a new test class or when nearby tests are inconsistent.
