# Checkpoint
## Description

---
**Checkpoint** element allows to set up "save point" in the chain, from which chain could be retried in case of a failure. This element initially preserves the context, passed through it and then re-send this context as part of the retry action, initiated either from **Session** tab or via API. When retrying session via API, request must be carefully verified, as its data will override the one, preserved by checkpoint initially.

## User Interface

---
### "Parameters" Tab
#### Common Parameters
| Parameter                                    | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Sample                                                                                                                                                                                                        |
| -------------------------------------------- | :-------------------------------------- | :-------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| <div style="width:150px">Path Template</div> | M                                       | String                                  | <div style="width:400px">HTTP path to the checkpoint to retry the session. Sets automatically, non editable parameter. The value is settled by the following template:<br/>  `{public_gateway_host}/api/v1/qip/engine/chains/`<br/>`{chainId}/sessions/{sessionId}/checkpoint-elements/{checkpointElementId}/retry`<br/><br/> <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">To trigger checkpoint, consumers have to use POST HTTP method while having the role <b>ROLE_QIP_SESSION_RETRY</b>.</div></div> | <div style="width:350px"> `/api/v1/qip/engine/chains/bf2c225f-0b0a-4fab-82a6-eab230b6164a/sessions/c8515604-fe58-430e-847e-3f2d9a303531/checkpoint-elements/319210c4-700d-4296-82ff-102f656219e2/retry`</div> |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                               | Sample                                                                                    |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ----------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| <div style="width:150px">Name</div>        | M                                       | String                                  | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">Customer Checkpoint</div>                                        |
| <div style="width:150px">Description</div> | O                                       | String                                  | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">Checkpoint allows restarting the session after its failure</div> |


## Constraints

---
Please consider next constraints:
- Checkpoint can be linked with previous element by only one input arrow to prevent the situation of context overwriting.
- Checkpoint data will be preserved only **if session fails**, hence it shall be noted, that failures that are successfully handled within chain (e.g. by "Catch" within "Try-Catch-Finally") won't be captured by "Checkpoint" as the overall session's status would be "Completed with Warnings".
- Checkpoint **must not** be used within next container-like elements or their sub-elements:
  - [Circuit Breaker](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/1__Circuit_Breaker/circuit_breaker.md) - as retried session ignores current circuit breaker status, which nihilates the logic of this element.
  - [Loop](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/8__Loop/loop.md) - as each new failed loop session will override the checkpoint placed inside this element. Additionally, retried session won't trigger any sequential transactions, scoped for loop initially.
- Checkpoint **must not** be used within sub-chains (chains, where starting element is [Chain Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger.md)).
- While placing checkpoint into the allowed container-elements and their sub-elements (e.g. "If" for "Choice" and "Finally" for "Try-Catch-Finally"), <font color="red">it must be noted</font>, that retried flow won't go further than the last element of the container, checkpoint is placed to. 
- There are specific headers, that are recognized by the system as context-related (or "Technical") ones. Such headers will be available in the "Technical context" tab under the session, if they were passed in the request. Even though values could be overridden for technical headers via sender elements, where "Propagate context" is available, Checkpoint will anyway store their original values and use them when retry is requested.
- While retrying failed synchronous calls, resulted chain response won't go to the system that initially made a request, as the system has already received synchronous response when initial session failed.
- Retry mechanism restores objects of the original classes if these classes implement serializable interface, for example: public class ObjectNode implements java.io.Serializable {...}.
- Properties of object type won't be restored as part of Retry process, if the classes for such properties were defined via scripts.