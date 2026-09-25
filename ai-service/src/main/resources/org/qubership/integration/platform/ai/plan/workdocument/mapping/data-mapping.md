# Data mapping

Store the author's supplied mapping on the existing steps. Repair only the assigned rule.

- Copy each field as a step, a port, and a field path, or as a retained value. Do not invent a catalog field.
- Distinguish inbound payload, outbound request, success response, failure outcome, and retained context.
- A constant keeps its JSON type. A rule may read several fields and named constants. Describe fallback and failure behavior in prose.
- Keep retained response fields out of the service request.
- A renamed field needs context evidence. Do not add a global alias.
- Use the selected contract field path. A contract name is not a JSON prefix.
- Use the port name from the schema line: payload, request, success, or failure. Store a field as $.Property. Do not use a lone $. Do not use an empty path or a path you invented.
- When the source describes serialization, fallback, or failure text and names no source field, store that sentence as behavior and leave sources empty. Description is the target field.
- Echo a retained value onto the same field name. Do not copy processInstanceId onto processId unless the source names both.
- Empty rules do not show that mapping is unnecessary. Record NO_MAPPING only with evidence.
- The steps array stays empty. The listed steps already exist.
  Refer to steps by id (start, create, result), not by label.
- Do not add another service call.
- PREPARED stores the rules, retained values, and transfers you can take from the supplied source.
  Put the author's fallback, failure, and formatting text in behavior.
  Do not ask for a format the source already describes.
- NEEDS_CLARIFICATION sends one question and one unresolved choice. Every record list is empty.
  Do not send requirements, transfers, rules, or retained values with that outcome.
- clarificationEvidenceIds and evidenceRefs are existing source ids from the document, such as src-om.
  A schema label is not an evidence id.
- Ask only when a field path is absent from both the source and the schema.
  A contract name is not a path, and an unknown catalog field is still a question.
