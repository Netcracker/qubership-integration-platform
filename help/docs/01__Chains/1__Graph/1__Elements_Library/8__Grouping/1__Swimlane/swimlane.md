# Swimlane
## Description

---
**Swimlane** element provides ability to logically group chain parts into colored "blocks" that help visually distinguish different part of the chain. When **Swimlane** is added firstly, it will automatically capture the whole chain and be marked with label "Default". If **Reuse** elements are found - they will always be captured in a separate Swimlane and this Swimlane will have label "Reuse".

## User Interface

---
### "Parameters" Tab
| Parameter   | Mandatory | Data Type | Description                                                | Sample                 |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ---------------------- |
| Name        | M         | String    | Name of the element.                                       | Group_1                |
| Description | O         | String    | Free text field, that contains description of the element. | External service calls |

## Constraints

---
Please consider next constraints:
- Swimlane can't be part of another Swimlane.
- Default and Reuse swimlanes cannot be removed if the chain contains other swimlanes
