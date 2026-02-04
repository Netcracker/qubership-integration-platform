# SFTP Upload
## Description

---

**SFTP Upload** element provides the ability to write **Exchange Object's** current body to the file with custom name and upload it to the specific SFTP directory.

## User Interface

---
### "Parameters" Tab

#### Common Parameters

| Parameter                                          | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                      | Sample                                                |
| -------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| <div style="width:150px">Host:Port/Directory</div> | M                                       | String                                  | <div style="width:400px">Specifies **Hostname** of the (S)FTP server, **port** of the (S)FTP server, and the **directory**</div> | <div style="width:350px">10.109.27.10:22/source</div> |

#### Advanced Parameters
| Parameter                                                 | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                | Sample                                     |
| --------------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------ |
| <div style="width:150px">File Name (mask supported)</div> | O                                       | String                                  | <div style="width:400px">Name of the file which is going to be uploaded.</div>                             | <div style="width:350px">fileName</div>    |
| <div style="width:150px">Create directory to file</div>   | O                                       | Boolean                                 | <div style="width:400px">Checkbox, that enables ability to create missing directories automatically.</div> | <div style="width:350px">N/A</div>         |
| <div style="width:150px">Username</div>                   | O                                       | String                                  | <div style="width:400px">Username value, required for authenticated access.</div>                          | <div style="width:350px">#{username}</div> |
| <div style="width:150px">Password</div>                   | O                                       | String                                  | <div style="width:400px">Password value, required for authenticated access. </div>                         | <div style="width:350px">#{password}</div> |


#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>   | <div style="width:75px">Data Type</div>  | Description                                                                               | Sample                                                                        |
|--------------------------------------------|:------------------------------------------|:-----------------------------------------|-------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                         | String                                   | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">SFTP upload</div>                                    |
| <div style="width:150px">Description</div> | O                                         | String                                   | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">Upload documents to specific remote ftp server</div> |

## Constraints

---
There are no specific constraints for the element.
