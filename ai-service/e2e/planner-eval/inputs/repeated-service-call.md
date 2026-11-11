Binding resolution policy: CATALOG_ONLY
Language version: 2024.4

Create an HTTP GET `/orders/{id}/compare` flow. Invoke the existing catalog operation
`Orders Service.getOrder` once for the current tenant and once for the reference tenant. Preserve
the two separate service-call elements even though both use the same operation.
