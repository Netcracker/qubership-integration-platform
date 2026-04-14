# SFTP Upload
## Description

---

**SFTP Upload** element provides the ability to write **Exchange Object's** current body to the file with custom name and upload it to the specific SFTP directory.

## User Interface

---
### "Parameters" Tab

#### Common Parameters

| Parameter           | Mandatory | Data Type | Description                                                                                       | Sample                 |
| ------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------- | ---------------------- |
| Host:Port/Directory | M         | String    | Specifies **Hostname** of the (S)FTP server, **port** of the (S)FTP server, and the **directory** | 10.109.27.10:22/source |

#### Advanced Parameters
| Parameter                  | Mandatory | Data Type | Description                                                                 | Sample      |
| -------------------------- | :-------- | :-------- | --------------------------------------------------------------------------- | ----------- |
| File Name (mask supported) | O         | String    | Name of the file which is going to be uploaded.                             | fileName    |
| Create directory to file   | O         | Boolean   | Checkbox, that enables ability to create missing directories automatically. | N/A         |
| Username                   | O         | String    | Username value, required for authenticated access.                          | #{username} |
| Password                   | O         | String    | Password value, required for authenticated access.                          | #{password} |


#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                         |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ---------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | SFTP upload                                    |
| Description | O         | String    | Free text field, that contains description of the element. | Upload documents to specific remote ftp server |

## Constraints

---
There are no specific constraints for the element.
