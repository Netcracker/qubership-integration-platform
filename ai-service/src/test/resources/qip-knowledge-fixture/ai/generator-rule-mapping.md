# Generator-Rule Mapping

> Phase 7, Step 7 — Maps every rule to its responsible generator.
> No rule belongs to more than one generator.

---

## Mapping Matrix

| Rule ID | Rule Name | Generator | Input Artifact | Output Artifact | Validation Rule | Dependencies |
|---------|-----------|-----------|----------------|-----------------|-----------------|--------------|
| **Language Rules** | | | | | | |
| R-101 | v2 Element Mandate | GEN-17 Element Validator | element_type | validation_result | VR-L-001 | None |
| R-102 | Valid Element Type | GEN-17 Element Validator | element_type | validation_result | VR-L-002 | None |
| **Grammar Rules** | | | | | | |
| R-201 | Chain Must Have Trigger | GEN-03 Structure Generator | chain.elements | chain_structure | VR-G-001 | GEN-01 |
| R-202 | Chain Must Have Executable | GEN-03 Structure Generator | chain.elements | chain_structure | VR-G-002 | GEN-01 |
| R-203 | DAG Requirement | GEN-03 Structure Generator | dependency_graph | validated_dag | VR-G-003 | GEN-03 (self) |
| R-204 | Element Reachability | GEN-03 Structure Generator | chain structure | reachability_report | VR-G-004 | R-201, R-203 |
| R-205 | Trigger Count Limit | GEN-03 Structure Generator | trigger_count | warning_or_pass | VR-G-019 | R-201 |
| R-206 | Condition Must Have If | GEN-03 Structure Generator | condition_element | validated_condition | VR-G-005 | None |
| R-207 | Choice Must Have When | GEN-03 Structure Generator | choice_element | validated_choice | VR-G-006 | None |
| R-208 | Try-Catch Structure | GEN-03 Structure Generator | try_catch_element | validated_error_block | VR-G-007 | None |
| R-209 | Split-Async Minimum | GEN-03 Structure Generator | split_async_element | validated_parallel | VR-G-008 | None |
| R-210 | Circuit Breaker Structure | GEN-03 Structure Generator | circuit_breaker | validated_cb | VR-G-009 | None |
| R-211 | Condition Nesting Limit | GEN-07 Routing Generator | nesting_depth | warning_or_pass | VR-G-015 | R-206 |
| R-212 | Element Count Limit | GEN-03 Structure Generator | element_count | warning_or_pass | VR-G-019 | All generators |
| R-213 | Swimlane Not In Deps | GEN-03 Structure Generator | swimlane_element | validated_org | VR-G-014 | None |
| R-214 | if→condition Parent | GEN-03 Structure Generator | if_element | parent_validation | VR-G-010 | R-206 |
| R-215 | when→choice Parent | GEN-03 Structure Generator | when_element | parent_validation | VR-G-011 | R-207 |
| R-216 | error→tcf2 Parent | GEN-03 Structure Generator | error_elements | parent_validation | VR-G-012 | R-208 |
| R-217 | async→split Parent | GEN-03 Structure Generator | split_elements | parent_validation | VR-G-013 | R-209 |
| R-218 | When Priority Unique | GEN-07 Routing Generator | when_children | priority_validation | VR-G-020 | R-207 |
| **Trigger Rules** | | | | | | |
| R-301 | HTTP ExternalRoute | GEN-02 Trigger Generator | http_trigger | configured_trigger | VR-E-001 | GEN-01 |
| R-302 | External Route RBAC | GEN-14 Security Generator | http_trigger | rbac_config | VR-C-001 | R-301 |
| R-303 | HTTP Trigger Timeout | GEN-02 Trigger Generator | http_trigger | timeout_config | VR-E-002 | None |
| R-304 | AsyncAPI Finally Obligation | GEN-04 Error Handling Gen | chain | error_structure | VR-X-002 | GEN-02 |
| R-305 | Kafka TLS | GEN-14 Security Generator | kafka_element | tls_config | VR-E-007 | None |
| R-306 | Kafka MaaS Connection | GEN-06 Integration Generator | kafka_element | maas_config | VR-E-008 | None |
| **Integration Rules** | | | | | | |
| R-401 | Service-Call M2M v2 | GEN-05 Auth Generator | service_call | auth_config | VR-E-003 | None |
| R-402 | Legacy M2M Detection | GEN-05 Auth Generator | service_call | migration_warning | VR-E-003 | None |
| R-403 | Service-Call After Hook | GEN-06 Integration Generator | service_call | hook_config | VR-E-004 | None |
| R-404 | Retry Count Limit | GEN-09 Retry Generator | retry_config | validated_retry | VR-E-005 | None |
| R-405 | Retry Delay Minimum | GEN-09 Retry Generator | retry_config | validated_retry | VR-E-006 | R-404 |
| R-406 | Kafka Topic MaaS | GEN-06 Integration Generator | kafka_sender | topic_config | VR-E-009 | None |
| R-407 | Chain-Call Static ID | GEN-11 Composition Generator | chain_call | validated_call | VR-E-012 | None |
| R-408 | Chain-Call Timeout | GEN-08 Timeout Generator | chain_call | timeout_config | VR-E-013 | None |
| R-409 | Reuse Ref Validity | GEN-11 Composition Generator | reuse_reference | validated_ref | VR-E-015 | None |
| R-410 | Timeout Hierarchy | GEN-08 Timeout Generator | timeout_values | hierarchy_validation | VR-X-001 | R-303, R-408 |
| **Error Handling Rules** | | | | | | |
| R-501 | HTTP Chain Error Handling | GEN-04 Error Handling Gen | chain | error_structure | VR-P-001 | GEN-02 |
| R-502 | Catch Exception Spec | GEN-04 Error Handling Gen | catch_2 | catch_config | VR-E-010 | R-208 |
| R-503 | Catch Priority Spec | GEN-04 Error Handling Gen | catch_2 | catch_config | VR-E-011 | R-208 |
| R-504 | Error Response Standard | GEN-04 Error Handling Gen | catch_script | error_script | VR-E-010 | R-502, R-503 |
| **Security Rules** | | | | | | |
| R-601 | External Auth Generic.Auth | GEN-05 Auth Generator | chain | auth_routing | VR-C-002 | GEN-06 |
| R-602 | No Hardcoded Credentials | GEN-14 Security Generator | chain_config | security_scan | VR-C-005 | None |
| R-603 | Wildcard Role Prohibition | GEN-14 Security Generator | roles_array | role_validation | VR-C-001 | R-302 |
| R-604 | GDPR Data Masking | GEN-14 Security Generator | chain | masking_config | VR-R-003 | None |
| **Monitoring Rules** | | | | | | |
| R-701 | Session Logging | GEN-15 Monitoring Generator | chain | logging_config | VR-C-003 | None |
| R-702 | DPT Events | GEN-15 Monitoring Generator | chain | dpt_config | VR-C-004 | None |
| R-703 | Context Propagation | GEN-15 Monitoring Generator | service_call | propagation_config | VR-C-006 | GEN-06 |
| R-704 | Complex Chain Checkpoints | GEN-15 Monitoring Generator | element_count | checkpoint_elements | VR-G-019 | R-212 |
| **Naming Rules** | | | | | | |
| R-801 | Chain Name Convention | GEN-16 Naming Generator | chain_name | validated_name | VR-N-001 | None |
| R-802 | No Ticket Numbers | GEN-16 Naming Generator | chain_name | validated_name | VR-N-002 | None |
| R-803 | No Numeric Prefixes | GEN-16 Naming Generator | chain_name | validated_name | VR-N-003 | None |
| **Data Flow Rules** | | | | | | |
| R-901 | Property-Based Data Flow | GEN-10 Data Flow Generator | scripts | data_flow_config | VR-N-004 | None |
| R-902 | Loop Safety Guard | GEN-12 Loop Generator | loop_2 | loop_config | VR-E-014 | None |
| R-903 | Choice Otherwise | GEN-07 Routing Generator | choice | routing_config | VR-X-003 | R-207 |
| R-904 | Valid Camel Expression | GEN-07 Routing Generator | expression | expression_validation | VR-E-016 | None |
| **Pattern Rules** | | | | | | |
| R-1001 | HTTP Must Use GP-01 | GEN-01 Pattern Selection | chain | pattern_selection | VR-P-001 | GEN-02 |
| R-1002 | AsyncAPI Must Use GP-02 | GEN-01 Pattern Selection | chain | pattern_selection | VR-P-002 | GEN-02 |
| R-1003 | Sub-Chain Use GP-03 | GEN-01 Pattern Selection | chain | pattern_selection | VR-P-001 | GEN-02 |
| R-1004 | GDPR Must Use GP-07 | GEN-01 Pattern Selection | chain | pattern_selection | VR-P-003 | None |
| R-1005 | Reuse Threshold | GEN-11 Composition Generator | repeated_sequences | reuse_suggestion | VR-P-004 | All |

