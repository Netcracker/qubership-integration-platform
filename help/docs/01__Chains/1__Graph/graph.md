# Graph
## Description

---
**Graph** is a work environment, presented in a "blueprint-like" way, that provides ability to connect multiple chain elements in particular order to form an integration chain and solve specific integration task.

## User Interface

---
On the "**Graph**" tab user can view and edit a particular chain by adding, updating and removing the elements and connections between them.

>**ℹ️Note:** When configuration graph is opened, system reviews the chain to detect deprecated elements:<ul><li>  If **only simple (non-container) deprecated elements are detected**, the system displays a warning message stating they may be removed in future releases.</li><li> If **deprecated container elements (the presence of deprecated simple elements has no impact) are detected** in the chain, the system recommends migrating them to their latest versions. After confirmation, the system first attempts to save the current chain state as a new snapshot and then updates the chain with the latest versions of container elements. A notification message will indicate the migration process results</li><li>If **overridden chain** is opened, the system displays notification panel explaining that the current chain can't be deployed with a reference to the chain that overrides it.</li></ul>

### Tool Panel

Please find the description for all available tools below:

- ![Plus](img/plus.svg) - zoom in configuration graph.
- ![Minus](img/minus.svg) - zoom out configuration graph.
- ![Expand](img/expand.svg) - fit view.
- ![Rotate right](img/rotate-right.svg) - change graph orientation (vertical/horizontal).

### General Actions

In the bottom right menu, marked with ![More](img/more.svg), the following actions are available:

<u>_Web UI_</u>

* ![Download](img/cloud-download.svg) - export chain. During export, you can adjust the data to be downloaded using the following checkboxes in the dialog window. All checkboxes are unchecked by default:
	- **Export related sub-chains** - if selected, system will also export the whole tree of chains, that are connected via [Chain Call](1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md) and [Chain Trigger](1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger.md) elements, sub-chains, selected as failure handling option on "Failure Response Mapping" tab for [HTTP Trigger](1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) and sub-chains, selected as the handler for duplicate idempotency keys on the "Idempotency" tab of the relevant trigger.
	- **Export related services** - if selected, system will also export services and specifications, utilized within chains.
	- **Export all common variables** - if selected, system will also export all common variables, utilized within chains.
* ![Send](img/send.svg) - save and deploy the chain.
* ⭾ - show sequence diagram based on the chain.

<u>_VS Code Extension_</u>

* ⭾ - show sequence diagram based on the chain.

### Add Element to the Graph

To add a new element, find the suitable one from the left library panel and then drag it to the graph space.

### Place Element to the Containers

To put the element into containers (e.g. [swimlanes](1__QIP_Elements_Library/8__Grouping/1__Swimlane/swimlane.md) and container-like element as [Loop](1__QIP_Elements_Library/1__Routing/8__Loop/loop.md), [Split](1__QIP_Elements_Library/1__Routing/4__Split/split.md), etc.), simply drag the element from the graph or element table to the container.

### Connect Elements

To connect the elements simply drop one element on another one or hover the mouse on the white dot (placed on the right border of the one element), click it and drag the connection line to the target element.

### Edit Element

To edit a particular element double-click on the element.


### Delete Element or Connection

To delete elements or connections from the configuration graph, select them via lift-click, optionally holding [<b>Ctrl</b>] button for multiple selection, and press [<b>Delete</b>] keyboard button. 

