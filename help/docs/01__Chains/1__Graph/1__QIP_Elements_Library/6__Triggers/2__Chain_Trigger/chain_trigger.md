# Chain Trigger
## Description

---
**Chain Trigger** is an element, that is used in pair with [Chain Call](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md) and provides the ability to build complex chain routes, where main chain must invoke other sub chains in order to properly process the data and fulfill required business scenario. This element must be placed as a first element in the sub-chain, so the main chain can be linked to it via Chain Call element and can pass the data.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">According to <b>Apache Camel</b> framework, when chain is triggered, system creates <b>Exchange Object</b>, that handles input data following the logic, described in respective article: <a href="docs/00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md">Apache Camel Context Concept</a>.</div>

## User Interface

---
### "Parameters" Tab
#### Common Parameters

| Parameter                                         | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                                                                                                            | Sample                                         |
| ------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------- |
| <div style="width:150px">Used by \<N> items</div> | M                                       | List                                    | <div style="width:400px">Read-only clickable parameter, that shows the number of [Chain Calls](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md), linked to this trigger. When clicked, it expands the list with the names of all linked chain calls, that might be clicked to open the respective chain.</div> | <div style="width:350px">Used by 2 items</div> |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                               | Sample                                                                                                       |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| <div style="width:150px">Name</div>        | M                                       | String                                  | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">Chain Trigger</div>                                                                 |
| <div style="width:150px">Description</div> | O                                       | String                                  | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">This trigger is an entry point for the chain to create a new customer account</div> |

## Constraints

---
Can't set input dependency to element.