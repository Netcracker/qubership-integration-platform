# SFTP Trigger
## Description

---
This element allows to trigger the chain by periodically fetching the file(s) from **FTP** server.

>ℹ️**Note:** According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md).

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter                          | Mandatory | Data Type | Description                                                                                                                                      | Sample                       |
| ---------------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------- |
| Host:Port/Directory                | M         | String    | Specifies Hostname of the (S)FTP server, port of the (S)FTP server and the directory, where files with specified mask or name must be processed. | sftp:host:port/directoryName |
| Polling schedule (cron expression) | M         | String    | Specifies a time interval via **Unix cron** syntax. With this interval the system will be polling new messages from the specified directory.     | 0/10+*+*+*+*+?               |

#### Advanced Parameters

| Parameter                               | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                        | Sample                                     |
| --------------------------------------- | :-------- | :-------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------ |
| File Name (mask supported)              | O         | String    | File name pattern or its exact name. All files matching the criteria are going to be downloaded.                                                                                                                                                                                                                                                                                   | *.txt                                      |
| Do not download files with same filekey | O         | Boolean   | Checkbox that disables sequential downloading of the same files. Unchecked by default.                                                                                                                                                                                                                                                                                             | N/A                                        |
| Filekey string                          | O         | String    | Combination of file properties that uniquely identifies the exact file. If properties value of this combination has been changed - element will consider this as appearance of a new file, hence it will start processing. When left empty, then  the absolute path of the file will be considered as a file key.<br>**Default value:** ${file:name}-${file:size}-${file:modified} | ${file:name}-${file:size}-${file:modified} |
| Username                                | O         | String    | Username value, required for authenticated access.                                                                                                                                                                                                                                                                                                                                 | N/A                                        |
| Password                                | O         | String    | Password value, required for authenticated access.                                                                                                                                                                                                                                                                                                                                 | N/A                                        |


#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                                    |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | --------------------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | SFTP Trigger                                              |
| Description | O         | String    | Free text field, that contains description of the element. | Triggers the chain when new file appears in the directory |

## Constraints

---
Can't set input dependency to element.
