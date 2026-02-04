# Chains (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>

## Description


---
Chain is an integration configuration which consist of Apache Camel (or customized) modules. Each chain is indented to perform particular integration task. Chain can be triggered by any external consumer, so chain configuration starts from some trigger (HTTP Trigger, Kafka Trigger, etc.). When the chain configuration was completed, it should be deployed at least on one [[docs/03__Admin_Tools/1__Domains/domains|Engine Domain]] (otherwise, the chain cannot be triggered).
## User Interface

---
### Chains and Folders Table View

Initially at the top of the screen Full text search is available to find necessary chain or folder. The only way to use this search is to enter in the special field some parameters and click on the <img src="docs/01__Chains/img/search.svg" width="20" height="20"> icon.

On the right side near Full test search it is possible to add/remove fields in the table clicking on this icon <img src="docs/01__Chains/img/setting.svg" width="20" height="20"> or to use filter <img src="docs/01__Chains/img/filter.svg" width="20" height="20"> with the following parameters:

* Column - field from the table on which the filter will be applied, all fields from the table are available to choose;
* Condition - consist of 4 conditions: Contains, Does not contain, Starts with, Ends with.
* Value - value of filtering.

 Each filter can be removed (marked with icon <img src="docs/01__Chains/img/delete.svg" width="20" height="20">) or duplicated (marked with icon <img src="docs/01__Chains/img/copy.svg" width="20" height="20">) under this <img src="docs/01__Chains/img/ellipsis.svg" width="20" height="20"> icon.

Additionally it is possible to add one more filter using this <img src="docs/01__Chains/img/plus.svg" width="20" height="20"> or to clear all filters using this <img src="docs/01__Chains/img/clear.svg" width="20" height="20">.

On the screen under Full text search there is a table view showing two groups of elements: chains (marked with icon <img src="docs/01__Chains/img/file.svg" width="20" height="20">) and chain folder (marked with icon <img src="docs/01__Chains/img/folder.svg" width="20" height="20">). To see all chains and folders under one particular folder it is necessary to click on <img src="docs/01__Chains/img/plus.svg" width="20" height="20"> icon near folder name. 

By default, each chain or folder displays the following parameters:

- **Name**: The user-defined identifier for the chain.
- **Status**: Indicates the current stage of the chain:
    - _Draft_: Initial state before deployment.
    - _Processing_: Chain is being deployed.
    - _Deployed_: Chain is active and running in the environment.
    - _Failed_: Deployment of this chain was unsuccessful.
- **Labels**: Tags used for categorization or filtering.
- **Created By**: The user who created the chain.
- **Created At**: Date and time of creation.
- **Modified By**: The user who last edited the chain.
- **Modified At**: Date and time of modifying.

For most of the table columns there is special context menu. To open it, click on the column name. The following functions are available:
* <img src="docs/01__Chains/img/caret-up.svg" width="20" height="20"> - click to sort ascending.
* <img src="docs/01__Chains/img/caret-down.svg" width="20" height="20"> - click to sort descending.
* <img src="docs/01__Chains/img/filter.svg" width="20" height="20"> - filtering.

A set of operations can be performed on chains of folders and marked with <img src="docs/01__Chains/img/more.svg" width="20" height="20"> icon. 
#### Available Actions

- **For Folders** :
    
    - Create New Folder - create of a new folder under existing one.
    - Create New Chain - create of a new chain under existing folder.
    - Expand All / Collapse All - expand and collapse all folders.
    - Copy Link - copy folder link to clipboard.
    - Edit - open pop-up to edit folder name and description.
    - Export - export selected folder.
    - Cut / Paste - cut and paste selected folder with all content.
    - Delete - delete existing folder with all content.
    
- **For Chains** :
    
    - Copy Link - copy chain link to clipboard.
    - Edit - open graph to update the chain.
    - Export - export selected chain.
    - Generate DDS - generate integration design document based on the chain data.
    - Cut / Copy - cut and paste selected chain.
    - Duplicate - duplicate selected chain with number postfix.
    - Delete - delete selected chain.

At the right down there is a floating button marked with !!!. These actions are available:
* <img src="docs/01__Chains/img/file-add.svg" width="20" height="20"> - create chain.
* <img src="docs/01__Chains/img/folder-add.svg" width="20" height="20"> - create folder.
* <img src="docs/01__Chains/img/delete.svg" width="20" height="20"> - delete selected chains and folders.
* <img src="docs/01__Chains/img/cloud-download.svg" width="20" height="20"> - export selected chains.
* <img src="docs/01__Chains/img/cloud-upload.svg" width="20" height="20"> - import chains.
* ⇄ - compare selected chains.
* <img src="docs/01__Chains/img/carry-out.svg" width="20" height="20">  - paste chain or folder.
* <img src="docs/01__Chains/img/send.svg" width="20" height="20"> - deploy selected chains.

### Create New Chain or Folder

To create a new chain, users click on the action menu at the bottom of the screen and select the appropriate option. Two tabs appear: **General Info** and **Extended Description** .

In the **General Info** tab:

