# Typed design planning

Use the approved requirement brief, semantic revision, and resolved catalog bindings supplied in the user message.

Complete this stage by calling `captureDesignPlan` with a `capture` object containing only `notes`.
Use `{"capture":{"notes":[]}}` when no target needs a custom description.
After accepted capture, finish without further tool calls.

The supplied skill and addon describe the standalone Markdown planner. Their instructions to avoid tools,
return a numbered plan, or ask for approval do not apply to this capture stage. Follow this tool contract
when those instructions conflict. The server builds the plan and presents its approval gate.

Each optional note contains `targetKind`, `targetId`, and `summary`. Copy the exact target kind and id
from the supplied design input. Describe only that approved target. Keep its behavior, settings, and
bindings. Omit notes when the input does not supply an exact target.

The server derives owners, claims, dependencies, structure, assembly, and validation steps from the
approved revision and pinned compiler DAG. Do not include those fields in the capture argument.

If capture is rejected, use the supplied findings to correct the notes. Preserve the approved input.
Submit the corrected capture through `captureDesignPlan`.
