# Try-Catch-Finally
## Description

---
**Try-Catch-Finally** is an element, designed for proper exception handling building in the chain.

Configuration of this element must follow simple aspects:

- The main part of the chain, that is expected to be executed, must be placed into the **Try** block.
- For each particular error type, **Catch** block must be created with properly filled expression and priority parameters. Part of the chain, that shall be triggered, when particular error is detected must be also placed into respective **Catch** block.
- **Finally** block must contain part of the chain, that is going to be executed at the end of Try-Catch-Finally processing. This part will always be executed, whether an error occurred or not.


>ℹ️Note: One of the options of accessing error details, captured by **Catch** block, would be using next string in the **Script** element:
><pre style="background-color: #F5F5F7;"><code style="color: #000;">exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)</code></pre>

## User Interface

---
### "Parameters" Tab (Try-Catch-Finally)
#### Metadata
| Parameter   | Mandatory | Data Type | Description                                        | Sample                        |
| ----------- | :-------- | :-------- | -------------------------------------------------- | ----------------------------- |
| Name        | M         | String    | Name of the "Try-Catch-Finally" container element. | Try - Catch                   |
| Description | O         | String    | Free text field for module description.            | Applies error handling logic. |

### "Parameters" Tab (Try)
#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                                   |
| ----------- | :-------- | :-------- | ---------------------------------------- | ---------------------------------------- |
| Name        | M         | String    | Name of the "Try" container element.     | Order submit                             |
| Description | O         | String    | Free text field for element description. | Part of the logic that must be executed. |

### "Parameters" Tab (Catch)
#### Common Parameters
| Parameter | Mandatory | Data Type | Description                                                                                                                              | Sample              |
| --------- | :-------- | :-------- | ---------------------------------------------------------------------------------------------------------------------------------------- | ------------------- |
| Exception | M         | String    | This field must contain the name of Java class, that must fail to start the chain branch, configured under particular "Catch" container. | java.lang.Exception |
| Priority  | M         | Number    | Priority of "Catch" containers. Container with priority "0" will be taken for processing first.                                          | 1                   |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                                  |
| ----------- | :-------- | :-------- | ---------------------------------------- | --------------------------------------- |
| Name        | M         | String    | Name of the "Catch" container element.   | Catch Internal server error             |
| Description | O         | String    | Free text field for element description. | Element handles internal server errors. |


### "Parameters" Tab (Finally)
#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample           |
| ----------- | :-------- | :-------- | ---------------------------------------- | ---------------- |
| Name        | M         | String    | Name of the element.                     | Create ticket    |
| Description | O         | String    | Free text field for element description. | Description text |

## Constraints

---
Please consider next constraints:
- Only one **Try** module should be used in **Try-Catch-Finally**.
- One or more **Catch** modules have to be used.
- More than one **Finally** element module can not be used in **Try-Catch-Finally**. **Try-Catch-Finally** also can proceed without **Finally** module.
