# File Write
## Description

---
**File Write** element provides ability to write data to the file in pod's temporary storage.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter                                | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                    | Sample                                 |
| ---------------------------------------- | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------ | -------------------------------------- |
| <div style="width:150px">File Name</div> | M                                       | String                                  | <div style="width:400px">Target file name where data will be written to.</div> | <div style="width:350px">NewFile</div> |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                                               | Sample                                                       |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|-------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                   | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">Element for file writing</div>      |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">Write documents to a folder</div>   |

## Constraints

---
**"File Write"** element is only able to write files to **/tmp/chain_tmp/** folder.