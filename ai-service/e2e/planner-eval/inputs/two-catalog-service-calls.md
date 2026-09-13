Binding resolution policy: CATALOG_ONLY
Language version: 2024.4

Create an HTTP POST `/orders` flow. Call the existing catalog operation
`Orders Service.createOrder`, then call `Inventory Service.reserveInventory`. Generate a distinct
service-call step for each operation and connect them in that order.
