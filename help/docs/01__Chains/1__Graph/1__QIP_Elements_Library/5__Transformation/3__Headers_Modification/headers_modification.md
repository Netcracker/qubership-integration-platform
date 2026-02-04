# Headers Modification
## Description

---
This element allows to perform specific actions against each particular header, available in **Exchange Object**:

- Replace value with constant
- Replace value with variable's values
- Remove the header completely
- Keep existing value

## User Interface

---
### "Header Modification" Tab
#### Add/Keep Headers
| Parameter                            | <div style="width:75px">Mandatory</div>    | <div style="width:75px">Data Type</div>   | Description                                                                                                                                                                                      | Sample                                                        |
|--------------------------------------|:-------------------------------------------|:------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| <div style="width:150px">Name</div>  | O                                          | String                                    | <div style="width:400px">Name of the header. Headers, added to the table will be kept, even if they are subjects to deletion by mask, specified in "Remove Headers".</div>                       | <div style="width:350px">x-customer-id </div>                 |
| <div style="width:150px">Value</div> | O                                          | String                                    | <div style="width:400px">Value of the header. It can be entered as a constant or variable. If no value is specified for a particular header in the table, it will keep its original value.</div> | <div style="width:350px">${exchangeProperty.customerId}</div> |

#### Remove Headers
| Parameter                              | <div style="width:75px">Mandatory</div>    | <div style="width:75px">Data Type</div>  | Description                                                                                                                      | Sample                                                                                                                                                                                                                                                                                                                                                                                          |
|----------------------------------------|:-------------------------------------------|:-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <div style="width:150px">Name</div>    | O                                          | String                                   | <div style="width:400px">Name of the headers, that shall be removed from the message. Field also accepts mask and RegExp.</div>  | <div style="width:350px"><ul><li><b>*-number</b> - removes all headers, where it contains "-number" at the end</li><li><b>\*</b> - removes all headers</li><li><b>kafka.*</b> - remove all headers, starting with "kafka"</li><li><b>*id</b> - removes all headers that end with "id"</li><li><b>^\w+-id$</b> - removes headers that start with single word and end with "-id"</li></ul></div>  |

#### Metadata
| Parameter                                   | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                                                                                              | Sample                                                                  |
|---------------------------------------------|:-----------------------------------------|:-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| <div style="width:150px">Name</div>         | M                                        | String                                   | <div style="width:400px">Name of the element.</div>                                                                                      | <div style="width:350px">Modify Headers</div>                           |
| <div style="width:150px">Description</div>  | O                                        | String                                   | <div style="width:400px">Free text field, that contains description of the element.</div>                                                | <div style="width:350px">Adds new headers to the exchange object.</div> |

## Constraints

---
There are specific headers, that are recognized by the system as **context-related** (or "**Technical**") ones. Such headers will be available in the **"Technical context"** tab under the session, if they were passed in the request. It is only possible to modify **"Technical"** headers in the sender elements when option **"Propagate context"** is selected for this element. Please read respective article for each particular sender, where mentioned option is available.