# SFTP Download
## Description

---
**SFTP download** element provides ability to fetch files with specific names from target SFTP directory and preserve them to **Exchange Object's** body.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter           | Mandatory | Data Type | Description                                                                                       | Sample                 |
| ------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------- | ---------------------- |
| Host:Port/Directory | M         | String    | Specifies **Hostname** of the (S)FTP server, **port** of the (S)FTP server and the **directory**. | 10.109.27.10:22/source |

#### Advanced Parameters
| Parameter                               | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                          | Sample                                     |
| --------------------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------ |
| File Name (mask supported)              | O         | String    | File name pattern or its exact name. All files matching the criteria are going to be downloaded.                                                                                                                                                                                                                                                                                     | *.txt                                      |
| Username                                | O         | String    | Username value, required for authenticated access.                                                                                                                                                                                                                                                                                                                                   | #{username}                                |
| Password                                | O         | String    | Password value, required for authenticated access.                                                                                                                                                                                                                                                                                                                                   | #{password}                                |
| Do not download files with same filekey | M         | Boolean   | Checkbox, that disables sequential downloading of the same files. Unchecked by default.                                                                                                                                                                                                                                                                                              | N/A                                        |
| Filekey string                          | O         | String    | Combination of file properties that uniquely identifies the exact file. If properties value of this combination has been changed - element will consider this as appearance of a new file, hence it will start processing. When left empty, then  the absolute path of the file will be considered as a file key.<br> **Default Values:** ${file:name}-${file:size}-${file:modified} | ${file:name}-${file:size}-${file:modified} |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                        |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | --------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | SFTP download                                 |
| Description | O         | String    | Free text field, that contains description of the element. | SFTP to load temp data from remote ftp server |

## Constraints

---
There are no specific constraints for the element.
