---
paths:
  - "**"
---

Load `change-description-authoring`, with `english-developer-style`, before writing or rewording
a pull request title or description, or the title or body of a GitHub issue. A commit message
needs only `english-developer-style` and the Conventional Commits format, so do not load
`change-description-authoring` for one, whatever the `english-developer-style` rule says about
installed authoring skills.

When an AI agent reports a run result on GitHub, sign the result once as described in
[AI agent comment signatures](https://github.com/Netcracker/qubership-ai-packages/blob/main/research/ai-autonomous-design/README.md#ai-agent-comment-signatures).
This applies to PR descriptions, issue bodies, top-level comments, review bodies, and stops for
human input. Keep inline review comments unsigned. Put the footer on the last line after an
empty line. Use the main agent's observed model and total runtime usage across its subagents
and retries. Omit unknown fields. When the controller publishes the result, it supplies the
footer.
