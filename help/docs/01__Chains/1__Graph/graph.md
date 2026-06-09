# Graph
## Description

---
**Graph** is a work environment, presented in a "blueprint-like" way, that provides ability to connect multiple chain elements in particular order to form an integration chain and solve specific integration task.

## Process Initialization

---
-	In <ins>Web UI</ins>: user is able to reach graph via simply clicking on any particular chain (to open it's detailed configuration).
-	In <ins>VS Code Extension</ins>: Users can open a chain in one of the following ways:
    - Find the QIP panel in the lower-left corner of VS Code, expand the Chains section, and double-click the desired chain. The corresponding graph will then be displayed.
    - In the Explorer view, find the VS Code Extension section and expand it. Find the required chain by its ID, expand it if necessary, and double-click it to open the corresponding graph.


## User Interface

---
On the "**Graph**" tab user can view and edit a particular chain by adding, updating and removing the elements and connections between them.

> ℹ️ **Note:** When configuration graph is opened, system reviews the chain to detect deprecated elements:
> - If **only simple (non-container) deprecated elements are detected**, the system displays a warning message stating they may be removed in future releases.
> - If **deprecated container elements (the presence of deprecated simple elements has no impact) are detected** in the chain, the system recommends migrating them to their latest versions.
> After confirmation, the system first attempts to save the current chain state as a new snapshot and then updates the chain with the latest versions of container elements. A notification message will indicate the migration process results.
> - If **overridden chain** is opened, the system displays notification panel explaining that the current chain can't be deployed with a reference to the chain that overrides it.
### Tool Panel

Please find the description for all available tools below:

- ![plus](img/plus.svg) - zoom in configuration graph.
- ![minus](img/minus.svg) - zoom out configuration graph.
- ![expand](img/expand.svg) - fit view.
- ![rotate-right](img/rotate-right.svg) - change graph orientation (vertical/horizontal).
- ![arrows-alt](img/arrows-alt.svg) - expand all existing containers in graph.
- ![shrink](img/shrink.svg) - collapse all existing containers in graph.
- ![insert-row-right](img/insert-row-right.svg) - expand/hide right panel. The panel is indented to edit chain graph by its text representation.
- ![insert-row-left](img/insert-row-left.svg) - expand/hide left panel.
- ![fullscreen](img/fullscreen.svg) ![Exit full screen icon](img/fullscreen-exit.svg) - switch between full screen and collapsed process view. **`⛔ Not available via VS Code extension`**

### General Actions
The next actions are available upper right corner:

#### <ins>Web UI</ins>
* ![column-width](img/column-width.svg) - show sequence diagram based on the chain.
* ![file](img/file.svg) - generate DDS.
* ![cloud-download](img/cloud-download.svg) - export chain. During export, you can adjust the data to be downloaded using the following checkboxes in the dialog window. All checkboxes are unchecked by default:
  - **Export related sub-chains** - if selected, system will also export the whole tree of chains, that are connected via [Chain Call](1__Elements_Library/1__Routing/6__Chain_Call/chain_call.md) and [Chain Trigger](1__Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger.md) elements,
  sub-chains selected as failure handling option on "Failure Response Mapping" tab for [HTTP Trigger](1__Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) and sub-chains selected as the handler for duplicate idempotency keys on the "Idempotency" tab of the relevant trigger.
  - **Export related services** - if selected, system will also export services and specifications, utilized within chains.
  - **Export all common variables** - if selected, system will also export all common variables, utilized within chains.
* ![send](img/send.svg) - save and deploy the chain.


#### <ins>VS Code Extension</ins>
* ![column-width](img/column-width.svg) - show sequence diagram based on the chain.

### Add Element to the Graph
To add a new element, find the suitable one from the left library panel and then drag it to the graph space.

### Place Element to the Containers
To put the element into containers (e.g. [swimlanes](1__Elements_Library/8__Grouping/1__Swimlane/swimlane.md) and container-like element as [Loop](1__Elements_Library/1__Routing/8__Loop/loop.md), [Split](1__Elements_Library/1__Routing/4__Split/split.md), etc.), simply drag the element from the graph or element table to the container.

### Connect Elements
To connect the elements simply drop one element on another one or hover the mouse on the white dot (placed on the right border of the one element), click it and drag the connection line to the target element.

### Edit Element
To edit a particular element, either double-click the element or choose "**Edit**" option from the context menu, that could be requested via right-click on the element. It is also possible to control element's window size with ![arrows-alt](img/arrows-alt.svg) and ![shrink](img/shrink.svg) buttons.

>ℹ️ **In‑place element name editing**
>
>To quickly change an element's name without switching tabs on the configuration window: hover over the name area (![edit](img/edit.svg) appears), click it, edit the name, and then click outside the input field.

### Copy Element
To copy a particular element, right-click it and choose "**Copy**" option from context menu. Then click right mouse on the space area and choose "**Paste**".

### Delete Element or Connection
To delete elements or connections from the configuration graph, select them via lift-click, optionally holding [**Ctrl**] button for multiple selection, and press [**Delete**] keyboard button.
Elements may also be deleted with the "**Delete**" option from the context menu, accessed by right-clicking the element.
For container-type elements, that have multiple elements in them, system will request to confirm delete process via dialog menu.

### View Table with Added Elements
To see all elements, currently added to the configuration graph, click ![insert-row-right](img/insert-row-right.svg) - open right panel with the elements using in the chain. Clicking the element in the list will immediately show it on the graph. Double-click will open this element in edit mode.

### View Table with Exchange Properties
To open window with a table, that contains all utilized exchange properties in the current chain, click ![insert-row-right](img/insert-row-right.svg) to open right panel and then click ![menu-unfold](img/menu-unfold.svg)tab. Each property in the list will have numeric indicator of elements, where this property is being utilized, type (if available) and labels, which help to identify property origin:
- P - common property, added via script, mapper, etc.
- H - property, built from header or query parameters.

When property record is expanded, it shows related element's information as well as property usage (e.g. Set or Get). Clicking the element in the list will show it on the graph. Double-click will open this element in edit mode on respective tab. Search and filter functionality are also available for this table to quickly find respective data.

> ℹ️ **Note:** Please note, that this view might not contain property usage record when property participates in complex scenarios (e.g. when property is declared and then fetched via "GET" as a variable).

### Open Chain's Text View
**`⛔ Not available via VS Code extension`**

Chain is also available in a text view, that could be used to compare one chain with another, find specific data or debugging. To open text view, simply click ![insert-row-right](img/insert-row-right.svg) to open right panel and then select respective tab with ![file](img/file.svg) icon.

### Generate Document by Template
**`⛔ Not available via VS Code extension`**

To initiate document generation, click ![file](img/file.svg) **"Generate DDS"** on control panel, select desired template from the list, specify document name and click "**Generate**". System will show preview screen, where it is possible to get familiar with the finalized view of the document. Click "**Download**" to get the archive, that contains design document in .md format and other supplementary files, like pictures, etc. Data from the archive can be further processed by Markdown editors or processors to build proper human-readable document, presented on preview step.

## Data Storage

---
-	In <ins>Web UI</ins>: chain configuration is being saved in the QIP catalog database in a real-time manner.
-	In <ins>VS Code Extension</ins>: chain configurations in VS Code are saved locally under a project folder or workspace directory configured by the user on file system of respective machine. 
