# File Read
## Description

---
**File Read** element provides ability to access the specific file, stored in predefined pod's temporary storage.

## User Interface

--- 
### "Parameters" Tab
#### Common Parameters
| Parameter                                                 | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                    | Sample                               |
| --------------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------ |
| <div style="width:150px">File Name (mask supported)</div> | M                                       | String                                  | <div style="width:400px">File name pattern or its exact name. All files matching the criteria are going to be processed.</div> | <div style="width:350px">*.txt</div> |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                                               | Sample                                                    |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|-------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                   | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">Element for file reading</div>   |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">Read documents from folder</div> |

## Constraints

---
**"File Read"** element is only able to read files from **/tmp/chain_tmp/** folder.