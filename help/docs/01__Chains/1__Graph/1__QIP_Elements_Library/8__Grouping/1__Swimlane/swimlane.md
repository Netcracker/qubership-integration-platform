# Swimlane
## Description

---
**Swimlane** element provides ability to logically group chain parts into colored "blocks" that help visually distinguish different part of the chain. When **Swimlane** is added, it will automatically capture the whole chain. If **Reuse** elements are found - they will always be captured in a separate Swimlane.

## User Interface

---
### "Parameters" Tab
#### Common Parameters

There is a few color options available via clicking the respective palette element.

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                 |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ---------------------- |
| Name        | M         | String    | Name of the element.                                       | Group_1                |
| Description | O         | String    | Free text field, that contains description of the element. | External service calls |

## Constraints

---
Please consider next constraints:
- If the elements are already in container or Swimlane, it is not possible to place such elements in the another Swimlane.
- Swimlane can't be part of another Swimlane.
