# Scheduler
## Description

---
**Scheduler Trigger** element allows to set the chain start cycle by specifying proper Cron Expression.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">According to <b>Apache Camel</b> framework, when chain is triggered, system creates <b>Exchange Object</b>, that handles input data following the logic, described in respective article: <a href="doc/00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md">Apache Camel Context Concept</a>.</div>

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter                                      | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                       | Sample                                        |
| ---------------------------------------------- | :-------------------------------------- | :-------------------------------------- | --------------------------------------------------------------------------------- | --------------------------------------------- |
| <div style="width:150px">Cron expression</div> | M                                       | String                                  | <div style="width:400px">Specifies a time interval via Quartz cron syntax. </div> | <div style="width:350px">0/50 * * * * ?</div> |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                               | Sample                                                                       |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| <div style="width:150px">Name</div>        | M                                       | String                                  | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">Scheduler</div>                                     |
| <div style="width:150px">Description</div> | O                                       | String                                  | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">Scheduler is used for planned order submition</div> |

## Constraints

---
Can't set input dependency to element.
