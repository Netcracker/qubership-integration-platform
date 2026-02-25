# Chain Availability Setup (Access Control)
## Description

---
Following the security practices and guidelines, Qubership Integration Platform has introduced an ability to integrate with **Access Control Microservice** in order to control the access to the endpoints, configured within [Chains](../../01__Chains/chains.md). When mentioned functionality is properly utilized, for each chain request Qubership Integration Platform performs a policy check and ensures, that calling system/user is actually allowed to trigger requested endpoint and start the logic, configured within related chain. 

Please refer to the diagram below, that visually represents the flow:

![](img/access_control.svg)

**Diagram details**

| #   | Description                                                                  |
| --- | ---------------------------------------------------------------------------- |
| 1   | User or system calls specific endpoint, exposed by the QIP chain.            |
| 2   | Engine identifies the user from the token in request.                        |
| 3   | Engine verifies access, utilizing cached data.                               |
| 4   | If access granted, Qubership Integration Platform allows to trigger a chain. |

User will get **403 Forbidden** error if it has no access to the called endpoint. 

**Custom Policies**

All sequentially added roles for each particular endpoint shall be specified manually. Please view configuration example below:

| Policy Name                | Permission:<br>Resource     | Permission:<br>Resource Type | Permission:<br>Operation | Permission:<br>Condition | Role #1 | Role #2 |
| -------------------------- | --------------------------- | ---------------------------- | ------------------------ | ------------------------ | ------- | ------- |
| Trigger Chain via Endpoint | /qip-routes/chain/checkData | QIP-CHAIN                    | ALL                      | -                        | Allow   | Allow   |
| Trigger Chain via Endpoint | /chain/checkData            | QIP-CHAIN                    | RETRIEVE                 | -                        | Deny    | Allow   |
| Trigger Chain via Endpoint |                             |                              | /order/{orderId}/submit  | Order                    | SUBMIT  | Deny    |

Where Resource, Resource Type, Operation are values, configured on [HTTP Trigger](../../01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) within a chain.

## User Interface

---
To make **Qubership Integration Platform** validating called resource against policies, stored in **Access Control**, it is required to select **"ABAC"** option and populate corresponding fields that identifies the resource for [HTTP Trigger](../../01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) (Parameters tab). Policies shall be manually configured directly in Access Control.
