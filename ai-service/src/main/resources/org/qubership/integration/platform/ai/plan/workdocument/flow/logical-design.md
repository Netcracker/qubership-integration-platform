# Logical design

Describe the integration actions. Repair only the assigned records.

- Store a trigger, each service call, each reply, and local processing as separate steps.
- A synchronous call includes request, success, and failure as connection outcomes on that call. Do not add a step that only receives the synchronous result. request enters the call from the previous step. success and failure leave the call. Do not add a request outcome that starts at the call.
- A callback is its own step with source evidence and a correlation outcome. Two intentional calls keep different step ids. Do not merge steps that share an operation, label, or service name.
- Write connection outcomes in lowercase: request, success, failure, or correlation. Every connection names a target step alias.
- Use sequence, condition, split, loop, retry, and error-scope groups. Copy source references onto the records they support.
- A semantic contradiction is an input defect for a logical repair. Do not rebuild the design.
- A payload constant stays a requirement. It is not a catalog operation or an extra call.
