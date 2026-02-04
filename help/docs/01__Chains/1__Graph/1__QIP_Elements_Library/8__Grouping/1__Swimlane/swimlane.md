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
| Parameter                                   | <div style="width:75px">Mandatory</div>    | <div style="width:75px">Data Type</div>   | Description                                                                               | Sample                                                |
|---------------------------------------------|:-------------------------------------------|:------------------------------------------|-------------------------------------------------------------------------------------------|-------------------------------------------------------|
| <div style="width:150px">Name</div>         | M                                          | String                                    | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">Group_1</div>                |
| <div style="width:150px">Description</div>  | O                                          | String                                    | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">External service calls</div> |

## Constraints

---
Please consider next constraints:
- If the elements are already in container or Swimlane, it is not possible to place such elements in the another Swimlane.
- Swimlane can't be part of another Swimlane.