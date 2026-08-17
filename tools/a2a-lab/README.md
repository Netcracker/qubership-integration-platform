# Local A2A lab

Run Google ADK Web and the official A2A Inspector against the `qip-ai-service` container in the existing QIP Compose
stack.

The lab exposes two ADK agents:

- `qip_direct_agent` forwards user messages directly to `qip-ai-service` over A2A and does not require an LLM key.
- `qip_top_level_agent` uses an ADK LiteLLM coordinator with the same OpenAI-compatible configuration as
  `qip-ai-service`.

Both agents export an ADK `App` with `ResumabilityConfig(is_resumable=True)` and set `rerun_on_resume=True` on the remote
A2A agent. Without that, ADK treats `INPUT_REQUIRED` as a long-running interrupt and never sends the clarify reply back
to `qip-ai-service` (parent re-transfer ends the turn with no A2A call).

## What the lab does and does not prove

The lab is a diagnostic bench for one client. A green run here says ADK can drive `create-chain@2`; it does not say
`ai-service` is correct for every A2A client.

Keep that distinction when a request is rejected. Adapting the ADK agents until the error disappears — trimming parts
in `sticky_a2a.py`, reshaping a payload — makes the lab pass while a different top-level agent still fails on the same
request. Diagnose from the `ai-service` log instead: every inbound Message is logged with its part shape, and every
typed rejection is logged with the stage and error type. Fix what that shows, in `ai-service`.

`sticky_a2a.py` is the exception that proves the rule: it works around ADK dropping `task_id` on re-transfer, which is
a client-side defect with no server-side counterpart. Anything that looks like a protocol disagreement belongs in
`ai-service`.

## Configure the lab

Create the ignored local configuration file:

```bash
scripts/a2a-lab.sh init
```

The ADK container loads `infrastructure/ai-service-dev.env` and then `infrastructure/.env.local`. The second file
overrides `LLM_API_KEY`, `LLM_BASE_URL`, and `LLM_CHAT_MODEL` without copying secrets into the lab configuration.

The Compose overlay enables the A2A feature flag, connects `qip-ai-service` to the `ai_a2a` PostgreSQL database, and
advertises `http://qip-ai-service:8080` in the Agent Card. This URL is reachable by both lab clients on the Compose
network. Port `8094` remains available for health and Agent Card checks from the host.

## Start the linked stack

Build and start `qip-ai-service`, its dependencies, ADK Web, and A2A Inspector:

```bash
scripts/a2a-lab.sh up
```

The official Inspector `main` branch still uses the pre-1.0 Python SDK. The lab pins the tested head of
[A2A Inspector PR 145](https://github.com/a2aproject/a2a-inspector/pull/145), which adds A2A 1.0 support and locks
`a2a-sdk==1.1.2`. Remove this compatibility build after the upstream project merges A2A 1.0 support.

Check all endpoints:

```bash
scripts/a2a-lab.sh check
```

Rebuild and recreate only ADK Web after changing the shared LLM configuration:

```bash
scripts/a2a-lab.sh restart-adk
```

Open these local interfaces:

- ADK Web: <http://localhost:8000>
- A2A Inspector: <http://localhost:8088>

Both diagnostic UIs bind to `127.0.0.1` and are not exposed on the LAN.

In Inspector, connect to `http://qip-ai-service:8080`. The Inspector backend and `qip-ai-service` share the Compose
network, so `localhost:8094` is not the correct URL from inside the Inspector container.

## Diagnose the stack

Follow the relevant logs:

```bash
scripts/a2a-lab.sh logs
```

Render the merged Compose configuration without starting containers:

```bash
scripts/a2a-lab.sh config
```

Stop the two diagnostic UIs without stopping the QIP services:

```bash
scripts/a2a-lab.sh stop
```

## Read the wire between the agents

The lab sets `QIP_AI_A2A_LOG_INBOUND_PAYLOAD=true`, so `qip-ai-service` logs every inbound JSON-RPC body verbatim,
before deserialization, as `A2A inbound payload version=… body=…`. That line is the only place the caller's wire form
survives: a part nested one level off, or a field ADK named differently, reads back as absent everywhere else.

```bash
scripts/a2a-lab.sh logs | grep 'A2A inbound payload'
```

The body carries the user's own text, so keep this flag out of deployed environments.

For the other direction, open the failing turn in ADK Web and expand its event. ADK records the A2A request and
response it exchanged with `qip_chain_builder` in the event metadata, including Task state, artifacts, and part shapes.
A2A Inspector shows the same frames for requests you send from Inspector itself; it does not observe ADK's traffic.

## Initial compatibility checks

Use `qip_direct_agent` first and verify:

1. ADK loads the Agent Card.
2. A new request creates an A2A Task.
3. Streaming progress appears before the Task reaches a terminal or input-required state.
4. Review artifacts remain visible when input is required.
5. A follow-up message continues the same Task and context.
6. A completed Task exposes the final chain artifacts.

Then repeat the flow with `qip_top_level_agent`. Confirm that it stops for explicit user approval and never generates
an approval decision itself.

## Create a chain from ADK Web

1. Open <http://localhost:8000> and select **`qip_direct_agent`**.
2. Send a short English requirement, for example: HTTP trigger GET `/health` plus one downstream HTTP GET `/status`.
3. When the Task asks for approval, reply with the printed token (`approve` plus the 8-character hash).
4. When it asks for an IDS, reply `yes` or `no` (not a longer sentence). `no` skips showing the design document.
5. When it asks for mappings, reply `PASS_THROUGH` unless you need explicit field mappings.
6. Continue the same session for later approvals (`approve <hash>`). Do not start a new chat.

`qip_direct_agent` pins `metadata.skillId=create-chain@2` and restores `task_id` on continue turns. The HITL send
button in ADK Web may stay disabled after paste; press Enter in the interrupt box to submit.
