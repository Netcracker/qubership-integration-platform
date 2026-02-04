# Chain Availability Setup (Access Control)
## Description

---
Following the security practices and guidelines, Qubership Integration Platform has introduced an ability to integrate with **Access Control Microservice** in order to control the access to the endpoints, configured within [Chains](docs/01__Chains/chains.md). When mentioned functionality is properly utilized, for each chain request Qubership Integration Platform performs a policy check and ensures, that calling system/user is actually allowed to trigger requested endpoint and start the logic, configured within related chain. 

Please refer to the diagram below, that visually represents the flow:

![[access_control.svg]]

**Diagram details**

| #   | Description                                                                                                 |
| --- | ----------------------------------------------------------------------------------------------------------- |
| 1   | <div style="width:788px">User or system calls specific endpoint, exposed by the QIP chain.</div>            |
| 2   | <div style="width:788px">Engine identifies the user from the token in request.</div>                        |
| 3   | <div style="width:788px">Engine verifies access, utilizing cached data.</div>                               |
| 4   | <div style="width:788px">If access granted, Qubership Integration Platform allows to trigger a chain.</div> |

User will get **403 Forbidden** error if it has no access to the called endpoint. 

**Custom Policies**

All sequentially added roles for each particular endpoint shall be specified manually. Please view configuration example below:

| Policy Name                                               | Permission:<br/>Resource                                   | <div style="width:90px">Permission:<br/>Resource Type</div> | <div style="width:75px">Permission:<br/>Operation</div> | <div style="width:75px">Permission:<br/>Condition</div> | <div style="width:75px">Role #1</div> | <div style="width:75px">Role #2</div> |
| --------------------------------------------------------- | ---------------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------- | ------------------------------------------------------- | ------------------------------------- | ------------------------------------- |
| <div style="width:150px">Trigger Chain via Endpoint</div> | <div style="width:150px">/qip-routes/chain/checkData</div> | QIP-CHAIN                                                   | ALL                                                     | -                                                       | Allow                                 | Allow                                 |
| <div style="width:150px">Trigger Chain via Endpoint</div> | <div style="width:150px">/chain/checkData</div>            | QIP-CHAIN                                                   | RETRIEVE                                                | -                                                       | Deny                                  | Allow                                 |
| <div style="width:150px">Trigger Chain via Endpoint</div> |                                                            |                                                             | /order/{orderId}/submit                                 | Order                                                   | SUBMIT                                | Deny                                  |

Where Resource, Resource Type, Operation are values, configured on [HTTP Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) within a chain.

## User Interface

---
To make **Qubership Integration Platform** validating called resource against policies, stored in **Access Control**, it is required to select **"ABAC"** option and populate corresponding fields that identifies the resource for [HTTP Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) (Parameters tab). Policies shall be manually configured directly in Access Control.
