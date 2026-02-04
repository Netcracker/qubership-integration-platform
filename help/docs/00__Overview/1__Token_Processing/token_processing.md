# Token Processing
## Description

---
Token mechanism allows secured communications between services and mitigates the risk of unrestricted access to the data. Each token that comes with the authentication request from service is going to be verified on two levels:

- **Engine Level:** on this level token is being validated via internal core libraries. This validation will ensure that token is correct and request is trusted. Only if authentication is successful, request is able to reach the chain (specifically the trigger element).
- **Trigger Level:** on this level tokenized request is being validated against the specified list of allowed roles (if any). This list could be filled under trigger's parameter tab.

In addition, Qubership Integration Platform provides the ability to pass the token as part of outgoing requests. It is supported via option "**Enable M2M Security**" under the parameters tab for **HTTP Sender** element and "**M2M Token**" authorization option for **Service Call** element . If mentioned options selected, then M2M token will be set to the **Authorization** header for outgoing request. Before making a call with M2M token, system will preserve "current" value of Authorization header and reinstate it back after receiving the response (utilized M2M token itself will be completely erased from the system to avoid its leakage). Please see the diagram below for the high-level process understanding.

![[Token_Processing_1.svg]]

**Diagram Description**

Diagram above shows common processing scheme, where token,
passed by Service A goes to the Engine (where it is going to be validated against the core libraries),
then token is going to be received by Trigger. Trigger might have set of roles configured for additional validation (where for HTTP Trigger the system requires that at least one role must be defined if role-based access control is opted). Service Call represents the element that is able to trigger the outbound call, if authorization option "**M2M Token**" is selected, then QIP Engine will put the M2M token to the **Authorization** header when calling Service B.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
Currently there are only three elements, that utilize mentioned security aspect, due to mechanism specifics:
<li><a
href="docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md">HTTP Trigger</a></li>
<li><a href="doc/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/4__HTTP_Sender/http_sender.md">HTTP Sender</a></li>
<li><a href= "docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md">Service Call</a></li>
</div>

Please review the diagram below for detailed and sequential steps to understand how Qubership Integration Platform operates with the M2M token.

![[Token_Processing_2.svg]]

**Diagram Details**

| #   | Step                                            | Description                                                                                                                                                                                                                                                   |
| --- | :---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Login with credentials                          | During initial system launch all clients get authorization credentials as clientId, clientSecret. To get M2M token, Domain A have to go through authorization procedure by it's credential in Identity Provider (IDP).                                        |
| 2   | Response with M2M token                         | Identity Provider (IDP) will respond with M2M Token.                                                                                                                                                                                                          |
| 3   | Request with M2M token                          | When client has M2M token, it forms a request to Engine. Token will be incorporated to the authorization header.                                                                                                                                              |
| 4   | Token validation                                | Engine microservice has to validate the token. According to validation results there are two option of Engine behavior: <ul><li>Go through the process (step 5)</li><li>Respond with access error (step 12).</li></ul>                                        |
| 5   | Get M2M token to call Domain B                  | In case of Domain B has enabled M2M security, Engine request another M2M token from Identity Provider. Authorization is done the same way as in step #1.                                                                                                      |
| 6   | Response with M2M token                         | Identity Provider will respond with M2M Token.                                                                                                                                                                                                                |
| 7   | Preserve current Authorization header's value   | In order to keep the original Authorization header's value, system preserves it before sending a request with M2M token.                                                                                                                                      |
| 8   | Request with M2M token to Domain B              | Engine sends a request with M2M token.                                                                                                                                                                                                                        |
| 9   | Response from Domain B                          | If authorization process was successful, Domain B performs operation and sends a response to the Engine                                                                                                                                                       |
| 10  | Reinstate original Authorization header's value | System reinstates the original value of Authorization header. M2M token, participated in the process is being removed. Pair of step 7 and step 10 ensures that M2M token won't be reused or exposed as the result of sequential service calls within a chain. |
| 11  | Response from Engine module to Domain A         | Engine sends a response to Domain A.                                                                                                                                                                                                                          |
| 12  | Response with access error                      | In case of failed token validation, Domain A get response with error description.                                                                                                                                                                             |
## User Interface

---
To enable sending **M2M token**, it is required to set option **"Enable M2M Security"** for [[docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/4__HTTP_Sender/http_sender|HTTP Sender]] element or select **"M2M Token"** authorization option for [[docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call|Service Call]] element. Please, refer to the respective articles for more details.