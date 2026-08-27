# Generator Contracts

> Phase 7, Step 3 — Contract specification for every AI Generator.
> Each generator implements exactly one responsibility.

---

## Contract Structure

Every generator contract specifies:
- **Responsibilities** — What this generator does (singular purpose)
- **Inputs** — What data it consumes
- **Outputs** — What artifacts it produces
- **Rules Implemented** — Which rules from CORPORATE_RULE_ENGINE.yaml this generator enforces
- **Rules Forbidden** — What this generator MUST NOT do
- **Generated Artifacts** — Concrete output format
- **Validation Rules** — How to verify correctness

---

## GEN-01: Pattern Selection Generator

**Responsibility:** Select the appropriate golden pattern (GP-01..GP-07) based on chain requirements.

**Inputs:**
- Chain purpose (HTTP API, AsyncAPI task, sub-chain, event processing, orchestration, GDPR)
- Trigger type
- Integration requirements

**Outputs:**
- Selected golden pattern ID (GP-01..GP-07)
- Template structure (element skeleton)

**Rules Implemented:** R-1001, R-1002, R-1003, R-1004

**Rules Forbidden:**
- MUST NOT generate element-level configuration (delegated to element generators)
- MUST NOT select deprecated patterns
- MUST NOT create novel patterns outside GP-01..GP-07

**Generated Artifacts:**
- `chain.selectedPattern: GP-XX`
- Element skeleton with types and hierarchy (no configuration)

**Validation Rules:**
- Output pattern MUST be one of GP-01..GP-07
- Selected pattern MUST match trigger type and chain purpose
- Skeleton MUST conform to grammar-standard.md productions

---

## GEN-02: Trigger Generator

**Responsibility:** Configure trigger elements based on entry point requirements.

**Inputs:**
- Trigger type (from D-001)
- Route exposure (external/internal)
- Access control requirements
- Consumer group naming

**Outputs:**
- Fully configured trigger element(s)
- RBAC configuration (if external)

**Rules Implemented:** R-301, R-302, R-303, R-304, R-305, R-306

**Rules Forbidden:**
- MUST NOT configure execution body elements
- MUST NOT generate scripts
- MUST NOT select trigger type (input from Pattern Selection Generator)

**Generated Artifacts:**
- Trigger element with all mandatory properties
- contextPath (for http-trigger)
- elementId (for chain-trigger-2)
- Integration binding (for async-api-trigger, kafka-trigger-2)
- RBAC roles array (for external http-trigger)

**Validation Rules:**
- http-trigger: externalRoute declared, connectTimeout: 120000
- External routes: accessControlType: RBAC, roles non-empty
- Kafka: connectionSourceType: maas, sslProtocol: TLS
- async-api-trigger: triggers D-003b (finally-2 obligation)

---

## GEN-03: Structure Generator

**Responsibility:** Construct the chain execution body as a valid DAG.

**Inputs:**
- Selected golden pattern skeleton (from GEN-01)
- Element list with parent-child relationships
- Dependency specifications

**Outputs:**
- Complete chain structure (elements + dependencies array)
- Parent-child hierarchy
- Execution order

**Rules Implemented:** R-201, R-202, R-203, R-204, R-205, R-206, R-207, R-208, R-209, R-210, R-212, R-213, R-214, R-215, R-216, R-217

**Rules Forbidden:**
- MUST NOT configure element properties (delegated to element-specific generators)
- MUST NOT generate scripts
- MUST NOT select elements (input from other generators)

**Generated Artifacts:**
- `chain.yaml` structure with elements array
- Dependencies array (DAG edges)
- Parent-child nesting hierarchy

**Validation Rules:**
- Chain has >= 1 trigger + >= 1 executable element
- Dependency graph is a DAG (no cycles)
- All elements reachable from trigger
- All parent-child rules satisfied (if→condition, when→choice, etc.)
- Element count <= 200

---

## GEN-04: Error Handling Generator

**Responsibility:** Generate error handling structure (try-catch-finally-2) with proper catch/finally blocks.

**Inputs:**
- Trigger type (determines error handling obligation)
- Chain purpose (async-api requires finally-2)

**Outputs:**
- try-catch-finally-2 container with try-2, catch-2, optionally finally-2
- Catch block configuration (exception, priority)
- Finally block content (for async-api-trigger)

**Rules Implemented:** R-304, R-501, R-502, R-503, R-504

