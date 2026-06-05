# Header Modification
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
| Parameter | Mandatory | Data Type | Description                                                                                                                                                       | Sample                         |
| --------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------ |
| Name      | O         | String    | Name of the header. Headers, added to the table will be kept, even if they are subjects to deletion by mask, specified in "Remove Headers".                       | x-customer-id                  |
| Value     | O         | String    | Value of the header. It can be entered as a constant or variable. If no value is specified for a particular header in the table, it will keep its original value. | ${exchangeProperty.customerId} |

#### Remove Headers
| Parameter | Mandatory | Data Type | Description                                                                                      | Sample                                                                                                                                                                                                                                                                                                                                           |
| --------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Name      | O         | String    | Name of the headers, that shall be removed from the message. Field also accepts mask and regular expression. | <ul><li>***-number** - removes all headers, where it contains "-number" at the end</li><li>**\*** - removes all headers</li><li>**kafka.*** - remove all headers, starting with "kafka"</li><li>***id** - removes all headers that end with "id"</li><li>**^\w+-id$** - removes headers that start with single word and end with "-id"</li></ul> |

### "Parameters" Tab
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                   |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ---------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | Modify Headers                           |
| Description | O         | String    | Free text field, that contains description of the element. | Adds new headers to the exchange object. |

## Constraints

---
There are specific headers, that are recognized by the system as **context-related** (or "**Technical**") ones. Such headers will be available in the **"Technical context"** tab under the session, if they were passed in the request. It is only possible to modify **"Technical"** headers in the sender elements when option **"Propagate context"** is selected for this element. Please read respective article for each particular sender, where mentioned option is available.
