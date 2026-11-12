1. Analyze requirements and name the chain for the HTTP POST `/orders/replay` flow (cip-requirement-analyzer + cip-naming-generator)
2. Generate HTTP Trigger element with interface `POST /orders/replay` (cip-trigger-generator)
3. Generate Script element for request mapping with `mappingIntentId=map-request` (cip-script-generator)
4. Configure the first existing `Orders Service.createOrder` binding (cip-service-call-generator)
5. Generate Script element between calls with `mappingIntentId=map-between` (cip-script-generator)
6. Configure the second existing `Orders Service.createOrder` binding (cip-service-call-generator)
7. Generate Script element for response mapping with `mappingIntentId=map-response` (cip-script-generator)
8. Generate execution structure and element ordering (cip-structure-generator)
9. Assemble `generated-chain.cip.yaml` + scripts (cip-chain-assembler)
10. Validate the assembled chain (cip-chain-validator)

If you agree, reply **Agree** or **Execute plan** to proceed.
