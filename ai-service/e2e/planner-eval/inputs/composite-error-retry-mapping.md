Binding resolution policy: CATALOG_ONLY
Language version: 2024.4
Mapping owner policy: mapper-2 is disabled; use cip-script-generator, not
cip-transformation-generator.

Create an HTTP POST `/payments` flow using `Payments Service.authorizePayment`. Map the request
with `mappingIntentId=payment-map`, retry the service call up to three attempts, and place the
mapping and service call in `try-2` with an error script in `catch-2`.
