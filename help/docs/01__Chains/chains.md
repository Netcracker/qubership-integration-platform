# Chains

> ⛔️ This functionality is not available via the VS Code Extension.

## Description

---
Chain is an integration configuration which consist of Apache Camel (or customized) modules. Each chain is indented to perform particular integration task.
Chain can be triggered by any external consumer, so chain configuration starts from some trigger (HTTP Trigger, Kafka Trigger, etc.).
When the chain configuration was completed, it should be deployed at least on one [Engine Domain](../03__Admin_Tools/1__Domains/domains.md) (otherwise, the chain cannot be triggered).
## User Interface

---
### Chains and Folders Table View
Screen shows the table of chains (marked with icon ![20](img/file.svg)) and chain folders (marked with icon ![16](img/folder.svg)). To see all the chains & folders under the particular folder, you can click ![20](img/right.svg) icon near folder name. Next control elements are available on top of the table:
- **Search field** - search box, provides ability to find respective data in the table. To find a particular chain/folder by a specific feature (case-insensitive) use search field at the top of the screen with the text "Search Chain..." and a lens icon ![14](img/search.svg). Full-text search is applicable by the following data:
  - Chain fields:
    - Chain name
    - Chain ID
    - Chain description
  - Chain elements in [graph](1__Graph/graph.md):
    - Path ([HTTP Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md), [Service Call](1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md))
    - Method ([HTTP Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md), [Service Call](1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md))
    - Topic ([Kafka Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/8__Kafka_Trigger/kafka_trigger.md), [Kafka Sender](1__Graph/1__QIP_Elements_Library/7__Senders/2__Kafka_Sender/kafka_sender.md),
    [AsyncAPI Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/3__AsyncAPI_Trigger/asyncapi_trigger.md), [Service Call](1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md))
    - Exchange ([RabbitMQ Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/6__RabbitMQ_Trigger/rabbitmq_trigger.md), [RabbitMQ Sender](1__Graph/1__QIP_Elements_Library/7__Senders/1__RabbitMQ_Sender/rabbitmq_sender.md),
    [AsyncAPI Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/3__AsyncAPI_Trigger/asyncapi_trigger.md), [Service Call](1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md))
    - Queue ([RabbitMQ Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/6__RabbitMQ_Trigger/rabbitmq_trigger.md), [RabbitMQ Sender](1__Graph/1__QIP_Elements_Library/7__Senders/1__RabbitMQ_Sender/rabbitmq_sender.md),
    [AsyncAPI Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/3__AsyncAPI_Trigger/asyncapi_trigger.md), [Service Call](1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md))
  - Folder name - if search query matches, all content (chains and child folders) under the folder will be shown.
- ![16](img/filter.svg) - opens filter pop-up.
- ![20](img/setting.svg) - opens pop-up with table properties that allows adjusting visibility and order of the columns except **Name**.
- ⇄ - compare selected chains.
- ![20](img/carry-out.svg) - pastes chain or folder.
- ![24](img/send.svg) - opens pop-up for chains redeploy.
- ![20](img/cloud-download.svg) - exports the chain(s).
- ![20](img/cloud-upload.svg) - opens pop-up for chain(s) import. As part of upload/import operation, user is able to additionally select an option to create a snapshot for imported chain or even deploy it to the selected engine as soon import is successfully completed.
- ![20](img/delete.svg) - delete selected chains or folders.

Each **chain** contains the following parameters on table view:
- **Name** - chain name, which is clickable reference to the chain [graph](1__Graph/graph.md).
- **Description** - user description of the chain.
- **Status** - shows chain's deployment status. Possible values:
  - ⚫ **_Draft_** - default chain status, that indicates that chain is not deployed yet.
  - 🔵 **_Progressing_** - chain deployment is in progress on one or multiple engines. If status stays for long period of time, it may indicate that system awaits crucial data, specified within a chain (e.g. classifiers in MaaS).
  - 🔴 **_Failed_** - chain deployment failed on one or multiple engines.
  - 🟢 **_Deployed_** - chain is successfully deployed on all requested engines.
- **Labels** - list of colored chain labels, unique within particular chain. It might contain **custom** labels, entered on the chain by user via Qubership Integration Platform UI or **technical** labels, populated as part of the deployment via Samples Repository. **Technical** labels cannot be updated manually.
- **Modified At** - date of last chain modification.
- **Modified By** - name of the user who modified the chain last.
- **Actions menu** - list of operations, accessed via menu symbol ![20](img/more.svg), contains the list of available operations with the chain:
  - **_Copy link_** - copies chain link to clipboard.
  - _**Edit**_ - opens pop-up to update chain name, description, **custom** labels and additional data, required for DDS generation.
  - _**Export**_ - exports chain from QIP.
  - _**Generate DDS**_ - generates integration design document, based on the chain data.
  - _**Cut**_ - cuts the chain. To paste it, click "paste" ![20](img/carry-out.svg) button, available on top of the screen.
  - _**Copy**_ - copies chain (whole object). To paste copied chain, click "paste" ![20](img/carry-out.svg) button, available on top of the screen.
  - _**Duplicate**_ - duplicates chain. New chain will be duplicated with the  **"- copy"** postfix.
  - _**Delete**_ - deletes chain.