**Rules Forbidden:**
- MUST NOT generate v1 error handling (try/catch/finally)
- MUST NOT omit catch-2 exception property
- MUST NOT expose stack traces in error responses

**Generated Artifacts:**
- try-catch-finally-2 element hierarchy
- catch-2 with exception: java.lang.Exception, priority: 0
- Error response script template (CamelHttpResponseCode + JSON body)
- finally-2 with OM task result reporter (for async-api-trigger)

**Validation Rules:**
- try-catch-finally-2 has exactly 1 try-2 and >= 1 catch-2
- catch-2 has exception and priority
- async-api-trigger chains have finally-2
- Error script sets HTTP status code and JSON error body

---

## GEN-05: Auth Generator

**Responsibility:** Configure authentication for all service integrations.

**Inputs:**
- Integration type (internal/external)
- Target system list

**Outputs:**
- M2M configuration for internal calls
- Generic.Auth chain-call for external calls

**Rules Implemented:** R-401, R-402, R-601

**Rules Forbidden:**
- MUST NOT generate per-chain custom OAuth scripts
- MUST NOT use v1 M2M syntax (m2m: true)
- MUST NOT embed credentials in configuration

**Generated Artifacts:**
- `authorizationConfiguration.type: "m2m"` on service-calls
- chain-call-2 to Generic.Auth (for external systems)
- System identifier for Generic.Auth routing

**Validation Rules:**
- All service-calls use v2 M2M syntax
- External system calls route through Generic.Auth
- No hardcoded credentials

---

## GEN-06: Integration Generator

**Responsibility:** Configure service-call and sender elements with proper hooks and bindings.

**Inputs:**
- Target service (from service catalog)
- Operation (HTTP method, endpoint)
- Hook requirements

**Outputs:**
- Configured service-call or sender element
- Before/after hook script references
- Kafka topic bindings (for kafka-sender-2)

**Rules Implemented:** R-403, R-406

**Rules Forbidden:**
- MUST NOT generate service-call without after hooks
- MUST NOT hardcode Kafka topic names
- MUST NOT use http-sender when service-call is available
- MUST NOT configure authentication (delegated to GEN-05)

**Generated Artifacts:**
- service-call with integrationSystemId, integrationOperationId
- before hook script reference
- after hook script references (200, default)
- kafka-sender-2 with MaaS topic binding

**Validation Rules:**
- service-call has >= 1 after hook (200)
- Kafka topics use %%{uuid} MaaS placeholders
- integrationSystemId/integrationOperationId resolve to catalog

---

## GEN-07: Routing Generator

**Responsibility:** Generate conditional routing structures (condition/if/else).

**Inputs:**
- Number of routing branches
- Routing expressions (Camel Simple)
- Branch execution bodies

**Outputs:**
- Conditional routing structure
- Expression configuration
- Priority ordering for sibling `if` children

**Rules Implemented:** R-211, R-904

**Rules Forbidden:**
- MUST NOT nest conditions deeper than 3 levels
- MUST NOT use dynamic chain-call targets for routing
- MUST NOT generate deprecated `choice` / `when` / `otherwise`

**Generated Artifacts:**
- condition/if/else block for every branch count (`if` 1..n, optional `else`)
- Camel Simple expressions on `if` elements
- unique `priority` on sibling `if` children (starting at 0)

**Validation Rules:**
- Expressions are valid Camel Simple
- `if` priorities are unique
- Nesting depth <= 3 for condition/if/else
- Deprecated `choice` is not generated

---

## GEN-08: Timeout Generator

**Responsibility:** Configure timeout values maintaining the descending hierarchy.

**Inputs:**
- Element type and execution level
- Parent timeout value

**Outputs:**
- Configured timeout values

**Rules Implemented:** R-408, R-410

**Rules Forbidden:**
- MUST NOT set http-trigger connectTimeout != 120000
- MUST NOT set child timeout > parent timeout
- MUST NOT set chain-call-2 timeout > 120000
- MUST NOT set service-call connectTimeout > 60000

**Generated Artifacts:**
- http-trigger: connectTimeout: 120000
- chain-call-2: timeout: 30000
- service-call: connectTimeout: 12000, socketTimeout: 12000

**Validation Rules:**
- http-trigger: 120000 (fixed)
- chain-call-2: <= parent trigger timeout
- service-call: <= parent chain-call or trigger timeout
- Hierarchy: trigger > chain-call > service-call

---

## GEN-09: Retry Generator

**Responsibility:** Configure retry behavior based on operation criticality.

