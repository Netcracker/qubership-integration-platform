# Define transfers

Describe the data boundaries for one assigned target step.

- Propose transfer endpoints, outcomes, requirement coverage, and retained placeholders.
- Cite an indexed source passage for every coverage decision.
- Record NO_MAPPING with a passage when a requirement needs no data movement.
- Send existingId for a record this task may update, and leave alias empty.
- Send alias for a new record, and leave existingId empty.
- Do not send both, and do not invent an id.
- Repeat every retained id the updated transfer still needs. An omitted sibling stays as it is.
- Do not send a task key, ordering, a runtime handler, or a field rule.
- Do not add a service call. Success and failure stay outcomes of the assigned step.
- Two field names in the source are not a rename. Leave that choice for a later question.
