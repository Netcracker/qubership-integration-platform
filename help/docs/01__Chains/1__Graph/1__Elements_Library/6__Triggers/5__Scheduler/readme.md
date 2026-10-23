# Scheduler
## Description

---
**Scheduler Trigger** element allows to set the chain start cycle by specifying proper Cron Expression.

> ℹ️ **Note:** According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/1__Apache_Camel_Context_Concept/readme.md).

## User Interface

---
### "Parameters" Tab
| Parameter       | Mandatory | Data Type | Description                                                | Sample                                         |
|-----------------|:----------|:----------|------------------------------------------------------------|------------------------------------------------|
| Cron expression | M         | String    | Specifies a time interval via Quartz cron syntax.          | `0/50 ** ** ?`                                 |
| Name            | M         | String    | Name of the element.                                       | Scheduler                                      |
| Description     | O         | String    | Free text field, that contains description of the element. | Scheduler is used for planned order submission |

## Constraints

---
Can't set input dependency to element.

On a micro domain, a scheduled chain runs only on pods whose blue-green state is `ACTIVE`. During a blue-green update, the candidate version fires nothing until it becomes `ACTIVE`, and the previous version stops firing when it leaves `ACTIVE`.
