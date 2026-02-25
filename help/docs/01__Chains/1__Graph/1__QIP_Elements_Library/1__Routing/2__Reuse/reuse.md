# Reuse
## Description

---
**Reuse** is a container-type element, that can hold a "repeatable" part of the chain logic, which would be available for reuse by other chain parts via [Reuse Reference](../7__Reuse_Reference/reuse_reference.md) element. Simply configure a chain part within Reuse container, that is designed to be referred multiple times and then call it across the main chain when required. This might be helpful for building Error Handling or Validation logics.


## User Interface

---
### "Parameters" Tab
#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                     |
| ----------- | :-------- | :-------- | ---------------------------------------- | -------------------------- |
| Name        | M         | String    | Name of the element.                     | Reuse                      |
| Description | O         | String    | Free text field for element description. | Error Handling logic reuse |

## Constraints

---
There are no specific constraints for the element.
