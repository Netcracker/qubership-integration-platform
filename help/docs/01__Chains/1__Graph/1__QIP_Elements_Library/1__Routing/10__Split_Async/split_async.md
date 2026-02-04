# Split Async
## Description

---
**Split Async** allows to route message to one or more separate **asynchronous flows** without result aggregation. Each Async Split Element starts parallel chain flow asynchronously.

## User Interface

---
### "Parameters" Tab (Split Async)
#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                                | Sample                                                                            |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|----------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                   | <div style="width:400px">Name of the Split Async container element.</div>  | <div style="width:350px">Container of async branches</div>                        |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field for element description.	</div>   | <div style="width:350px">Split Async container with multiple split branches</div> |

### "Parameters" Tab (Async Split Element)
#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                              | Sample                                                      |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|--------------------------------------------------------------------------|-------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                   | <div style="width:400px">Name of the Split Async element.	</div>         | <div style="width:350px">Split Async branch</div>           |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field for element description.	</div> | <div style="width:350px">Async flow for notifications</div> |

## Constraints

---
**Async Split Element** should be used at least once in **Split Async** module.
