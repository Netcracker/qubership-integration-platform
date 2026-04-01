# Snapshots [Web UI only]

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>

## Description

---
Snapshot represents a chain state in the particular moment. In a nutshell, snapshot is a chain XML representation which could be a basis for chain deployment (chain cannot be deployed in intermediate state). Chain could be reverted to any existing snapshot if required.
## User Interface

---
### Snapshot Table View

There is a **"Snapshots"** tab, available for each particular chain. By navigating to this tab user will be presented with a table, that contains next info and control elements:
- **Name** - snapshot's name. First snapshot is automatically named "**V1**". Every subsequent snapshot will be named with incremented number (**V2**, **V3**, etc).
    >**ℹ️Note**: To **change** snapshot's <b>current version name</b>, hover the mouse on the version name of suitable snapshot, click on it, type new version name and click <b><code>Enter</code></b>.
* **Labels** - list of colored snapshot labels, unique within particular snapshot. It might contain **custom** labels, entered on the snapshot by user via Qubership Integration Platform UI or **technical** labels, populated as part of the deployment via Samples Repository. **Custom** labels can be added or removed clicking on the row respectively. **Technical** labels cannot be updated manually.
- **Created By** - username of snapshot creation.
- **Created At** - the datetime of snapshot creation.
- **Modified By** - username of snapshot modification.
- **Modified At** - the datetime of snapshot modification.
- **Actions menu** - list of operations, accessed via ![20](img/more.svg) menu. Contains next operations:
	- ![Delete|20](img/delete.svg) **Delete** - deletes snapshot(s).
	- ![Rollback|20](img/rollback.svg)**Revert Snapshot** - reverts to the selected snapshot version.
	- ![File text|20](img/file-text.svg)**Show XML** - opens pop-up with XML definition for the snapshot.
	- ⭾ **Show Diagram** - opens snapshot sequence diagram.
- **Control panel** - panel, placed on the top of the table. Provides next capabilities:
	- ![20](img/plus.svg) - allows to create new snapshot.
	- ![20](img/delete.svg) - deletes the snapshot(s), selected via checkbox.
	- ⇄ - compares selected snapshots.


### Create Snapshot

To create snapshot, click button ![20](img/plus.svg). If chain graph is valid, snapshot will be created.

### Delete Snapshot

To delete snapshot(s), mark all suitable rows with ticks in the snapshot table view and click ![Delete|20](img/delete.svg) on control panel or select "**Delete**" option in actions menu for each row. 

>**ℹ️Note**: Besides the UI option for manual deletion, a scheduled task automatically deletes snapshots older that the configured interval *(default is set to 14 days)*. Once deleted, these snapshots cannot be restored.


### Revert Chain to the Particular Snapshot

To revert chain to the particular version, select **"Revert to"** option in actions menu. After this a popup with the confirmation will be opened. Then the graph will be shown with all the information and the elements that were saved in the chosen snapshot.

>**ℹ️Note**: In case there are unsaved changes in the chain and revert has been requested - the snapshot with all unsaved changes will be automatically created (saved) before reverting to the previous version.


### Open Snapshot Sequence Diagram

To build the sequence diagram by particular snapshot data, select "**Show Diagram**" option in actions menu. This will open new pop-up window with sequence diagram.

Sequence diagram could be exported via button **"Export"** with 3 output formats supported: **SVG**, **Mermaid** or **PlantUML**.