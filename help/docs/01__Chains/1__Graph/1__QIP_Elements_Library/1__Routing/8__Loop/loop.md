# Loop
## Description

---
**Loop** element allows to process an input data through a single flow multiple time. It is designed to handle scenarios, when a list of values is required to be processed separately one by one through a predefined process.

There are two principally different ways of utilizing Loop:

- Use **fixed number of iterations**: this is achieved by entering number (or use a reference to property or variable that also contains a number) to the "Value" field.
- Use **expression** to exit from Loop: this is possible to do by entering **doWhile expression** to the "**Value**" field. Iteration attempts are stopped when expression evaluates to false.

Before the Loop module, it is recommended to set a property with the condition to exit the loop and then refer to it in the "**Value**" field. Notice, that by default the loop uses the same exchange throughout the looping, so the result from the previous iteration will be used for the next.

## User Interface

---
### "Parameters" Tab
#### Common Parameters

| Parameter                         | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                               | Sample                                                                                                                                                                                                                                                 |
| --------------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Value                             | M         | String    | Field, that can specify:<ul><li>**Counter** value, entered with number or reference to property/variable. Simply number of looped runs.</li><li>**doWhile expression**, that makes the flow exit the Loop, when expression evaluates to false.</li></ul>When **number** entered, but **"Expression used"** checkbox is enabled, field becomes highlighted with red and does not allow to save the settings in such state. | Property:<pre style="background-color: #F5F5F7"><code style="color: #000000">${exchangeProperty.userListSize}</code></pre><br>Expression:<pre style="background-color: #F5F5F7"><code style="color: #000000">${headers.iterations}<10</code></pre><br> |
| Expression is used                | M         | Boolean   | Checkbox, that must be enabled, when **"Value"** field contains an expression to avoid processing failures. Otherwise, it must be disabled.                                                                                                                                                                                                                                                                               | N/A                                                                                                                                                                                                                                                    |
| Copy start context each iteration | M         | Boolean   | Checkbox, that enables reuse of **original Exchange data** in each sequential iteration.                                                                                                                                                                                                                                                                                                                                  | N/A                                                                                                                                                                                                                                                    |
| Loop iteration index property     | O         | String    | Index of the current iteration. Any name can be specified for this property and then referred to it in other elements, placed under the Loop container.                                                                                                                                                                                                                                                                   | N/A                                                                                                                                                                                                                                                    |
| Maximum iteration count           | O         | Number    | Maximum number of loop iterations. This parameter allows to avoid unexpected cases, caused by infinite loop.                                                                                                                                                                                                                                                                                                              | 100                                                                                                                                                                                                                                                    |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                    |
| ----------- | :-------- | :-------- | ---------------------------------------- | ------------------------- |
| Name        | M         | String    | Name of the element.                     | Loop                      |
| Description | O         | String    | Free text field for element description. | Processes array of items. |

## Constraints

---
There are no specific constraints for the element.
