import os
import sys
from pathlib import Path

from google.adk.a2a.agent.config import A2aRemoteAgentConfig
from google.adk.agents.remote_a2a_agent import RemoteA2aAgent
from google.adk.apps.app import App
from google.adk.apps import ResumabilityConfig

_AGENTS_ROOT = Path(__file__).resolve().parent.parent
if str(_AGENTS_ROOT) not in sys.path:
    sys.path.insert(0, str(_AGENTS_ROOT))

from sticky_a2a import sticky_request_interceptor  # noqa: E402


root_agent = RemoteA2aAgent(
    name="qip_direct_agent",
    description="Direct A2A proxy for the QIP chain builder.",
    agent_card=os.environ.get(
        "QIP_A2A_AGENT_CARD_URL",
        "http://qip-ai-service:8080/.well-known/agent-card.json",
    ),
    use_legacy=False,
    # See qip_top_level_agent: INPUT_REQUIRED interrupts need a native rerun to continue A2A.
    rerun_on_resume=True,
    config=A2aRemoteAgentConfig(
        request_interceptors=[
            # Conformance bench: keep the task_id workaround, send parts unmodified.
            sticky_request_interceptor("qip_direct_agent", strip_non_protocol_data=False)
        ]
    ),
)

app = App(
    name="qip_direct_agent",
    root_agent=root_agent,
    resumability_config=ResumabilityConfig(is_resumable=True),
)
