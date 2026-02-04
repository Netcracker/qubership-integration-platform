# Chain Configuration Process

## Description

---
Qubership Integration Platform follows chain-concept, where integration task is being configured or build with the different elements and integration platform capabilities. 

The high-level diagram below shows the process of chain configuration.

![[Chain_Configuration_Proc.svg]]

**Diagram details**

| #     | Steps                                              | Description                                                                                                                                                                                                                                                                                    |
| ----- | -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | <div style="width:150px">Configuration in UI</div> | <div style="width:798px">The first step is the creation of the chain via integration platform UI. User is build the chain (basically, integration step-by-step instruction for platform), utilizing UI components, such us windows for service discovery, triggers, mapper, script, etc.</div> |
| 2     | <div style="width:150px">Snapshot</div>            | <div style="width:798px">Snapshot is a system-understandable file that stores all the instructions, given within the chain in XML format. Snapshot provides a "Save point" for the chain and works as a version of it. User is able to always come back to any saved version. </div>           |
| 3,4,5 | <div style="width:150px">Deployment</div>          | <div style="width:798px">To apply any snapshot to particular environment (engine) with specific logging settings, it is required to Deploy the snapshot. As part of this operation, Engine(s) will retrieve all required details from Catalog via REST API.</div>                              |
| 5     | <div style="width:150px">GW Configuration</div>    | <div style="width:798px">For external end-points it is essential to complete gateway set-up, that is done outside of Qubership Integration Platform and shall be handled by particular project group.</div>                                                                                    |

## User Interface

---
Chain configuration is handled via multiple UI elements, separately described in respective articles: [QIP Elements Library](docs/01__Chains/1__Graph/graph.md)