---

## Generator Summary

| Generator | Rules Owned | Rule IDs |
|-----------|------------|----------|
| GEN-01 Pattern Selection | 4 | R-1001, R-1002, R-1003, R-1004 |
| GEN-02 Trigger | 2 | R-301, R-303 |
| GEN-03 Structure | 13 | R-201, R-202, R-203, R-204, R-205, R-206, R-207, R-208, R-209, R-210, R-212, R-213, R-214, R-215, R-216, R-217 |
| GEN-04 Error Handling | 5 | R-304, R-501, R-502, R-503, R-504 |
| GEN-05 Auth | 3 | R-401, R-402, R-601 |
| GEN-06 Integration | 3 | R-306, R-403, R-406 |
| GEN-07 Routing | 4 | R-211, R-218, R-903, R-904 |
| GEN-08 Timeout | 2 | R-408, R-410 |
| GEN-09 Retry | 2 | R-404, R-405 |
| GEN-10 Data Flow | 1 | R-901 |
| GEN-11 Composition | 3 | R-407, R-409, R-1005 |
| GEN-12 Loop | 1 | R-902 |
| GEN-13 Parallel | 0 | (uses R-209 owned by GEN-03 for structural validation) |
| GEN-14 Security | 4 | R-302, R-305, R-602, R-603, R-604 |
| GEN-15 Monitoring | 4 | R-701, R-702, R-703, R-704 |
| GEN-16 Naming | 3 | R-801, R-802, R-803 |
| GEN-17 Element Validator | 2 | R-101, R-102 |

**Total:** 48 rules across 17 generators. Every rule has exactly one owner. No rule is shared.

---

## Dependency Graph

```
GEN-01 (Pattern Selection)
  ↓
GEN-02 (Trigger)
  ↓
GEN-03 (Structure)  ← GEN-17 (Element Validator, pre-check)
  ↓
GEN-04 (Error Handling)
  ↓
GEN-05 (Auth)
  ↓
GEN-06 (Integration)
  ↓
GEN-07 (Routing)
  ↓
GEN-08 (Timeout)  ← depends on GEN-02 (trigger timeout) + GEN-06 (service-call timeout)
  ↓
GEN-09 (Retry)
  ↓
GEN-10 (Data Flow)
  ↓
GEN-11 (Composition)
  ↓
GEN-12 (Loop)
  ↓
GEN-13 (Parallel)
  ↓
GEN-14 (Security)  ← depends on GEN-02 (external route) + GEN-06 (Kafka)
  ↓
GEN-15 (Monitoring)  ← depends on GEN-06 (service-calls)
  ↓
GEN-16 (Naming)
  ↓
GEN-17 (Element Validator, final validation)
```
