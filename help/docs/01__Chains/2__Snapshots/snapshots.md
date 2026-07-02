# Snapshots

> ⛔️ This functionality is not available via the VS Code Extension.

## Description

---
Snapshot represents a chain state in the particular moment. In a nutshell, snapshot is a chain XML representation which could be a basis for chain deployment (chain cannot be deployed in intermediate state). Chain could be reverted to any existing snapshot if required.
## User Interface

---
### Snapshot Table View
There is a **"Snapshots"** tab, available for each particular chain. By navigating to this tab user will be presented with a table, that contains next info and control elements:
- **Name** - snapshot's name. First snapshot is automatically named "**V1**". Every subsequent snapshot will be named with incremented number (**V2**, **V3**, etc).
    > ℹ️ **Note**: To **change** snapshot's **current version name**, hover the mouse on the version name of suitable snapshot, click on it, type new version name and click **`Enter`**.
- **Labels** - list of colored snapshot labels, unique within particular snapshot. It might contain **custom** labels, entered on the snapshot by user via Qubership Integration Platform UI or **technical** labels, populated as part of the deployment via Samples Repository. **Custom** labels can be added or removed clicking on the row respectively. **Technical** labels cannot be updated manually.
- **Created By** - username of snapshot creation.
- **Created At** - the datetime of snapshot creation.
- **Modified By** - username of snapshot modification.
- **Modified At** - the datetime of snapshot modification.
- **Actions menu** - list of operations, accessed via ![more](img/more.svg) menu. Contains next operations:
  - ![delete](img/delete.svg) **Delete** - deletes snapshot(s).
  - ![rollback](img/rollback.svg)**Revert to** - reverts to the selected snapshot version.
  - ![file-text](img/file-text.svg)**Show XML** - opens pop-up with XML definition for the snapshot.
  - ![column-width](img/column-width.svg) **Show Diagram** - opens snapshot sequence diagram.
- **Control panel** - panel, placed on the top of the table. Provides next capabilities:
  - **Search snapshots** - search box, provides ability to find respective data in the table. To find a particular snapshot use search field at the top of the screen and a lens icon ![search](img/search.svg).
  - ![plus](img/plus.svg) - allows to create new snapshot.
  - ![delete](img/delete.svg) - deletes the snapshot(s), selected via checkbox.
  - ![diff](img/diff.svg) - compares selected snapshots.
  - ![setting](img/setting.svg) - opens pop-up with table properties that allows adjusting visibility and order of the columns.


### Create Snapshot
To create snapshot, click button ![plus](img/plus.svg). If chain graph is valid, snapshot will be created.

### Delete Snapshot
To delete snapshot(s), mark all suitable rows with ticks in the snapshot table view and click ![delete](img/delete.svg) on control panel or select "**Delete**" option in actions menu for each row.

> ℹ️ **Note**: Besides the UI option for manual deletion, a scheduled task automatically deletes snapshots older that the configured interval *(default is set to 14 days)*. Once deleted, these snapshots cannot be restored.


### Revert Chain to the Particular Snapshot
To revert chain to the particular version, select **"Revert to"** option in actions menu. After this a popup with the confirmation will be opened. Then the graph will be shown with all the information and the elements that were saved in the chosen snapshot.

> ℹ️ **Note**: In case there are unsaved changes in the chain and revert has been requested - the snapshot with all unsaved changes will be automatically created (saved) before reverting to the previous version.


### Open Snapshot Sequence Diagram
To build the sequence diagram by particular snapshot data, select "**Show Diagram**" option in actions menu. This will open new pop-up window with sequence diagram.

Sequence diagram could be exported via button **"Export"** with 3 output formats supported: **SVG**, **Mermaid** or **PlantUML**.

#### Compare Snapshots
Mark two snapshots with checkboxes, click ![diff](img/diff.svg) button to open widget with two comparison areas and supplementary elements:

- **Snapshot Name** - dropdown with snapshot versions, available for given chain. When a comparison window is requested, while only a single snapshot is marked with a checkbox, the system compares it with current state of the chain.
- Switcher **Graph/Table/Text** - allows to switch between three different comparison views.
- **![caret-up](img/caret-up.svg) (Previous change)** and **![caret-down](img/caret-down.svg) (Next change)** - navigate to the previous or next detected difference. The corresponding element and its changed property are selected in the comparison areas, and the property details are displayed above the graphs.

Comparison could be done in three different views, that could be switched anytime:
- **Graph View** - Default view for comparison widget. When this type of view is selected, comparison areas show configuration graphs, compiled on the basis of selected snapshots. Next tools are available:
  - ![plus](img/plus.svg) ![minus](img/minus.svg) - zoom in /out the graph.
  - ![expand](img/expand.svg) - fit view.
  - ![rotate-right](img/rotate-right.svg) - changes graphs orientation from vertical to horizontal and vice versa.
  - ![arrows-alt](img/arrows-alt.svg) ![shrink](img/shrink.svg) - allows to open widget in full screen and collapse it back.

  Graphs' elements are marked according to the found differences:
   - **Identical (grey)** - no differences were found in the element or dependency.
   - **Changed (yellow)** - the element exists in both chain versions, but its properties differ.
   - **Removed (red)** - the element or dependency does not exist in the compared chain version.
   - **Created (green)** - a new element or dependency exists in the compared chain version.

Clicking the element in one area makes another area also selecting it, allowing to quickly find comparable elements. Double-clicking the element opens another window with text-based comparator, complied on the basis of the elements' data.

- **Table View** - Select **Table** in the **Graph/Table/Text** switcher to display the comparison results as a table. Each row represents a detected difference. The table contains the following columns:

  - **Type** - type of the changed entity.
  - **Compared snapshot versions** - two columns named after the compared snapshots. Each column shows the data from the corresponding chain version:
    - **Element** - element whose configuration differs. Click the element name to open its properties in a new browser window.
    - **Name** - name of the changed property.
    - **Value** - property value in the corresponding chain version.

  Select a row using the radio button to focus on a particular difference. Use the **Previous change** and **Next change** arrows to move between the detected differences.

- **Text View** - Mark two snapshots with checkboxes, click ![diff](img/diff.svg) button to open comparison widget and then use **Graph/Table/Text** switcher to change the view option to "**Text**". Compare areas in this view contain text representation of the chains and per line differences, highlighted in next color:
  - **Red** - properties don't exist in the compared chain version.
  - **Green** - new properties exist in the compared chain version.

Text view is also accessible via double-clicking the element while working with graph view of comparator.