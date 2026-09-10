# 02 - Keep binding clarification when the service name is ambiguous or unusable

**What to build:** When a catalog service name matches several operations, or is empty, unknown, or incompatible, the run stays on an actionable candidate card. The compiler is not invoked. The unresolved choice is explained, and no semantic repair is spent. If ownership cannot be established, stop safely without asking a question that has no consumer.

**Blocked by:** 01 - Apply a missing catalog binding from a clarification answer

**Status:** ready-for-agent

- [ ] A2: Multiple catalog matches stay on an actionable candidate card; compiler is not invoked
- [ ] A3: Empty, unknown, or incompatible answers explain the unresolved choice and do not spend a semantic repair
- [ ] If ownership cannot be established, stop safely without asking a question that has no consumer