> ℹ️ **Notes:** For most of the table columns there is special context menu. To open it, **click on the column name**. The following functions can be available here:
>
> - Filter (by this parameter's value)
> - Sort Ascending
> - Sort Descending
> - Hide column

**Folder** contains the following parameters on table view:
- **Name** - clickable reference to the page with folder content.
- **Actions menu** - list of operations, accessed via menu symbol ![20](img/more.svg), contains the list of available operations with the folder:
  - **_Create New Folder_** - opens pop-up to add new folder under this one.
  - **_Create New Chain_** - opens pop-up to create chain under the folder.
  - **_Expand All_** - expands all folders (regardless of the nesting level) under the current one.
  - **_Collapse All_** - collapses all child folders and the current one.
  - **_Copy link_** - copies folder link to clipboard. Following the link, you will open the same "**Chains**" page with expanded and highlighted folder.
  - **_Edit_** - opens pop-up to update folder name or description.
  - **_Export_** - exports all the chains under the folder.
  - **_Cut_** - cuts the chain folder (with all folder content). To paste folder, click "paste" ![20](img/carry-out.svg) icon button, available on top of the screen.
  - **_Paste_** - pastes copied chain or folder.
  - _**Delete**_ - deletes folder with all content under it.


> ℹ️ **Notes:**
>
> - You can **move chain/folder** to the folder via drag&drop operation (instead of Cut and Paste). To **move it to the root directory**, drop chain/folder above table headings.
> - If you are trying to open folder with no chains in it, message "No elements in folder" will be presented on the folder card.
> - Mentioned "Chains" window **does not validate** the uniqueness of the names, neither folders nor the chains. Hence, it is possible that multiple chains (or folders) might have the same names.

### Chain Details Side Panel
More chain details are available via **right side panel**. To open it, click on any place in the chain row (except chain name, which leads to [graph](1__Graph/graph.md)). The following information about chain will be available (in read-only mode):

- **Id** - chain identifier.
- **Name** - chain name (same as in the table).
- **Description** - detailed description of the chain, if entered during creation.
- **Labels** - list of chain labels (same as in the table).
- **Overridden By** - clickable reference to the chain that overrides the current one.
- **Overrides** - clickable reference to the chain that is overridden by the current one.
- **Status** - same as in the table.
- **Deployments** - embedded table with details about chain deployments (domains and active engines).
- **Logging settings source** - shows the source of the logging settings: Default (Consul), Custom or Default (Fallback).
- **Sessions logging level** - level of logs for chain sessions: Off, Error, Info or Debug.
- **Log logging level** - shows current log level: Error, Warning or Info.
- **Log payload level** - shows if payload is being logged.
- **DPT events enabled** - shows is DPT events are being sent.
- **Created** - date, time and creator (username) of the chain.
- **Modified** - date, time and user of the last chain modification.

### Create New Chain or Folder
To create a new chain or a folder, click button **"Create"** on the top right of the screen and select either **"New chain"** or **"New folder"** from the list menu.

You will be presented with the window. Fill in the following fields:
For Folders:
- **Name**  - in this mandatory field, you could populate a name for new chain or a folder.
- **Description** - here you could add detailed description for new chain or a folder.
- At the bottom, users can choose to open the newly created folder immediately after submitting it by selecting **Open folder**. Alternatively, they can opt to open it in a **new tab**. If neither option is selected, the system simply returns to the list of chains.

For Chains:
Two tabs appear: **General Info** and **Extended Description**.

In the **General Info** tab:
- **Name**  - in this mandatory field, you could populate a name for new chain or a folder.
- **Labels**  - customizable list of chain labels. To populate new label to the list, specify its value and then press **`Enter`**.
- **Description** - here you could add detailed description for new chain or a folder.
- At the bottom, users can choose to open the newly created chain immediately after submitting it by selecting **Open chain**. Alternatively, they can opt to open it in a **new tab**. If neither option is selected, the system simply returns to the list of chains.

In the **Extended Description** tab:
- Optional fields include _Business Description_, _Assumptions_, and _Out of Scope_.

When all necessary parameters are filled, click **"Submit"** button or use the combination of **`Ctrl+Enter`**.

### Import Chain(s)
To upload the chain(s), click the icon ![20](img/cloud-upload.svg), drag and drop **.zip** or **.yaml** file into import area or click on this area, select the file and click "Next" at the bottom right. The second step allows to specify actions. There are four tabs under one.

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
To export the chain(s), simply select respective rows in the table with checkboxes (use checkbox in table heading if you need to select all/filtered chains) and ![20](img/cloud-download.svg).
When no entities are selected and button clicked, system will attempt to export every chain after receiving a user's confirmation.
During export, you can adjust the data to be downloaded using the following checkboxes in the dialog window. All checkboxes are unchecked by default:

- **Export related sub-chains** - if selected, system will also export the whole tree of chains, that are connected via [Chain Call](1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md) and [Chain Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger.md) elements,
sub-chains selected as failure handling option on "Failure Response Mapping" tab for [HTTP Trigger](1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md)
and sub-chains selected as the handler for duplicate idempotency keys on the "Idempotency" tab of the relevant trigger.
- **Export related services** - if selected, system will also export services and specifications, utilized within chains.
- **Export all common variables** - if selected, system will also export all common variables, utilized within chains.

### Deploy Chain(s)
To deploy desired chains, mark them via checkboxes (use checkbox in table heading if you need to select all/filtered chains) and click ![20](img/send.svg).
System will attempt to deploy every available chain when button is clicked but none of the chains selected.
In both cases, system shows pop-up and requests additional data:

- **Engine Domain** - the engine, selected chains will be deployed on.
- **Snapshot Action** - defines if new snapshot must be deployed. There are two actions available:
  - **Create new** - system will attempt to redeploy the chain with new snapshot.
  - **Reuse latest, otherwise create new** - system will attempt to reuse latest snapshot. If it does not exist - redeploy will be attempted with newly created snapshot.

Confirm selected options and click "**Deploy**" button. System will attempt to deploy selected chains and show notifications.
