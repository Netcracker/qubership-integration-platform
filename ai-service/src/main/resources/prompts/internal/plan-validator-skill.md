# plan-validator

Internal ai-service BUILD_CHAIN skill. Not part of the CIP compiler APM skill pack.

## Purpose

Pre-build validator in the BUILD_CHAIN spine after `generator-plan-manifest`. Review the
`ChainPlanGraph` against compiler validation rules and the requirement brief.

## Inputs

- `CHAIN_PLAN_GRAPH`
- Requirement brief
- Generator plan manifest (when present)

## Outputs

- `PRE_BUILD_VALIDATION`
- `PLAN_CAPTURE_OUTCOME`

## Responsibilities

- Validate the plan graph and requirement brief before catalog materialization.
- Call **captureValidationResult** in the same turn once the report is complete.
- Prefer `WARNING` / `INFO` for advisory findings; use `BLOCKER` only when a concrete rule applies.
- Do not call captureGraphPatch or captureChainPlan.

## Scope

- Pre-build validation checks the **plan graph** and requirement brief, not full catalog element
  completeness. Catalog defaults (for example `accessControlType`, `handleChainFailureAction`,
  `connectTimeout`) are applied later during materialization.
- Use **describeElementPatchSchema** for allowed property keys, not as a plan blocker checklist.
- For `http-trigger`, `externalRoute` must match the brief: `true` for external/public routes,
  `false` for internal routes.
- Missing optional catalog properties on an otherwise valid plan are `WARNING`, not `BLOCKER`.
- `valid=false` requires at least one `BLOCKER` issue in the report.
