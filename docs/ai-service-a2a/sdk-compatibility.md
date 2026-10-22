# A2A SDK compatibility report

## Decision

**Selected path: official Java A2A SDK (REST reference server).**

`ai-service` boots on Quarkus `3.32.3` with A2A Java SDK `1.1.0.Final`
(`a2a-java-sdk-reference-rest`) and serves `GET /.well-known/agent-card.json`.
Do not upgrade Quarkus for A2A.

## Versions tested

| Component | Version |
| --- | --- |
| Quarkus platform (`quarkus-bom`) | `3.32.3` |
| A2A Java SDK | `1.1.0.Final` |
| A2A BOM imported | `a2a-java-sdk-bom` (not `a2a-java-sdk-reference-bom`) |
| Extra Quarkus extension for SDK routes | `quarkus-security` `3.32.3` |
| Java | 21 |

## Evidence

- Dependency resolution keeps every `io.quarkus:*` artifact at `3.32.3` while resolving
  `org.a2aproject.sdk:a2a-java-sdk-reference-rest:1.1.0.Final`.
- Import `a2a-java-sdk-bom` only. Do not import `a2a-java-sdk-reference-bom`; that BOM
  imports Quarkus `3.36.3` and would force an upgrade.
- `@QuarkusTest` boots `ai-service` with `%test.qip.ai.a2a.enabled=true`, injects
  `@PublicAgentCard AgentCard`, and returns HTTP 200 from `/.well-known/agent-card.json`.
- A separate `@TestProfile` with `qip.ai.a2a.enabled=false` proves the no-op producers stay
  inactive on a production-ish boot.
- Focused suite:

```bash
mvn -pl ai-service \
  -Dqip.schemas.sync.skip=true \
  -Dqip.ai.qipknowledge.build.skip=true \
  -DskipITs=false \
  -Dtest='*A2a*CompatibilityTest,*AgentCard*Test' test
```

Result: 6 tests, 0 failures.

## Verified Agent Card `protocolVersion`

Wire value: `"1.0"`.

Source: `AgentInterface.CURRENT_PROTOCOL_VERSION` in A2A Java SDK `1.1.0.Final`.
A2A Protocol 1.0.1 uses major.minor `"1.0"` on each `supportedInterfaces[]` entry.
The MVP card advertises one REST interface (`HTTP+JSON`) with that value.

## MVP card constraints proven here

- REST binding only (`HTTP+JSON`)
- `capabilities.streaming: true`
- `capabilities.pushNotifications: false`
- One skill: `create-chain@2`
- Input and output modes: `text/plain` and `application/json`

## Package boundary

Stable helpers live under `org.qubership.integration.platform.ai.a2a.protocol`:

- `A2aProtocolConstants`
- `A2aAgentCardFactory`
- `A2aTaskState`
- `A2aStreamingEventSupport`

SDK types may appear in `ai.a2a` packages. They must not enter `productpipeline` packages.
Prompt 03 facade code must talk to application types, not `org.a2aproject.sdk.*`.

## Rejected alternatives

1. **Quarkus upgrade to 3.36.3** to match the SDK parent and the earlier `ai-agent` prototype.
   Rejected by plan scope controls.
2. **Import `a2a-java-sdk-reference-bom`.** Rejected because it imports Quarkus `3.36.3`.
3. **Specification binding path.** Not selected. Dependency convergence and boot proof were green
   for the SDK on Quarkus `3.32.3`, so the preferred SDK path stands.
4. **gRPC / JSON-RPC reference modules.** Out of MVP scope. REST satisfies the target environment.

## Earlier prototype notes

Inspected `feature/ai-agent-a2a-compiler-inplace` for dependency and producer patterns only.
That module used Quarkus `3.36.3`, gRPC, OIDC, and JPA task storage. None of that was merged
wholesale into `ai-service`.

## Constraints for later prompts

### Prompt 02 (Task persistence)

- Own PostgreSQL / Flyway wiring for A2A Tasks.
- Do not adopt `a2a-java-extras-task-store-database-jpa` in this MVP unless a later decision
  revisits storage. Prompt 02 owns the store contract.
- Keep SDK `TaskStore` usage behind the A2A adapter if the transport layer needs it later.

### Prompt 04 (A2A transport)

- Build on `a2a-java-sdk-reference-rest` producers: `@PublicAgentCard` and `AgentExecutor`.
- Enable them only when `qip.ai.a2a.enabled=true` (or the rollout flag that supersedes it).
- Reuse `A2aAgentCardFactory` / `A2aProtocolConstants` for the published card.
- Map facade commands at the adapter boundary. Do not pass SDK types into `productpipeline`.
- Cancel remains `TaskNotCancelable` for the launch horizon.
- `quarkus-security` is present for SDK `@Authenticated` routes. Local permit-all policy still
  belongs to prompts 04 and 07.

### Prompt 05 (Streaming)

- Use SDK streaming types (`Task`, `TaskStatusUpdateEvent`, `TaskArtifactUpdateEvent`) and the
  REST SSE writer path already exercised by `A2aStreamingEventSupport`.
- REST SSE frames from the SDK are `data:` / `id:` oriented. Preserve SDK wire format for
  client compatibility; do not invent a parallel event-name scheme in the adapter.
- Close streams at `INPUT_REQUIRED`, `COMPLETED`, and `FAILED` as planned.

## Boot proof scope

This prompt ships only:

- Maven coordinates and `quarkus-security`
- Protocol helper types and focused tests
- Optional CDI producers for Agent Card and a no-op `AgentExecutor`, gated by
  `qip.ai.a2a.enabled` (default `false`; `%test` sets `true` for the compatibility suite)

It does not implement Send Message, Get Task, persistence, or product-pipeline integration.

## Property gate: `qip.ai.a2a.enabled`

- Default / prod / dev: `false`. `A2aSdkBootProducers` is excluded at build time, so the no-op
  Agent Card and `AgentExecutor` are not published.
- `%test`: `true`. Compatibility tests exercise `@PublicAgentCard` and the no-op executor.
- Prompts 04 and 07 own enabling the real A2A transport and rollout flag. They may keep this
  build-time property, rename it, or supersede it with a runtime feature flag. Leave the default
  at `false` outside the Task 01 test profile until then.