**Inputs:**
- Operation criticality (standard / critical / rate-limited)

**Outputs:**
- retryCount and retryDelay configuration

**Rules Implemented:** R-404, R-405

**Rules Forbidden:**
- MUST NOT set retryCount > 5
- MUST NOT set retryDelay < 5000 when retry > 0
- MUST NOT configure retry on non-critical standard operations

**Generated Artifacts:**
- Standard: retryCount: 0
- Critical: retryCount: 3, retryDelay: 5000-10000
- Rate-limited: loop-2 with doWhile/maxLoopIteration

**Validation Rules:**
- retryCount <= 5
- retryDelay >= 5000 when retry > 0
- Non-critical operations have retryCount: 0

---

## GEN-10: Data Flow Generator

**Responsibility:** Generate scripts for exchange property management and data passing.

**Inputs:**
- Input parameters to extract
- Service response fields to capture
- Property naming requirements

**Outputs:**
- Request parser script
- Before hook scripts
- After hook scripts
- Property binding instructions

**Rules Implemented:** R-901

**Rules Forbidden:**
- MUST NOT pass data via body between elements
- MUST NOT delete platform properties (breadcrumbId, etc.)
- MUST NOT use hardcoded values where properties should be used

**Generated Artifacts:**
- Request parser script (first element): JsonSlurper → setProperty
- Before hook script: getProperty → JsonOutput → body
- After hook script: JsonSlurper → setProperty
- Error response script: getProperty("CamelExceptionCaught") → HTTP error

**Validation Rules:**
- All extracted values stored as exchange properties
- Property names follow camelCase convention
- Body used only for current request/response
- No inter-element body dependency

---

## GEN-11: Composition Generator

**Responsibility:** Generate chain composition structures (chain-call-2, reuse blocks).

**Inputs:**
- Delegation requirements (which sub-chains to call)
- Reuse candidates (repeated sequences)
- Blocking requirements (sync vs fire-and-forget)

**Outputs:**
- chain-call-2 elements with static elementId
- reuse blocks and reuse-references

**Rules Implemented:** R-407, R-409, R-1005

**Rules Forbidden:**
- MUST NOT use dynamic chain-call elementId
- MUST NOT create dangling reuse-references
- MUST NOT create recursive reuse without guard condition

**Generated Artifacts:**
- chain-call-2 with elementId (static UUID), block, timeout, failIfNoConsumers
- reuse block definitions (name, execution body)
- reuse-reference elements (reuseElementId)

**Validation Rules:**
- All elementIds are static UUIDs
- All reuseElementIds reference existing reuse blocks
- block: true has timeout configured
- fire-and-forget sub-chains have own error handling

---

## GEN-12: Loop Generator

**Responsibility:** Generate iteration structures (loop-2).

**Inputs:**
- Iteration type (count-based / do-while / pagination)
- Loop expression
- Safety limits

**Outputs:**
- Configured loop-2 element

**Rules Implemented:** R-902

**Rules Forbidden:**
- MUST NOT use deprecated loop + loop-expression
- MUST NOT omit maxLoopIteration
- MUST NOT nest loops deeper than 2 levels

**Generated Artifacts:**
- loop-2 with expression property
- doWhile configuration (for pagination)
- maxLoopIteration safety guard
- Loop variable management scripts

**Validation Rules:**
- maxLoopIteration set
- Expression is valid Camel Simple
- Nesting depth <= 2
- No deprecated v1 loop elements

---

## GEN-13: Parallel Generator

**Responsibility:** Generate parallel execution structures (split-async-2, split-2).

**Inputs:**
- Parallel branch definitions
- Branch type (independent operations vs collection processing)
- Aggregation requirements

**Outputs:**
- Configured split element with branches

**Rules Implemented:** R-209

**Rules Forbidden:**
- MUST NOT use deprecated v1 split elements
- MUST NOT nest split-async-2
- MUST NOT create split-async-2 with zero branches

**Generated Artifacts:**
- split-async-2 with 1+ async-split-element-2 (fire-and-forget)
- split-2 with split-element-2 and optional main-split-element-2 (for collections)
- Aggregation strategy configuration

**Validation Rules:**
- split-async-2 has >= 1 branches
- No v1 split elements
- No nested split-async-2
- Aggregation strategy set when needed

---

## GEN-14: Security Generator

**Responsibility:** Configure security aspects (RBAC, TLS, data masking, credential management).

**Inputs:**
- Endpoint exposure level (external/internal)
- Data sensitivity (GDPR/PII)
- Kafka connection requirements

