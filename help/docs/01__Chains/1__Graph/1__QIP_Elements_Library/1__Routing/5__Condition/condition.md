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
| Parameter   | Mandatory | Data Type | Description                                | Sample                      |
| ----------- | :-------- | :-------- | ------------------------------------------ | --------------------------- |
| Name        | M         | String    | Name of the "Condition" container element. | Check Party Role.           |
| Description | O         | String    | Free text field for element description.   | Check if party role exists. |

### "Parameters" Tab (If)
#### Common Parameters
| Parameter | Mandatory | Data Type | Description                                                                                                                          | Sample                                                                                      |
| --------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| Condition | O         | String    | Predicate, that has to be evaluated. If data corresponds to it, then it is routed to the chain part under particular "If" container. | `${exchangeProperty.partyRoleType} != null && ${exchangeProperty.user_customer_id} != null` |
| Priority  | M         | String    | Priority of clauses. "If" block with priority 0 will be processed first.                                                             | 1                                                                                           |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                                                |
| ----------- | :-------- | :-------- | ---------------------------------------- | ----------------------------------------------------- |
| Name        | M         | String    | Name of the "If" container element.      | Party Role Type and User Customer Id exists.          |
| Description | O         | String    | Free text field for element description. | Check if Party Role Type and User Customer Id exists. |

### "Parameters" Tab (Else)
#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                                               |
| ----------- | :-------- | :-------- | ---------------------------------------- | ---------------------------------------------------- |
| Name        | M         | String    | Name of the "Else" container element.    | Send Error Message.                                  |
| Description | O         | String    | Free text field for element description. | Otherwise form error message and send as a response. |

## Constraints

---
There are no specific constraints for the element.
