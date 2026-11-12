1. Analyze requirements and name chain for the HTTP POST `/orders` flow using `Orders Service.createOrder` (cip-requirement-analyzer + cip-naming-generator)
2. Generate HTTP Trigger element with interface `POST /orders` (cip-trigger-generator)
3. Generate Script element for request mapping with `mappingIntentId=request-map` (cip-script-generator)
4. Configure the existing `Orders Service.createOrder` binding (cip-service-call-generator)
5. Generate Script element for response mapping with `mappingIntentId=response-map` (cip-script-generator)
6. Generate execution structure and element ordering (cip-structure-generator)
7. Connect HTTP Trigger → request-mapping Script in the execution structure (cip-structure-generator)
8. Connect request-mapping Script → `Orders Service.createOrder` Service Call in the execution structure (cip-structure-generator)
9. Connect `Orders Service.createOrder` Service Call → response-mapping Script in the execution structure (cip-structure-generator)
10. Assemble `generated-chain.cip.yaml` + scripts (cip-chain-assembler)
11. Validate the assembled chain (cip-chain-validator)

If you agree, reply **Agree** or **Execute plan** to proceed.
