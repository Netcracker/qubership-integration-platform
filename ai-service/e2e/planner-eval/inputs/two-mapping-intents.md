Binding resolution policy: CATALOG_ONLY
Language version: 2024.4
Mapping owner policy: mapper-2 is disabled; use cip-script-generator, not
cip-transformation-generator.

Create an HTTP POST `/orders` flow using `Orders Service.createOrder`. Before the call, map the
request with `mappingIntentId=request-map`. After the call, map the response with
`mappingIntentId=response-map`. Both transformations must remain separate plan steps.
