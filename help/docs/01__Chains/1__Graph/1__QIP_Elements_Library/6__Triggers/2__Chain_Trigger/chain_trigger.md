# Chain Trigger
## Description

---
**Chain Trigger** is an element, that is used in pair with [Chain Call](../../1__Routing/6__Chain_Call/chain_call.md) and provides the ability to build complex chain routes, where main chain must invoke other sub chains in order to properly process the data and fulfill required business scenario. This element must be placed as a first element in the sub-chain, so the main chain can be linked to it via Chain Call element and can pass the data.

>**ℹ️Note**: According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md).
## User Interface

---
### "Parameters" Tab
#### Common Parameters

| Parameter          | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                  | Sample          |
| ------------------ | :-------- | :-------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| Used by \<N> items | M         | List      | Read-only clickable parameter, that shows the number of [Chain Calls](../../1__Routing/6__Chain_Call/chain_call.md), linked to this trigger. When clicked, it expands the list with the names of all linked chain calls, that might be clicked to open the respective chain. | Used by 2 items |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                                                        |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ----------------------------------------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | Chain Trigger                                                                 |
| Description | O         | String    | Free text field, that contains description of the element. | This trigger is an entry point for the chain to create a new customer account |

## Constraints

---
Can't set input dependency to element.
