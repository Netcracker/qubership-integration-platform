"""Sticky A2A task/context IDs for ADK RemoteA2aAgent continue turns.

ADK copies task_id only on the mock function-response path. When a parent agent
re-transfers after INPUT_REQUIRED, the session fallback builds a message with
context_id but no task_id, so the server opens a new Task. This interceptor
restores the last remote task_id/context_id, drops non-protocol DataParts that
trigger "Structured data requires action", and recovers the user's clarify
answer when the re-transfer buries it under transfer_to_agent narration.
"""

from __future__ import annotations

from typing import Any, Optional

from a2a.types import Message, Part
from google.adk.a2a.agent.config import ParametersConfig, RequestInterceptor
from google.adk.agents.invocation_context import InvocationContext
from google.adk.agents.remote_a2a_agent import A2A_METADATA_PREFIX
from google.adk.events.event import Event

_TASK_KEY = A2A_METADATA_PREFIX + "task_id"
_CONTEXT_KEY = A2A_METADATA_PREFIX + "context_id"

#: Peer skill this lab drives. Matches A2aProtocolConstants.CREATE_CHAIN_SKILL_ID.
CREATE_CHAIN_SKILL_ID = "create-chain@2"


def latest_remote_ids(
    ctx: InvocationContext, agent_name: str
) -> tuple[Optional[str], Optional[str]]:
    """Return the newest task_id/context_id emitted by the named remote agent."""
    if ctx is None or ctx.session is None or not ctx.session.events:
        return None, None
    for event in reversed(ctx.session.events):
        if event.author != agent_name or not event.custom_metadata:
            continue
        metadata = event.custom_metadata
        task_id = _as_nonblank(metadata.get(_TASK_KEY))
        context_id = _as_nonblank(metadata.get(_CONTEXT_KEY))
        if task_id or context_id:
            return task_id, context_id
        nested = metadata.get(A2A_METADATA_PREFIX + "response")
        if isinstance(nested, dict):
            task_id = _as_nonblank(nested.get("id") or nested.get("taskId"))
            context_id = _as_nonblank(nested.get("contextId") or nested.get("context_id"))
            if task_id or context_id:
                return task_id, context_id
    return None, None


def strip_non_protocol_data_parts(message: Message) -> None:
    """Keep text parts; keep data parts only when they declare a create-chain action."""
    if message is None or not message.parts:
        return
    kept: list[Part] = []
    for part in message.parts:
        if part.HasField("text"):
            kept.append(part)
            continue
        if part.HasField("data") and _data_has_action(part):
            kept.append(part)
            continue
        # Drop raw/url/filename-only parts and data blobs without action.
    del message.parts[:]
    message.parts.extend(kept)


def sticky_request_interceptor(
    agent_name: str,
    strip_non_protocol_data: bool = True,
    skill_id: Optional[str] = CREATE_CHAIN_SKILL_ID,
) -> RequestInterceptor:
    """Build a before_request hook that pins task/context IDs for ``agent_name``.

    Set ``strip_non_protocol_data`` to False on a conformance bench. Dropping data parts
    client-side hides whatever ``ai-service`` would answer to them, which is a server
    question that a different A2A client would hit anyway.

    ``skill_id`` names the peer skill this agent drives. A2A carries no skill selector on
    SendMessage, so the peer falls back to its conversational skill when nothing is named --
    which is the wrong one for this lab. Pass None to leave the choice to the peer.
    """

    async def before_request(
        ctx: InvocationContext,
        message: Message,
        params: ParametersConfig,
    ) -> tuple[Message | Event, ParametersConfig]:
        task_id, context_id = latest_remote_ids(ctx, agent_name)
        if task_id and not _as_nonblank(message.task_id):
            message.task_id = task_id
        if context_id and not _as_nonblank(message.context_id):
            message.context_id = context_id
        if skill_id:
            message.metadata.update({"skillId": skill_id})
        if strip_non_protocol_data:
            strip_non_protocol_data_parts(message)
        if not _has_text_part(message):
            clarify = _latest_user_clarify_text(ctx)
            if clarify:
                part = Part()
                part.text = clarify
                message.parts.append(part)
        return message, params

    return RequestInterceptor(before_request=before_request)


def _has_text_part(message: Message) -> bool:
    return any(
        part.HasField("text") and part.text.strip() and not _is_transfer_narration(part.text)
        for part in message.parts
    )


def _is_transfer_narration(text: str) -> bool:
    """True for ADK's auto-generated transfer_to_agent narration, not real user input.

    A parent-agent re-transfer appends "For context:" plus "called tool" / "tool returned
    result" lines ahead of the clarify answer. Those parts pass the plain non-blank check in
    _has_text_part, which used to mask the missing answer and skip the recovery below.
    """
    text = text.strip()
    if text == "For context:":
        return True
    return text.startswith("[") and ("called tool" in text or "tool returned result" in text)


def _latest_user_clarify_text(ctx: InvocationContext) -> Optional[str]:
    """Recover clarify text when parent re-transfer drops the mock function_response."""
    if ctx is None or ctx.session is None or not ctx.session.events:
        return None
    for event in reversed(ctx.session.events):
        if event.author != "user" or not event.content or not event.content.parts:
            continue
        for part in event.content.parts:
            fr = getattr(part, "function_response", None)
            if fr is None:
                continue
            name = getattr(fr, "name", "") or ""
            if "required_user_input" not in name and "required_user_auth" not in name:
                continue
            response = getattr(fr, "response", None) or {}
            if isinstance(response, dict):
                result = response.get("result")
                if result is not None and str(result).strip():
                    return str(result).strip()
        break
    return None


def _data_has_action(part: Part) -> bool:
    data = part.data
    fields = getattr(data, "fields", None)
    if not fields:
        return False
    action = fields.get("action")
    if action is None:
        return False
    value = getattr(action, "string_value", None)
    return bool(value and str(value).strip())


def _as_nonblank(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
