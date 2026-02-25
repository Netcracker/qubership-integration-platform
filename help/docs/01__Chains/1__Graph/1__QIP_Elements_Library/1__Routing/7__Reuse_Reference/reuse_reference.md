# Reuse Reference
## Description

---
**Reference** element allows to connect the elements with a reusable chain flow, that is configured within [Reuse](../2__Reuse/reuse.md) container to avoid doubling the logic.


## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter       | Mandatory | Data Type | Description                                                                 | Sample |
| --------------- | :-------- | :-------- | --------------------------------------------------------------------------- | ------ |
| Reuse Reference | M         | List      | Specifies the [Reuse](../2__Reuse/reuse.md) container to be connected with. | N/A    |

### "Parameters" Tab
#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample            |
| ----------- | :-------- | :-------- | ---------------------------------------- | ----------------- |
| Name        | M         | String    | Name of the element.                     | Reference         |
| Description | O         | String    | Free text field for element description. | Validate response |

## Constraints

---
Please consider next constraints:
- Reference can be established only with the reuse containers, that are configured within the current chain.
- Only a single reuse container could be referenced at once.
