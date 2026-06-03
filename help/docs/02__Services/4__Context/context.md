# Context Services
## Description

---
Context Services are being used as a temporary storage of chain contexts. Chain context lifetime is limited and configurable, please check [Context Storage](../../01__Chains/1__Graph/1__Elements_Library/4__Services/1__Context_Storage/context_storage.md) to get more details.

> ⚠️ **Warning:** Context services **shall NOT be used** to store/manage sensitive data.

## User Interface

---

### View Context Services

**`⛔ Not available via VS Code extension`**

Table with **Context** services is accessible by navigating to **Services** → **Context** tab. Next columns and elements are available for the table:

- **Name** - clickable name of the service or specification group. When clicked, system navigates to respective entity.
- **Labels** - list of colored labels of the service, specification group or specification, unique within particular entity of each type.
- **Used By** - shows the chains using this service.
- **Created At** - datetime of entity creation (hidden by default).
- **Created By** - shows the user, who created an entity (hidden by default).
- **Modified At** - datetime of entity modifying (hidden by default).
- **Modified By** - shows the user, who modified an entity (hidden by default).
- **Actions menu** - list of operations, accessed via ![more](img/more.svg) menu under each service. Contains next operations:
  - **Edit** ![edit](img/edit.svg) - opens pop-up to change service name, description or set of **custom** labels.
  - **Delete** ![delete](img/delete.svg) - deletes entity.
  - **Export** ![cloud-download](img/cloud-download.svg) - allows to export the entity.
- **Control panel** - panel, placed on top of the table. Provides next capabilities:
  - **Search field** - search box, provides ability to find respective data in the table.
  - ![filter](img/filter.svg) - opens filter pop-up.
  - ![setting](img/setting.svg) - opens pop-up with table properties that allows to adjust visibility and sequence of columns except **Name**.
  - ![cloud-download](img/cloud-download.svg) - exports the service.
  - ![cloud-upload](img/cloud-upload.svg) - opens pop-up for service import.
  - ![plus](img/plus.svg) - provides ability to add new service.

### View Parameters
When service is clicked, system shows Parameters tab with the following information:
- **Name** - mandatory service name.
- **Description** - description of service.
- **Labels** - list of colored labels of the service, specification group or specification, unique within particular entity of each type.

For <ins>Web UI</ins> there are some additional information:

- **Created** - datetime of entity creation.
- **Modified** - datetime of entity modifying.

### Add Context Service

**`⛔ Not available via VS Code extension`**

To add new context service, click **"Create"** button marked with ![plus](img/plus.svg).  Specify service name, description and labels on a newly opened pop-up and then click **"Save"**. System opens new window with the **"Parameters"** tab:
- **Name** - mandatory service name.
- **Description** - description of service.
- **Service type** - types of service.

Specify the required fields and click **"Save"**. Notification about successful saving means that context service is added to the list of context services.

### Import Service(s)

**`⛔ Not available via VS Code extension`**

To import the service(s), click the icon ![cloud-upload](img/cloud-upload.svg), drag and drop **.zip** file into import area or click **"browse"** link and select **single** file with respective format from the explorer menu. When appropriate file is added to the window, click **"Import"** button to start the import process. During the import, system follows next logic:
- Verify Import Instructions, saved in the system. Proceed with the step below only if they exist:
  - Fetch the list of service ids with **ignore** action and skip import process for them. Find more details about Import Instructions in the respective article: [Import Instructions](../../03__Admin_Tools/4__Import_Instructions/import_instructions.md).
- Find existing services by ids from import archive:
  - If system already has entities with ids, specified in import archive:
    - Merge data from archive, including **custom labels**, into existing entities.
    - **Technical labels** are going to be removed from existing entities if they are updated as a part of import process.
> ℹ️ **Note:** If import is done as a part of deployment process or it is initiated directly via API with **corresponding headers**, the current set of technical labels is **always overridden** by the values, received from import archive. This might lead to technical labels to be removed from existing entities if imported file has no corresponding technical labels for them.
- Otherwise, if entities, that are being imported, don't exist, they are going to be created with the next specific: for all new services system will increment standard UUID, so it is possible to operate with it in order to maintain services uniqueness.

When import is completed, system displays import result table with the following columns:
- **Name** - name of the service participated in import operation.
- **Status** - import status for particular service. Possible values:
  - **Created** - new service is successfully imported.
  - **Updated** - imported data from archive is successfully merged with existing one for particular service with matched ID.
  - **Ignored** - service is ignored during the import.
  - **Error** - service import failed.
- **Message** - additional import message if it is available.

### Export Service(s)

**`⛔ Not available via VS Code extension`**

System allows exporting service. There are two possible ways to export service(s):
- From **"Context Services"** page - mark specific services with checkboxes and click ![cloud-download](img/cloud-download.svg) (Export).
- From exact service page - simply click ![cloud-download](img/cloud-download.svg) (Export) from the action menu ![more](img/more.svg).
