# Chain Call
## Description

---
**Chain Call** element allows to continue integration flow by synchronously invoking another chain, specified as a target one in the settings of the element. Target chain must have [Chain Trigger](../../6__Triggers/2__Chain_Trigger/chain_trigger.md) in it to be available for connection.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter     | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                 | Sample                    |
| ------------- | :-------- | :-------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------- |
| Chain trigger | M         | List      | Dropdown list, that contains all chains with [Chain Triggers](../../6__Triggers/2__Chain_Trigger/chain_trigger.md) elements, allowed to be linked with. It is possible to link only **one** chain.<br><br>ℹ️**Note:** When chain with respective [Chain Trigger](../../6__Triggers/2__Chain_Trigger/chain_trigger.md) element is selected from the list, it is possible to navigate to it via ![Plus](img/plus.svg) button. | Start customer onboarding |

#### Advanced Parameters
| Parameter    | Mandatory | Data Type | Description                                                                                                                                                                                                                        | Sample |
| ------------ | :-------- | :-------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Timeout (ms) | O         | Number    | Timeout for communication between the chains, linked via "Chain Call" + "Chain Trigger" elements. If timeout is reached, system will fail the session. When left blank, default value will be applied.<br>**Default value:** 30000 | 30000  |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample                        |
| ----------- | :-------- | :-------- | ---------------------------------------- | ----------------------------- |
| Name        | M         | String    | Name of the element.                     | Chain Call                    |
| Description | O         | String    | Free text field for element description. | Call customer onboarding flow |

## Constraints

---
There are no specific constraints for the element.
