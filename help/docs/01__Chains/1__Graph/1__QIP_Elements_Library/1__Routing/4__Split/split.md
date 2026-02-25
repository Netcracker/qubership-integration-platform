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
| Parameter         | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                    | Sample |
| ----------------- | :-------- | :-------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Timeout           | O         | Number    | Determines the timeout in milliseconds until all parallel split branches are completed. When left empty, default value will be applied.<br>**Default value:** 0                                                                                                                                                                                                | 10000  |
| Stop On Exception | O         | Boolean   | Checkbox. Defines the strategy in case one or more subchains end with an error or do not have time to work: <ul><li>**checked** - when one of **Split branches** fails, it also leads to whole "**Split**" element failure and process stop.</li><li>**unchecked** - failures of split branches do not lead to the failure of whole "Split" element.</li></ul> | N/A    |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                                            |
| ----------- | :-------- | :-------- | ---------------------------------------- | ------------------------------------------------- |
| Name        | M         | String    | Name of the Split container element.     | Split customer onboarding and quotation processes |
| Description | O         | String    | Free text field for element description. | Description text                                  |

### "Parameters" Tab (Main Split Element)

Main Split Element is using as the main branch, which will always propagate all data without any constraints.

#### Common Parameters
| Parameter  | Mandatory | Data Type | Description                                                             | Sample    |
| ---------- | :-------- | :-------- | ----------------------------------------------------------------------- | --------- |
| Split Name | M         | String    | Defines the name of the branch. Must be <b>unique</b> within the chain. | Subchain1 |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                        |
| ----------- | :-------- | :-------- | ---------------------------------------- | ----------------------------- |
| Name        | M         | String    | Name of the Main Split branch element.   | Branch for creating a user    |
| Description | O         | String    | Free text field for element description. | Create user for the new sales |

### "Parameters" Tab (Split Element)
#### Common Parameters
| Parameter            | Mandatory | Data Type | Description                                                                                          | Sample    |
| -------------------- | :-------- | :-------- | ---------------------------------------------------------------------------------------------------- | --------- |
| Split Name           | M         | String    | Defines the name of the branch. Must be **unique** within the chain.                                 | Subchain1 |
| Propagate headers    | M         | Boolean   | Checkbox, that enables propagation of headers from this branch to the aggregated exchange object.    | N/A       |
| Propagate properties | M         | Boolean   | Checkbox, that enables propagation of properties from this branch to the aggregated exchange object. | N/A       |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                        |
| ----------- | :-------- | :-------- | ---------------------------------------- | ----------------------------- |
| Name        | M         | String    | Name of the Split branch element.        | Branch for creating a user    |
| Description | O         | String    | Free text field for element description. | Create user for the new sales |

## Constraints

---
Please consider next constraints:
- Split cannot be added without at least one Main Split element or one Split Element.
- When split branch fails, all processing data within this branch, including headers, properties and body will be erased. This means, that there won't be any ability to refer to this data outside of the split element.
