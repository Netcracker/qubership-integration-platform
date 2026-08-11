import os
import sys
from pathlib import Path

from google.adk.a2a.agent.config import A2aRemoteAgentConfig
from google.adk.agents.llm_agent import Agent
from google.adk.agents.remote_a2a_agent import RemoteA2aAgent
from google.adk.apps.app import App
from google.adk.apps import ResumabilityConfig
from google.adk.models.lite_llm import LiteLlm

_AGENTS_ROOT = Path(__file__).resolve().parent.parent
if str(_AGENTS_ROOT) not in sys.path:
    sys.path.insert(0, str(_AGENTS_ROOT))

from sticky_a2a import sticky_request_interceptor  # noqa: E402


chain_builder = RemoteA2aAgent(
    name="qip_chain_builder",
    description=(
        "Designs, plans, materializes, validates, and repairs QIP integration chains "
        "through the A2A protocol."
    ),
    agent_card=os.environ.get(
        "QIP_A2A_AGENT_CARD_URL",
        "http://qip-ai-service:8080/.well-known/agent-card.json",
    ),
    use_legacy=False,
    # ADK 2.5 treats INPUT_REQUIRED as a long-running interrupt. Without rerun_on_resume,
    # answering the mock function call never re-enters RemoteA2aAgent, so no A2A continue
    # request is sent.
    rerun_on_resume=True,
    config=A2aRemoteAgentConfig(
        request_interceptors=[sticky_request_interceptor("qip_chain_builder")]
    ),
)

root_agent = Agent(
    name="qip_top_level_agent",
    model=LiteLlm(
        model=f"openai/{os.environ.get('LLM_CHAT_MODEL', 'gpt-4o-mini')}",
        api_key=os.environ.get("LLM_API_KEY", ""),
        api_base=os.environ.get("LLM_BASE_URL", "https://api.openai.com/v1"),
    ),
    description="Coordinates user conversations with the QIP A2A chain builder.",
    instruction="""
You help the user create an integration chain in QIP.

Delegate chain requirements, clarifications, and explicit approval responses to qip_chain_builder. Preserve the user's
meaning and do not invent requirements, artifact identifiers, hashes, revisions, or approval decisions.

When qip_chain_builder requests input, show the request and its reviewable artifacts to the user. Stop and wait for a
new user message. Treat approval as valid only when the user explicitly confirms it in that new message. Never infer
approval from silence, previous messages, or your own assessment. Do not approve on the user's behalf.

After completion, summarize the final state and list the artifacts returned by qip_chain_builder.
""".strip(),
    sub_agents=[chain_builder],
)

# Resumability routes the mock input function_response straight back to qip_chain_builder
# instead of restarting at the parent LLM (which re-transfers with empty input and skips A2A).
app = App(
    name="qip_top_level_agent",
    root_agent=root_agent,
    resumability_config=ResumabilityConfig(is_resumable=True),
)
