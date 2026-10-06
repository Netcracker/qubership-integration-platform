# Features

## Description

---
Platform mechanisms that work underneath the chains you design, and the parameters that control them. Each page here
describes one mechanism: what the platform does on its own, and when it does it. Where a mechanism is tunable, the
page names the Consul or environment parameters that change the outcome.

A page belongs in this section when it documents behavior the platform performs without a screen to drive it. Anything
with its own UI area is documented in the section for that area — [Chains](../01__Chains/chains.md),
[Services](../02__Services/services.md), [Admin Tools](../03__Admin_Tools/admin_tools.md), or
[Dev Tools](../04__Dev_Tools/dev_tools.md). A repeatable task with a beginning and an end belongs in
[How To](../05__How_To/how_to.md).

The line against [Admin Tools](../03__Admin_Tools/admin_tools.md) is worth drawing: that section documents a tab
inside the product, used by administrators through the UI, while this section describes what the platform behind that
tab does on its own.

## Topics

---

- [System Properties](1__System_Properties/system_properties.md) - the exchange properties the platform calculates at
  runtime and exposes to chains, including blue-green state and the idempotency key parts.
- [Token Processing](2__Token_Processing/token_processing.md) - how authentication tokens are validated on the engine
  and on the trigger, and how a token is passed on to outgoing requests.
- [Retention Settings](3__Retention_Settings/retention_settings.md) - cleanup parameters for sessions, action logs,
  checkpoints, snapshots, context records, and idempotency records.
- [Database Multitenancy](4__Database_Multitenancy/database_multitenancy.md) - tenant isolation on the database level,
  and how each trigger resolves the tenant of an incoming request.
