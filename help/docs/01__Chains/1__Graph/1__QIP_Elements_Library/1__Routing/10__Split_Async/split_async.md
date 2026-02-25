# Split Async
## Description

---
**Split Async** allows to route message to one or more separate **asynchronous flows** without result aggregation. Each Async Split Element starts parallel chain flow asynchronously.

## User Interface

---
### "Parameters" Tab (Split Async)
#### Metadata
| Parameter   | Mandatory | Data Type | Description                                | Sample                                             |
| ----------- | :-------- | :-------- | ------------------------------------------ | -------------------------------------------------- |
| Name        | M         | String    | Name of the Split Async container element. | Container of async branches                        |
| Description | O         | String    | Free text field for element description.   | Split Async container with multiple split branches |

### "Parameters" Tab (Async Split Element)
#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                       |
| ----------- | :-------- | :-------- | ---------------------------------------- | ---------------------------- |
| Name        | M         | String    | Name of the Split Async element.         | Split Async branch           |
| Description | O         | String    | Free text field for element description. | Async flow for notifications |

## Constraints

---
**Async Split Element** should be used at least once in **Split Async** module.
