---
name: component-testing
description: >-
  Extend component test coverage for QIP chains through catalog-generated snapshot
  bundles and micro-engine YAML scenarios. Use when adding scenarios, exported test
  chains, or fixtures to this flow, or assessing its coverage. Unit and CDI
  configuration tests of production Java code belong to java-unit-testing.
---

# Component testing

Test exported chains through the existing catalog-to-micro-engine flow. Runtime
Catalog imports the configurations, persists snapshots, and generates micro-domain
XML. Micro-engine loads that XML through production route loading and executes the
scenarios with controlled external dependencies.

## Choose the test boundary

| Requested behavior | Test approach |
| --- | --- |
| Generated routes, element settings, component beans, and their runtime interactions | Use this skill and the snapshot scenario framework. |
| One production Java class, Mockito behavior, or a small CDI configuration | Use `java-unit-testing`, including its Quarkus component reference. |
| Catalog HTTP behavior and PostgreSQL or Consul effects in the local stack | Use `runtime-catalog-api-testing`. |
| Endpoint options absent from the exported element | Use an endpoint test; state that it does not establish generated-chain support. |

Verify framework code used only by component tests through actual component
scenarios. Do not create separate unit tests for its fixtures, drivers, broker and
proxy helpers, runners, launchers, discovery code, models, or assertion utilities. Retain the
tests that execute component scenarios and the catalog snapshot producer.

The snapshot consumer belongs to `micro-engine`. Do not assume Spring `engine`,
sessions-management, UI, or the Go testing service has the same runner. For those
modules, inspect their local test guidance before choosing an approach.

Apply `english-developer-style` when writing identifiers, YAML descriptions, or
documentation. A request to extend coverage authorizes the necessary test edits
and focused local runs; carry those through without asking for confirmation at
each step.

Use English text and Latin characters in test specifications, including names,
descriptions, comments, textual fixture data, and expectations. Replace non-Latin
words and phrases consistently in inputs and expected values while preserving
what each assertion checks.
Use Latin letters with diacritics, such as `café`, in UTF-8 scenarios to preserve
multibyte coverage.
Check decoded YAML strings as well as source text. Unicode escapes follow the
same language rules.

## Find an observable gap

Read `micro-engine/TESTING.md` for the relevant execution and fixture behavior.
Use `micro-engine/SNAPSHOT-COVERAGE.md` to find candidate gaps, then check the
current manifests. The matrix can lag behind test changes; neither its counts nor
an element's presence establishes which behaviors pass.

Inspect the element descriptor, generator templates, runtime implementation, and
nearest existing scenario before writing expectations. Locate descriptors and
templates under `integration-build-pipeline/src/main/resources`; use their actual
paths and settings rather than assuming a Camel option is exposed by the element.

For each proposed case, identify the input, configured behavior, assertion, and
external boundary. Prefer cases that add evidence: a distinct branch, a supported
option, an observed failure, or recovery after that failure. Check both the route
result and the relevant effect, such as an outbound request or a stored record.
Where the contract requires no effect, assert its absence.

Keep scenario coverage separate from JaCoCo line coverage. A unit test of a
processor does not verify its generated route, and a passing route does not cover
every branch of that processor. Run JaCoCo when the task calls for it.

## Extend the existing flow

1. Prefer another invocation or scenario in the element's existing manifest.
   Read [Scenario design](references/scenarios.md) for the manifest rules and
   examples to adapt. Add an exported chain when different element settings or
   topology are needed.
2. Reuse the existing drivers and fixture providers. Read
   [Fixtures and assertions](references/fixtures.md) when adding external behavior,
   async checks, or a capability the existing YAML cannot express.
3. Keep production processors and generated component beans in the execution
   path. Control external dependencies at their boundary. Do not substitute a
   mock for the behavior the new scenario is meant to exercise.
4. Group compatible invocations when they must share state or reuse an expensive
   broker. Keep failure and recovery in the same scenario when checking recovery
   of the existing client. Use separate scenarios for incompatible configuration
   or a required fresh client, broker, or credentials cache.
5. Follow [Verification](references/verification.md). Regenerate affected inputs,
   run the selected scenarios, and inspect their recorded results. Broaden the run
   when shared fixtures or runner changes affect other scenarios.
6. Update the relevant row in `micro-engine/SNAPSHOT-COVERAGE.md` with the new
   assertions and remaining limits. Update `micro-engine/TESTING.md` when adding
   fixture or manifest capabilities. Keep provider-specific details in those
   documents and their implementation rather than duplicating them in this skill.

If a new case exposes a production defect, report the expected and actual
behavior, the failing scenario, and the reproduction command. Follow the
repository rule to present findings and stop until the user chooses a fix. Do not
weaken an assertion, disable a case, or patch production code to make the coverage
task pass. A request to implement tests does not authorize unrelated fixes.

## Report the evidence

Report the target and scenario IDs, behavior asserted, commands and results, and
whether the bundle was regenerated or reused. Distinguish declared assertions
from executed tests, including blocked or failed runs.

Name the limits of the tested boundary. Controlled checkpoint storage does not
establish persistent recovery; an emulator does not exercise live cloud IAM; a
micro-engine result does not establish Spring-engine parity. Preserve these
limits when updating the coverage matrix.
