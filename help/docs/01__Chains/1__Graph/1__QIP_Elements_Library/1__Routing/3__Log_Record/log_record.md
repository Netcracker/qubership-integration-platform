# Log Record
## Description

---
**Log Record** element allows to configure a message that will be logged, when chain is deployed with logging level, specified in the element. Message could be built from a property, variable, script outcome, constant text or via using SIMPLE language. Message, that has been configured within this element will be logged as a new record in microservice logs with common Qubership Integration Platform parameters and format preserved.

## User Interface

---
### "Logging" Tab
#### Common Parameters
| Parameter                               | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                                                                                                                                        | Sample                                                                     |
| --------------------------------------- | :-------------------------------------- | :-------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| <div style="width:150px">Log as</div>   | M                                       | List                                    | <div style="width:400px">Specifies the type of the record, that will be written to the microservice log:<ul><li>Error</li><li>Warning</li><li>Info</li></ul><div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>Message won't be logged if chain is deployed with a logging level higher than selected in this list.</div></div> | <div style="width:350px">Error</div>                                       |
| <div style="width:150px">Sender</div>   | O                                       | String                                  | <div style="width:400px">Specifies a custom name of the system, that sends a request.<br/>Record in the log will contain next additional item:<pre style="background-color: #F5F5F7"><code  style="color: #000000">[sender= %value%]</code></pre></div>                                                                                                                            | <li>Constant text </li><br><li>Message with runtime variable/property</li> |
| <div style="width:150px">Receiver</div> | O                                       | String                                  | <div style="width:400px">Specifies a custom name of the system, that accepts a request.<br/>Record in the log will contain next additional item:<pre style="background-color: #F5F5F7"><code  style="color: #000000">[receiver = %value%]</code></pre></div>                                                                                                                       | <li>Constant text </li><li>Message with runtime variable/property</li>     |

#### Business identifiers

Table that allows to configure a map of parameter name and its value.

| Parameter                            | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                   | Sample                                                                        |
| ------------------------------------ | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| <div style="width:150px">Name</div>  | M                                       | String                                  | <div style="width:400px">Business identifier name. Basically, any additional identifier, that has a business value for the process, built by the chain.</div> | <div style="width:350px">Id</div>                                             |
| <div style="width:150px">Value</div> | O                                       | String                                  | <div style="width:400px">Business identifier's value. Can be a camel exchangeProperty, constant, variable, etc. </div>                                        | <div style="width:350px">${exchangeProperty.variables["Identificator"]}</div> |

#### Message
"Message" text box provides an ability to build a custom message to be logged. This message will be added at the end of logged record. Please refer to the samples below:
<ul><li>Constant message:</li><pre style="background-color: #F5F5F7"><code  style="color: #000000">System failure detected</code></pre><li>Message with runtime variable/property:</li><pre style="background-color: #F5F5F7"><code  style="color: #000000">There are ${exchangeProperty.variables["variableName"]} customer accounts with missing data</code></pre><li>Message with non-runtime variable:</li><pre style="background-color: #F5F5F7"><code  style="color: #000000">Failure detected on #{instance_name} instance</code></pre></ul>

### "Parameters" Tab
#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>   | Description                                                             | Sample                                                                |
|--------------------------------------------|:-----------------------------------------|:------------------------------------------|-------------------------------------------------------------------------|-----------------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                    | <div style="width:400px">Name of the element.</div>                     | <div style="width:350px">Log record</div>                             |
| <div style="width:150px">Description</div> | O                                        | String                                    | <div style="width:400px">Free text field for element description.</div> | <div style="width:350px">Custom message is going to be logged.</div>  |

## Constraints

---
There are no specific constraints for the element.