**Outputs:**
- RBAC configuration
- TLS settings
- Data masking configuration
- Secured variable references

**Rules Implemented:** R-302, R-305, R-602, R-603, R-604

**Rules Forbidden:**
- MUST NOT create external endpoints without RBAC
- MUST NOT use unencrypted Kafka
- MUST NOT embed credentials
- MUST NOT use wildcard roles

**Generated Artifacts:**
- accessControlType: RBAC with roles array
- sslProtocol: TLS for Kafka
- maskingEnabled + maskedFields for GDPR
- #{SECURED_VAR} references for secrets

**Validation Rules:**
- All external routes have RBAC
- All Kafka has TLS
- No hardcoded credentials
- GDPR chains have masking enabled

---

## GEN-15: Monitoring Generator

**Responsibility:** Configure observability (session logging, DPT, checkpoints, correlation).

**Inputs:**
- Chain complexity (element count)
- Chain criticality
- Correlation requirements

**Outputs:**
- Chain-level monitoring configuration
- Checkpoint elements
- Correlation property setup

**Rules Implemented:** R-701, R-702, R-703, R-704

**Rules Forbidden:**
- MUST NOT disable session logging
- MUST NOT disable DPT events
- MUST NOT omit propagateContext on cross-service calls

**Generated Artifacts:**
- sessionsLoggingLevel: SESSION
- dptEventsEnabled: true
- propagateContext: true on service-calls
- checkpoint elements (for 20+ element chains)
- Labels for domain classification

**Validation Rules:**
- Session logging enabled
- DPT events enabled
- propagateContext on service-calls
- Checkpoints at major boundaries (if >= 20 elements)

---

## GEN-16: Naming Generator

**Responsibility:** Apply naming conventions to all generated artifacts.

**Inputs:**
- Domain
- Communication direction (Inbound/Outbound/Internal)
- Action description
- Element UUIDs

**Outputs:**
- Chain name
- Script file names
- Service name
- Exchange property names

**Rules Implemented:** R-801, R-802, R-803

**Rules Forbidden:**
- MUST NOT use ticket numbers in names
- MUST NOT use numeric prefixes
- MUST NOT use non-camelCase property names
- MUST NOT use uppercase or space-separated service names

**Generated Artifacts:**
- Chain name: `{Domain}.{Direction}.{Action}`
- Scripts: `script-{uuid}.groovy`, `script-before-{uuid}.groovy`, `script-{code}-{uuid}.groovy`
- Services: `{domain}-{purpose}-service`
- Properties: camelCase identifiers

**Validation Rules:**
- Chain name matches {Domain}.{Direction}.{Action}
- No ticket numbers or numeric prefixes
- Script names follow convention
- Property names are camelCase

---

## GEN-17: Element Validator

**Responsibility:** Validate that all elements use current v2 types from the approved language.

**Inputs:**
- Element type list

**Outputs:**
- Validation result (pass/fail per element)

**Rules Implemented:** R-101, R-102

**Rules Forbidden:**
- MUST NOT accept deprecated v1 elements
- MUST NOT accept unknown element types

**Generated Artifacts:**
- Validation report: element-by-element pass/fail
- Migration recommendations for deprecated elements

**Validation Rules:**
- All elements in the 42 current types
- No deprecated elements
- No unknown types

---

## Generator Execution Order

```
1. GEN-01  Pattern Selection Generator     → selects golden pattern
2. GEN-02  Trigger Generator               → configures triggers
3. GEN-03  Structure Generator             → builds DAG skeleton
4. GEN-04  Error Handling Generator        → adds try-catch-finally
5. GEN-05  Auth Generator                  → configures authentication
6. GEN-06  Integration Generator           → configures service-calls/senders
7. GEN-07  Routing Generator               → adds conditional routing
8. GEN-08  Timeout Generator               → sets timeout hierarchy
9. GEN-09  Retry Generator                 → configures retry
10. GEN-10 Data Flow Generator             → generates scripts
11. GEN-11 Composition Generator           → adds chain-calls/reuse
12. GEN-12 Loop Generator                  → adds loops
13. GEN-13 Parallel Generator              → adds split/parallel
14. GEN-14 Security Generator              → applies security
15. GEN-15 Monitoring Generator            → adds observability
16. GEN-16 Naming Generator                → applies naming
17. GEN-17 Element Validator               → final validation
```

No generator may perform the responsibility of another generator.
