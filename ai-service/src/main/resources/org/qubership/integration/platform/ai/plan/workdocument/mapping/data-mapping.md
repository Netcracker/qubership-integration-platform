# Data mapping

Store the author's supplied mapping on the existing steps. Repair only the assigned rule.

- Copy each field as a step, a port, and a field path, or as a retained value. Do not invent a catalog field.
- Distinguish inbound payload, outbound request, success response, failure outcome, and retained context.
- A constant keeps its JSON type. A rule may read several fields and named constants. Describe fallback and failure behavior in prose.
- Keep retained response fields out of the service request.
- A renamed field needs context evidence. Do not add a global alias.
- Use the selected contract field path. A contract name is not a JSON prefix.
- Empty rules do not show that mapping is unnecessary. Record NO_MAPPING only with evidence.
- Do not add another service call.
