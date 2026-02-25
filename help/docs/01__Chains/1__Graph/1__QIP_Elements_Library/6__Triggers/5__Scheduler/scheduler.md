# Scheduler
## Description

---
**Scheduler Trigger** element allows to set the chain start cycle by specifying proper Cron Expression.

>ℹ️**Note:** According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md).

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter       | Mandatory | Data Type | Description                                       | Sample         |
| --------------- | :-------- | :-------- | ------------------------------------------------- | -------------- |
| Cron expression | M         | String    | Specifies a time interval via Quartz cron syntax. | 0/50 * * * * ? |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                         |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ---------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | Scheduler                                      |
| Description | O         | String    | Free text field, that contains description of the element. | Scheduler is used for planned order submission |

## Constraints

---
Can't set input dependency to element.