- Entering a chain name is mandatory.
- Labels and a short description are optional.

In the **Extended Description** tab:

- Optional fields include _Business Description_, _Assumptions_, and _Out of Scope_.

At the bottom, users can choose to open the newly created chain immediately after submitting it by selecting **Open chain**. Alternatively, they can opt to open it in a **new tab**. If neither option is selected, the system simply returns to the list of chains.

Clicking on any existing chain opens it in a detailed view for further editing or inspection.

To create a new folder, users click on the same action menu at the bottom of the screen and select the appropriate option. The pop-up is appeared with the following fields:
* Name - mandatory;
* Description - optional.
The same option of immediate opening is available for folders.

### Import Chain(s)

To upload the chain(s), click the icon <img src="docs/01__Chains/img/cloud-upload.svg" width="20" height="20">, drag and drop **.zip** or **.yaml** file into import area or click on this area, select the file and click "Next" at the bottom right. The second step allows to specify actions. There are four tabs under one.

#### Chains Tab

This tab contains all chains that are going to be imported.

The first element is a switcher "Validate By Hash". This option may enhance the time of import by comparing hash of any chains with each other. If the hash of one chain is the same, the system will skip to import this chain.

Under this option there is a table of chains with the following parameters:
* Name - name of the imported chain;
* ID - id of the imported chain;
* Domain - the selected domain for deployment;
* Instruction Action - shows the exact instruction for the particular chain. Available only on preview before import process is completed. Possible values:
    - **Ignore** - means that specified entity is going to be ignored during import process.
    - **Override** - means that the chain is going to be overridden by another one.
* Action - allows to select preferable deployment option for imported chain. Possible values:
    - **None** - neither snapshot nor deployment will be created for the chain, but the chain will get all the data from archive and be marked with "Unsaved changes" label.
    - **Snapshot** - imported data is merged and saved under the new snapshot.
    - **Deploy** - imported data is merged and saved under the new snapshot with sequential deployment creation.
* Status - this field is available only after finishing import process and shows the status of imported chain. Available status:
	* **Created** - new chain is successfully imported.
    - **Updated** - imported data from archive is successfully merged with existing one for particular chain with matched ID.
    - **Error** - chain import failed.

#### Services Tab

This tab contains all services that are going to be imported.

On this tab there is a table with the following data:
* Name - name of the service;
* ID - id of the service;
* Status -this field is available only after finishing import process and shows the status of imported services. Available status:
	* **Created** - new service is successfully imported.
    - **Updated** - imported data from archive is successfully merged with existing one for particular service with matched ID.
    - **Error** - service import failed.
    - **No action** - import is skipped for particular service that has been unchecked via checkboxes.

#### Common Variables Tab

This tab contains all common variables that are going to be imported.

The following information are available:
* Name - name of the common variable;
* Value - value of the common variable; 
* Current Value - existing value of the common variable;
* Status - this field is available only after finishing import process and shows the status of imported common variables. Available status:
	* **Created** - new common variable is successfully imported.
    - **Updated** - imported data from archive was successfully merged with existing one for particular common variable with matched name.
    - **Error** - common variable import failed.
    - **No action** - import is skipped for particular common variable that has been unchecked via checkboxes.

#### Import Instructions Tab

The tab contains the full list of entities, that are going to be managed via Import Instructions.
There is a table with the following columns:
* ID - id of the instruction;
* Action - describes the exact instruction given to the specific entity;
* Overridden By - contains Id of the chain that overrides the current one;
* Labels - list of colored technical labels;
* Status - this field is available only after finishing import process and shows the status of imported entities. Available status:
	* **Ignored** - entity is ignored during the import.
    - **Overridden** - chain is successfully overridden by another one.
    - **Deleted** - entity is successfully removed.
    - **Error on Delete** - entity removal failed.
    - **Error on Override** - override process failed.
    - **No action** - specified instruction has not applied during the import.

---
After all actions it is necessary to click on the "import" button at the bottom right. The next step is the processing of import. The user has to wait for it.

The last step is the result of import. In each tab the specified field "Status" is added as a result of import that is described at the end of each table.

### Export Chain(s)

To export the chain(s), simply select respective rows in the table with checkboxes (use checkbox in table heading if you need to select all/filtered chains) and <img src="docs/01__Chains/img/cloud-download.svg" width="20" height="20">. When no entities are selected and button clicked, system will attempt to export every chain after receiving a user's confirmation. During export, you can adjust the data to be downloaded using the following checkboxes in the dialog window. All checkboxes are unchecked by default:

- **Export related sub-chains** - if selected, system will also export the whole tree of chains, that are connected via [Chain Call](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md) and [Chain Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger.md) elements, sub-chains, selected as failure handling option on "Failure Response Mapping" tab for [HTTP Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) and sub-chains, selected as the handler for duplicate idempotency keys on the "Idempotency" tab of the relevant trigger.
- **Export related services** - if selected, system will also export services and specifications, utilized within chains.
- **Export all common variables** - if selected, system will also export all common variables, utilized within chains.



