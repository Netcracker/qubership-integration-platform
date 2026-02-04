# Condition
## Description

---
**Condition** element provides an ability to route messages to the specific destination based on the contents of the message exchanges.

Current element supports 2 sub-elements:

- **If** - sets the "If" clause. If condition, specified in this sub-element is true, related chain flow will be routed to the part, that is placed inside the "If" container. Multiple "If" conditions are possible, hence this sub-element can be added multiple times.
- **Else** - sets the otherwise/else condition. If nothing from "If" clauses is true, route under "Else" condition will be performed. This condition can be specified only once, but also could be absent.

## User Interface

---
### "Parameters" Tab (Condition)
#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                               | Sample                                                     |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------- | ---------------------------------------------------------- |
| <div style="width:150px">Name</div>        | M                                       | String                                  | <div style="width:400px">Name of the "Condition" container element.</div> | <div style="width:350px">Check Party Role.</div>           |
| <div style="width:150px">Description</div> | O                                       | String                                  | <div style="width:400px">Free text field for element description.</div>   | <div style="width:350px">Check if party role exists.</div> |

### "Parameters" Tab (If)
#### Common Parameters
| Parameter                                | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                          | Sample                                                                                                                                |
|------------------------------------------|:----------------------------------------|:----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| <div style="width:150px">Condition</div> | O                                       | String                                  | <div style="width:400px">Predicate, that has to be evaluated. If data corresponds to it, then it is routed to the chain part under particular "If" container.</div>  | <div style="width:350px"><code>${exchangeProperty.partyRoleType} != null && ${exchangeProperty.user_customer_id} != null</code></div> |
| <div style="width:150px">Priority</div>  | M                                       | String                                  | <div style="width:400px">Priority of clauses. "If" block with priority 0 will be processed first. </div>                                                             | <div style="width:350px">1</div>                                                                                                      |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                             | Sample                                                                               |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|-------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                   | <div style="width:400px">Name of the "If" container element.</div>      | <div style="width:350px">Party Role Type and User Customer Id exists.</div>          |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field for element description.</div> | <div style="width:350px">Check if Party Role Type and User Customer Id exists.</div> |

### "Parameters" Tab (Else)
#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div> | Description                                                             | Sample                                                                               |
|--------------------------------------------|:-----------------------------------------|:----------------------------------------|-------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                  | <div style="width:400px">Name of the "Else" container element.</div>    | <div style="width:350px">Send Error Message.</div>                                   |
| <div style="width:150px">Description</div> | O                                        | String                                  | <div style="width:400px">Free text field for element description.</div> | <div style="width:350px">Otherwise form error message and send as a response.</div>  |

## Constraints

---
There are no specific constraints for the element.
