# Product CREATE semantic rubric

Score each dimension as an integer from 0 to 5.

| Dimension | Meaning |
|-----------|---------|
| intentFidelity | Plan matches explicit positive and negative user facts |
| completeness | Decision-critical endpoints, branches, scripts, and exclusions are present |
| executability | Plan could be materialized without inventing missing bindings |
| unnecessaryComplexity | No forbidden or decorative topology |

Reject non-integer or out-of-range scores. Reliability failures (TPM, MCP, Docker, catalog, timeout) stay in `reliabilityFailures` and never become semantic scores.

## Forbidden facts (exact match)

- Treat each forbidden fact as an **exact string** (or an exact `key=value` token such as `else.condition=...`).
- Do **not** treat a shorter token as forbidden when only a longer fact is listed.
- Example: if forbidden facts include `else.condition` / `else.priority` but not `else`, a property-less / bare `else` branch fact is **valid CIP** and must **not** lower scores.
- Only penalize when the plan actually contains the listed forbidden fact (for example `else.condition` or `else.condition=...`).

## Endpoint vocabulary

- In `endpointFacts`, the token `external` means HTTP trigger `externalRoute=true` (public route visibility).
- That is **not** a `service-call` and is **not** an "external service call" unless those exact forbidden facts are listed.
- Do not lower scores solely because `endpointFacts` contains `external` when the scenario forbids service-calls or "external service calls".

## Generator ownership versus topology

- `cip-service-call-generator` is an ownership label. Its presence does not prove that the plan
  contains a `service-call` element.
- The same generator configures native outbound elements such as `kafka-sender-2`. For those
  elements, an empty `serviceBindings` collection is correct because no catalog or APIHub binding
  is required.
- Determine forbidden topology from the planned element types and structured claims, not from a
  generator skill name or a negated phrase such as `No service calls` or `No APIHub`.

## Required response literals

- Required strings such as `even minute` / `odd minute` count as present when they appear anywhere in the supplied plan JSON (`scriptOutcomes`, `planText`, branch notes, and so on).
- Prefer completeness of required literals over stylistic script shape. Nested `condition`/`if`/`else` plus scripts under branches is a valid CIP shape.
