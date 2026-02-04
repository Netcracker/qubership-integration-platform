# Script
## Description

---
**Script** element allows to build part of the chain logic with Groovy-based scripting.

## User Interface

---
### "Script" Tab

This tab allows to write transformation script in Groovy programming language for further processing. System displays the code completion popup automatically during the typing.

For example, next code might be utilized to set a new Exchange Body:

<pre style="background-color: #F5F5F7"><code  style="color: #000000">exchange.getMessage().setBody("Body")</code></pre>

For the quick navigation in the code block, use search bar, accessed by clicking combination of Ctrl+F. Refer to [Tutorial Page](1__Tutorial_on_the_Apache_Groovy/tutorial_apache_groovy.md) for additional samples and hints.

### "Parameters" Tab
#### Metadata
| Parameter                                   | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                                                                                                                         | Sample                                                 |
|---------------------------------------------|:-----------------------------------------|:-----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| <div style="width:150px">Name</div>         | M                                        | String                                   | <div style="width:400px">Name of the element.</div>                                                                                                                 | <div style="width:350px">Set body</div>                |
| <div style="width:150px">Description</div>  | O                                        | String                                   | <div style="width:400px">Free text field for module description. Use for adding additional text, for example for description why and when we use the element	</div> | <div style="width:350px">Script sets a new body.</div> |

## Constraints

---
While configuring **Script**, it is not possible to import classes, defined in another **Script**, as they are isolated from each other on class loader level. Attempting to perform such import will lead to deployment issues.