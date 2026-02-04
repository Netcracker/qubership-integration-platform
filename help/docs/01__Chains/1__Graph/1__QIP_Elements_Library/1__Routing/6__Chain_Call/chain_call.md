# Chain Call
## Description

---
**Chain Call** element allows to continue integration flow by synchronously invoking another chain, specified as a target one in the settings of the element. Target chain must have [Chain Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger.md) in it to be available for connection.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter                                    | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | Sample                                                   |
| -------------------------------------------- | :-------------------------------------- | :-------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| <div style="width:150px">Chain trigger</div> | M                                       | List                                    | <div style="width:400px">Dropdown list, that contains all chains with [Chain Triggers](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger.md) elements, allowed to be linked with. It is possible to link only <b>one</b> chain.<br/><br/><div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>When chain with respective [Chain Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger.md) element is selected from the list, it is possible to navigate to it via <img src="docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/img/plus.svg" width="20" height="20"> button.</div></div> | <div style="width:350px">Start customer onboarding</div> |

#### Advanced Parameters
| Parameter                                   | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                             | Sample                               |
|---------------------------------------------|:----------------------------------------|:----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| <div style="width:150px">Timeout (ms)</div> | O                                       | Number                                  | <div style="width:400px">Timeout for communication between the chains, linked via "Chain Call" + "Chain Trigger" elements. If timeout is reached, system will fail the session. When left blank, default value will be applied.<br/><b>Default value: </b>30000 </div>  | <div style="width:350px">30000</div> |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div>  | <div style="width:75px">Data Type</div>  | Description                                                              | Sample                                                       |
|--------------------------------------------|:-----------------------------------------|:-----------------------------------------|--------------------------------------------------------------------------|--------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                        | String                                   | <div style="width:400px">Name of the element.	</div>                     | <div style="width:350px">Chain Call</div>                    |
| <div style="width:150px">Description</div> | O                                        | String                                   | <div style="width:400px">Free text field for element description.	</div> | <div style="width:350px">Call customer onboarding flow</div> |

## Constraints

---
There are no specific constraints for the element.
