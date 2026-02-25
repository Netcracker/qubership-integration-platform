# File Write
## Description

---
**File Write** element provides ability to write data to the file in pod's temporary storage.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter | Mandatory | Data Type | Description                                     | Sample  |
| --------- | :-------- | :-------- | ----------------------------------------------- | ------- |
| File Name | M         | String    | Target file name where data will be written to. | NewFile |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                      |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | --------------------------- |
| Name        | M         | String    | Name of the element.                                       | Element for file writing    |
| Description | O         | String    | Free text field, that contains description of the element. | Write documents to a folder |

## Constraints

---
**"File Write"** element is only able to write files to **/tmp/chain_tmp/** folder.
