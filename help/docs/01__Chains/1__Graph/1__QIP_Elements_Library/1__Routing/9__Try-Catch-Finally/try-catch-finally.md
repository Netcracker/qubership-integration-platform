# Try-Catch-Finally
## Description

---
**Try-Catch-Finally** is an element, designed for proper exception handling building in the chain.

Configuration of this element must follow simple aspects:

- The main part of the chain, that is expected to be executed, must be placed into the **Try** block.
- For each particular error type, **Catch** block must be created with properly filled expression and priority parameters. Part of the chain, that shall be triggered, when particular error is detected must be also placed into respective **Catch** block.
- **Finally** block must contain part of the chain, that is going to be executed at the end of Try-Catch-Finally processing. This part will always be executed, whether an error occurred or not.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
One of the options of accessing error details, captured by <b>Catch</b> block, would be using next string in the <b>Script</b> element:
<br/><div><pre style="background-color: #F5F5F7;"><code style="color: #000;">exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)</code></pre></div>
</div>

## User Interface

---
### "Parameters" Tab (Try-Catch-Finally)
#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                                       | Sample                                                       |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|-----------------------------------------------------------------------------------|--------------------------------------------------------------|
| <div style="width:150px"> Name</div>       | M                                        | String                                   | <div style="width:400px">Name of the "Try-Catch-Finally" container element.</div> | <div style="width:350px">Try - Catch </div>                  |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field for module description.</div>            | <div style="width:350px">Applies error handling logic.</div> |

### "Parameters" Tab (Try)
#### Metadata
| Parameter                                    | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>   | Description                                                              | Sample                                                                  |
|----------------------------------------------|:-----------------------------------------|:------------------------------------------|--------------------------------------------------------------------------|-------------------------------------------------------------------------|
| <div style="width:150px">Name</div>          | M                                        | String                                    | <div style="width:400px">Name of the "Try" container element.	</div>     | <div style="width:350px">Order submit</div>                             |
| <div style="width:150px">Description</div>   | O                                        | String                                    | <div style="width:400px">Free text field for element description.	</div> | <div style="width:350px">Part of the logic that must be executed.</div> |

### "Parameters" Tab (Catch)
#### Common Parameters
| Parameter                                | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                              | Sample                                             |
|------------------------------------------|:----------------------------------------|:----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------|
| <div style="width:150px">Exception</div> | M                                       | String                                  | <div style="width:400px">This field must contain the name of Java class, that must fail to start the chain branch, configured under particular "Catch" container.	</div> | <div style="width:350px">java.lang.Exception</div> |
| <div style="width:150px">Priority</div>  | M                                       | Number                                  | <div style="width:400px">Priority of "Catch" containers. Container with priority "0" will be taken for processing first.	</div>                                          | <div style="width:350px">1</div>                   |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                              | Sample                                                                 |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|--------------------------------------------------------------------------|------------------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                   | <div style="width:400px">Name of the "Catch" container element.	</div>   | <div style="width:350px">Catch Internal server error</div>             |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field for element description.	</div> | <div style="width:350px">Element handles internal server errors.</div> |


### "Parameters" Tab (Finally)
#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>   | Description                                                               | Sample                                           |
|--------------------------------------------|:-----------------------------------------|:------------------------------------------|---------------------------------------------------------------------------|--------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                    | <div style="width:400px">Name of the element.</div>                       | <div style="width:350px">Create ticket</div>     |
| <div style="width:150px">Description</div> | O                                        | String                                    | <div style="width:400px">Free text field for element description.</div>   | <div style="width:350px">Description text</div>  |

## Constraints

---
Please consider next constraints:
- Only one **Try** module should be used in **Try-Catch-Finally**.
- One or more **Catch** modules have to be used.
- More than one **Finally** element module can not be used in **Try-Catch-Finally**. **Try-Catch-Finally** also can proceed without **Finally** module.
