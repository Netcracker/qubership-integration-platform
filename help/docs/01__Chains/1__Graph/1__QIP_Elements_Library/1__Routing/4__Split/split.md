# Split
## Description

---
**Split** component is indented to split the chain processing into several branches and aggregate the results of all branches into one Exchange again:

- **JSON bodies** of several Split Elements will be aggregated into single JSON file.
- **Headers**
  - Headers from **Main** Split Element will be aggregated as-is, without any prefixes.
  - Headers from **every** Split Element with marked "**Propagate headers**" checkbox will be aggregated with branch's name as a prefix, separated with a dot.
- **Properties**
  -Properties from **Main** Split Element will be aggregated as-is, without any prefixes.
  -Properties from **every** Split Element with marked "**Propagate properties**" checkbox will be aggregated with branch's name as a prefix, separated with a dot.

## User Interface

---
### "Parameters" Tab (Split)
#### Common Parameters
| Parameter                                        | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                                                                                                                                                         | Sample                               |
| ------------------------------------------------ | :-------------------------------------- | :-------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| <div style="width:150px">Timeout</div>           | O                                       | Number                                  | <div style="width:400px">Determines the timeout in milliseconds until all parallel split branches are completed. When left empty, default value will be applied.<br/><b>Default value:</b> 0</div>                                                                                                                                                                                                  | <div style="width:350px">10000</div> |
| <div style="width:150px">Stop On Exception</div> | O                                       | Boolean                                 | <div style="width:400px">Checkbox. Defines the strategy in case one or more subchains end with an error or do not have time to work: <ul><li><b>checked</b> - when one of <b>Split branches</b> fails, it also leads to whole "<b>Split</b>" element failure and process stop.</li><li><b>unchecked</b> - failures of split branches do not lead to the failure of whole "Split" element.</li></ul> | <div style="width:350px">N/A</div>   |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                              | Sample                                                                           |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------ | -------------------------------------------------------------------------------- |
| <div style="width:150px">Name</div>        | M                                       | String                                  | <div style="width:400px">Name of the Split container element.	</div>     | <div style="width:350px">Split customer onboarding and quotation processes</div> |
| <div style="width:150px">Description</div> | O                                       | String                                  | <div style="width:400px">Free text field for element description.	</div> | <div style="width:350px">Description text</div>                                  |

### "Parameters" Tab (Main Split Element)

Main Split Element is using as the main branch, which will always propagate all data without any constraints.

#### Common Parameters
| Parameter                                 | <div style="width:75px">Mandatory</div>   | <div style="width:75px">Data Type</div>  | Description                                                                                             | Sample                                     |
|-------------------------------------------|:------------------------------------------|:-----------------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------|
| <div style="width:150px">Split Name</div> | M                                         | String                                   | <div style="width:400px">Defines the name of the branch. Must be <b>unique</b> within the chain.	</div> | <div style="width:350px">Subchain1</div>   |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                              | Sample                                                       |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------ |
| <div style="width:150px">Name</div>        | M                                       | String                                  | <div style="width:400px">Name of the Main Split branch element.	</div>   | <div style="width:350px">Branch for creating a user</div>    |
| <div style="width:150px">Description</div> | O                                       | String                                  | <div style="width:400px">Free text field for element description.	</div> | <div style="width:350px">Create user for the new sales</div> |


### "Parameters" Tab (Split Element)
#### Common Parameters
| Parameter                                           | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>    | Description                                                                                                                      | Sample                                    |
|-----------------------------------------------------|:-----------------------------------------|:-------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------|
| <div style="width:150px">Split Name</div>           | M                                        | String                                     | <div style="width:400px">Defines the name of the branch. Must be <b>unique</b> within the chain.</div>                           | <div style="width:350px">Subchain1</div>  |
| <div style="width:150px">Propagate headers</div>    | M                                        | Boolean                                    | <div style="width:400px">Checkbox, that enables propagation of headers from this branch to the aggregated exchange object.</div> | <div style="width:350px">N/A</div>        |
| <div style="width:150px">Propagate properties</div> | M                                        | Boolean                                    | <div style="width:400px">Checkbox, that enables propagation of properties from this branch to the aggregated exchange object.</div>                | <div style="width:350px">N/A</div>        |

#### Metadata
| Parameter                                    | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div> | Description                                                              | Sample                                                       |
|----------------------------------------------|:-----------------------------------------|:----------------------------------------|--------------------------------------------------------------------------|--------------------------------------------------------------|
| <div style="width:150px">Name</div>          | M                                        | String                                  | <div style="width:400px">Name of the Split branch element.</div>         | <div style="width:350px">Branch for creating a user</div>    |
| <div style="width:150px">Description</div>   | O                                        | String                                  | <div style="width:400px">Free text field for element description.</div>  | <div style="width:350px">Create user for the new sales</div> |

## Constraints

---
Please consider next constraints:
- Split cannot be added without at least one Main Split element or one Split Element.
- When split branch fails, all processing data within this branch, including headers, properties and body will be erased. This means, that there won't be any ability to refer to this data outside of the split element.
