# File Read
## Description

---
**File Read** element provides ability to access the specific file, stored in predefined pod's temporary storage.

## User Interface

--- 
### "Parameters" Tab
#### Common Parameters
| Parameter                  | Mandatory | Data Type | Description                                                                                     | Sample |
| -------------------------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------- | ------ |
| File Name (mask supported) | M         | String    | File name pattern or its exact name. All files matching the criteria are going to be processed. | *.txt  |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                     |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | -------------------------- |
| Name        | M         | String    | Name of the element.                                       | Element for file reading   |
| Description | O         | String    | Free text field, that contains description of the element. | Read documents from folder |

## Constraints

---
**"File Read"** element is only able to read files from **/tmp/chain_tmp/** folder.
