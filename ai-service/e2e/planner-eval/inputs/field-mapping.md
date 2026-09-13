Binding resolution policy: CATALOG_ONLY
Language version: 2024.4
Mapping owner policy: mapper-2 is disabled; use cip-script-generator, not
cip-transformation-generator.

Create an HTTP POST `/orders` flow using the existing `Orders Service.createOrder` binding. Map
`request.customerId` to `order.customer.id` before the service call. The mapping intent identifier
is `order-map`.
