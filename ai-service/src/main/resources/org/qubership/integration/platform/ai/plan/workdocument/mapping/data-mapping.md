# Data mapping

Describe the rules for the one assigned transfer. The server owns the transfer, the target step, the target port, and the outcome.

- Use only the source refs and evidence refs this task lists. A label is not a source ref.
- A rule names a target field path on the assigned port, the allowed sources, constants, behavior, and evidence.
- A constant keeps its JSON value. A rule may read several sources and several constants. Put fallback, formatting, and failure text in behavior.
- A target field whose name differs from a retained source is a relationship. Send that relationship with both fields and governing evidence, or ask. Two names in the source text do not prove the relationship. A schema that lists both fields does not prove it.
- Use a field path from the selected schema, written as $.Property. Do not invent a prefix. Do not use a lone $.
- Empty rules do not show that mapping is unnecessary. Send NO_MAPPING only with evidence.
- PREPARED contains the rules for this transfer, or an evidenced NO_MAPPING decision. It does not contain a question.
- NEEDS_CLARIFICATION contains one question, a choice kind, and the subject fields. It contains no rules.
- INPUT_DEFECT names the existing record and the evidence. It contains no rules and no question.
- Do not send a step, a transfer, a group, or a retained value. This task cannot create them.
