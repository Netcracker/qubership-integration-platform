# Fixtures and assertions

Framework paths below are relative to
`micro-engine/src/test/java/org/qubership/integration/platform/engine/routes/`.
Read the relevant provider and its section in `micro-engine/TESTING.md` before
adding fixture parameters.

## Assert the behavior at the right boundary

Use the generated route's result and independent observations of the external
effect. An HTTP response alone does not check the outgoing request; a successful
send alone does not check the stored message. Broker providers have different
count semantics, so read the selected provider before setting an expected count.

Use `support/SnapshotValueAssertions.java` and
`support/SnapshotFailureAssertions.java` for existing matcher behavior:

- Omit `expectedBody` only when deliberately skipping the body assertion.
  `expectedBody: null` asserts a null body. Use the existing structural and
  `$match` assertions for JSON, XML, UUIDs, and date-time values.
- Use `expectedAbsentHeaders` for missing headers. A null value and an absent
  header express different expectations.
- For HTTP drivers, `expectedHeaders` checks the client-visible response.
  `expectedExchangeHeaders` and `expectedProperties` check the internal Camel
  exchange. Match the assertion to the behavior the caller can observe.
- Failure expectations check exact types, messages, and the complete cause chain.
  Reuse the documented placeholders for exchange IDs and ephemeral ports.
  Use `anyOf` for observed alternative failures and `ignoreMessage: true` only
  at the level with system-dependent text. Keep the other levels exact.

Read provider-specific `response.properties` in the provider implementation.
Injecting a fault and asserting its effects are separate parts of the scenario.
For a failure, check the expected route outcome, any partial delivery, and the
subsequent recovery when it belongs to the requested behavior.

Keep exported-element coverage distinct from fixture configuration. A fixture
that sets a Kafka producer option or a directly configured RabbitMQ endpoint
can test runtime behavior without establishing support for that option in the
element descriptor. State that distinction in `SNAPSHOT-COVERAGE.md`.

## Extend a provider when necessary

Look for an existing capability in `fixture/SnapshotFixtureRegistry.java` and
`driver/SnapshotScenarioDriverRegistry.java`. Extend a driver for a new input
representation or invocation mechanism. Extend a fixture for external setup,
fault injection, or observations that existing providers cannot express.

Preserve generated beans and production processors. HTTP and broker fixtures
control the external service. The JMS fixture supplies JNDI while production
code constructs the component. Use these boundaries when adapting a provider.

Register a new provider, validate its parameters, and update the manifest schema
when its contract changes. Add focused helper tests for new matching or lifecycle
behavior, followed by a scenario that exercises the capability through generated
routes. Fixture-only tests do not establish generated-chain coverage.

Follow `fixture/SnapshotFixture.java` and the runner's lifecycle:

1. Start external resources and prepare configuration before route loading.
2. Configure stateful hooks through `configure(context, routes)`, using the
   supplied deployment routes. Deployments share a Camel context, so a global
   route modification can affect a neighboring deployment.
3. Prepare and verify each invocation through the existing hooks.
4. Release pending async work, await completion, and verify final effects.
5. Release gates and close resources on failure as well as success.

Use `async-flow`, `parallel-barrier`, or `split-timeout` for their supported
coordination cases. Reuse bounded waits and the runner's setup and invocation
deadlines. A fixed sleep cannot establish branch overlap or completion; raising
every timeout does not explain a stalled branch.

The runner checks expected exchange properties again after fixture completion.
It checks bodies once because reading a stream can consume it. Keep async
assertions compatible with this order and restore any JVM state a fixture changes.
