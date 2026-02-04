# Snapshots (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>

## Description

---
Snapshot represents a chain state in the particular moment. In a nutshell, snapshot is a chain XML representation which could be a basis for chain deployment (chain cannot be deployed in intermediate state). Chain could be reverted to any existing snapshot if required.
## User Interface

---
### Snapshot Table View

After creating a chain, the next important step is to generate a **snapshot** before deployment. A snapshot captures the current state of the chain configuration and ensures version consistency, enabling safe redeployment or rollback if needed.

Once created, a new row appears in the snapshot list displaying the following parameters:

- **Name**: A user-defined identifier for the snapshot. By default, it follows a versioning format such as _V1_ , _V2_ , etc., but can be edited as needed.
- **Labels**: Tags used for categorization or filtering.
- **Created By**: The user who initiated the snapshot creation.
- **Created At**: The exact date and time when the snapshot was created.
- **Modified By**: The user who last updated the snapshot (if any changes were made).
- **Modified At**: The date and time of the latest modification.
- **Actions**: A set of operations that can be performed on the snapshot.

Available actions include:

- **Delete Snapshot** <img src="docs/01__Chains/2__Snapshots/img/delete.svg" width="20" height="20">: Removes the selected snapshot from the system.
- **Revert to <img src="docs/01__Chains/2__Snapshots/img/rollback.svg" width="20" height="20">: Rolls back the chain to the selected snapshot, restoring its configuration to that point in time.
- **Show XML** <img src="docs/01__Chains/2__Snapshots/img/file-text.svg" width="20" height="20">: Displays the internal XML structure of the snapshot, useful for advanced troubleshooting or validation.
- **Show Diagram** ⭾ : Visualizes the chain workflow as it was at the time of the snapshot, helping users understand its structure without opening the full editor.

For most of the table columns there is special context menu. To open it, click on the column name. The following functions are available:
* <img src="docs/01__Chains/2__Snapshots/img/caret-up.svg" width="20" height="20"> - click to sort ascending.
* <img src="docs/01__Chains/2__Snapshots/img/caret-down.svg" width="20" height="20"> - click to sort descending.
* <img src="docs/01__Chains/2__Snapshots/img/filter.svg" width="20" height="20"> - filtering.

### Create Snapshot

To create a snapshot:

1. Navigate to the **Snapshot** section using the top menu bar.
2. Click on the **Create** button <img src="docs/01__Chains/2__Snapshots/img/plus.svg" width="20" height="20"> in the right-side action menu <img src="docs/01__Chains/2__Snapshots/img/more.svg" width="20" height="20">.

### Delete Snapshot

To delete snapshot(s), mark all suitable rows with ticks in the snapshot table view and click <img src="docs/01__Chains/2__Snapshots/img/delete.svg" width="20" height="20"> on control panel or select "**Delete**" option in actions menu for each row. 

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    <b>Note:</b><br>
    Besides the UI option for manual deletion, a scheduled task automatically deletes snapshots older that the configured interval <i>(default is set to 14 days)</i>. Once deleted, these snapshots cannot be restored.
</div>
### Revert Chain to the Particular Snapshot

To revert chain to the particular version, select **"Revert to"** option in actions menu. After this a popup with the confirmation will be opened. Then the graph will be shown with all the information and the elements that were saved in the chosen snapshot.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
In case there are unsaved changes in the chain and revert has been requested - the snapshot with all unsaved changes will be automatically created (saved) before reverting to the previous version .
</div>

### Open Snapshot Sequence Diagram

To build the sequence diagram by particular snapshot data, select "**Show Diagram**" option in actions menu. This will open new pop-up window with sequence diagram.

Sequence diagram could be exported via button **"Export"** with 3 output formats supported: **SVG**, **Mermaid** or **PlantUML**.