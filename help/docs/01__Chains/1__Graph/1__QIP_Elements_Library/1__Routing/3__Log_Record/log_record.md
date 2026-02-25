# Log Record
## Description

---
**Log Record** element allows to configure a message that will be logged, when chain is deployed with logging level, specified in the element. Message could be built from a property, variable, script outcome, constant text or via using SIMPLE language. Message, that has been configured within this element will be logged as a new record in microservice logs with common Qubership Integration Platform parameters and format preserved.

>ℹ️**Note:**
>Message won't be logged if chain is deployed with a logging level higher than selected in this list.
## User Interface

---
### "Logging" Tab
#### Common Parameters
| Parameter | Mandatory | Data Type | Description                                                                                                                                                                                                                                                | Sample                                                                     |
| --------- | :-------- | :-------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Log as    | M         | List      | Specifies the type of the record, that will be written to the microservice log:<br><ul><li>Error</li><li>Warning</li><li>Info</li></ul><br>`ℹ️ Note: Message won't be logged if chain is deployed with a logging level higher than selected in this list.` | Error                                                                      |
| Sender    | O         | String    | Specifies a custom name of the system, that sends a request.<br>Record in the log will contain next additional item:<br>`[sender= %value%]`                                                                                                                | <li>Constant text </li><br><li>Message with runtime variable/property</li> |
| Receiver  | O         | String    | Specifies a custom name of the system, that accepts a request.<br>Record in the log will contain next additional item:<br> `[receiver = %value%]`                                                                                                          | <li>Constant text </li><li>Message with runtime variable/property</li>     |

#### Business identifiers

Table that allows to configure a map of parameter name and its value.

| Parameter | Mandatory | Data Type | Description                                                                                                                    | Sample                                         |
| --------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------- |
| Name      | M         | String    | Business identifier name. Basically, any additional identifier, that has a business value for the process, built by the chain. | Id                                             |
| Value     | O         | String    | Business identifier's value. Can be a camel exchangeProperty, constant, variable, etc.                                         | ${exchangeProperty.variables["Identificator"]} |

#### Message
"Message" text box provides an ability to build a custom message to be logged. This message will be added at the end of logged record. Please refer to the samples below:
<ul><li>Constant message:</li><pre style="background-color: #F5F5F7"><code  style="color: #000000">System failure detected</code></pre><li>Message with runtime variable/property:</li><pre style="background-color: #F5F5F7"><code  style="color: #000000">There are ${exchangeProperty.variables["variableName"]} customer accounts with missing data</code></pre><li>Message with non-runtime variable:</li><pre style="background-color: #F5F5F7"><code  style="color: #000000">Failure detected on #{instance_name} instance</code></pre></ul>

### "Parameters" Tab
#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                                |
| ----------- | :-------- | :-------- | ---------------------------------------- | ------------------------------------- |
| Name        | M         | String    | Name of the element.                     | Log record                            |
| Description | O         | String    | Free text field for element description. | Custom message is going to be logged. |

## Constraints

---
There are no specific constraints for the element.
