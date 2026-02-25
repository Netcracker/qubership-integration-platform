# Context Services
## Description

---
Context Services are being used as a temporary storage of chain contexts. Chain context lifetime is limited and configurable, please check [Context Storage](../../01__Chains/1__Graph/1__QIP_Elements_Library/4__Services/1__Context_Storage/context_storage.md) to get more details.

>⚠️**Warning**: Context services **shall NOT be used** to store/manage sensitive data.

## User Interface

---

### View Context Services

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

Table with **Context** services is accessible by navigating to **Services** → **Context** tab. Next columns and elements are available for the table:

- **Name** - clickable name of the service or specification group. When clicked, system navigates to respective entity.
- **Protocol** - service's integration protocol. Possible values: **_http, soap, kafka, amqp, graphql, grpc_**. Value for this parameter will be propagated from the firstly imported API specification. There is no ability to upload API specifications with another protocol after that.
- **Status** - API Specification status. 
- **Source** - specifies the way specification was created. Possible values:
	- **Manual** - uploaded manually.
	- **Discovered** - added as the result of service discovery. This is only applicable to Inner Cloud Services.
- **Labels** - list of colored labels of the service, specification group or specification, unique within particular entity of each type.
- **Created When** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
* **Actions menu** - list of operations, accessed via ![More](img/more.svg) menu under each service. Contains next operations:
	- **Edit** ![Edit](img/edit.svg) - opens pop-up to change service name, description or set of **custom** labels.
	- **Delete** ![Delete](img/delete.svg) - deletes entity.
	- **Export** ![Upload](img/cloud-upload.svg) - allows to export the entity.

In general at the right down side only one operation is available under ![More](img/more.svg) button:
* ![Plus](img/plus.svg) - Create Service.
* ![Upload](img/cloud-upload.svg) - Upload Service.
* ![Download](img/cloud-download.svg) - Download Selected Services.

### View Parameters

When service is clicked, system shows Parameters tab with the following information:
* Name - mandatory service name.
* Description - description of service.

For _Web UI_ there are some additional information:

* Created - datetime of entity creation.
* Modified - datetime of entity modifying.

### Add Context Service

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To add new context service, click **"Create"** button marked with ![Plus](img/plus.svg) from the ![More](img/more.svg) button. Specify service name, description and labels on a newly opened pop-up and then click **"Create"**. System opens new window with the **"Parameters"** tab:
- **Name** - mandatory service name.
- **Description** - description of service.
- **Service type** - types of service.

Specify the required fields and click **"Save"**. Notification about successful saving means that implemented service is added to the list of implemented services.

### Import Service(s)

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To import the service(s), click the icon ![Upload](img/cloud-upload.svg), drag and drop **.zip** file into import area or click **"browse"** link and select **single** file with respective format from the explorer menu. When appropriate file is added to the window, click **"Import"** button to start the import process. During the import, system follows next logic:
- Verify Import Instructions, saved in the system. Proceed with the step below only if they exist:
  - Fetch the list of service ids with **ignore** action and skip import process for them. Find more details about Import Instructions in the respective article: [Import Instructions](../../03__Admin_Tools/4__Import_Instructions/import_instructions.md).
- Find existing services by ids from import archive:
  - If system already has entities with ids, specified in import archive:
    - Merge data from archive, including **custom labels**, into existing entities.
    - **Technical labels** are going to be removed from existing entities if they are updated as a part of import process.
>ℹ️**Note:** If import is done as a part of deployment process or it is initiated directly via API with **corresponding headers**, the current set of technical labels is **always overridden** by the values, received from import archive. This might lead to technical labels to be removed from existing entities if imported file has no corresponding technical labels for them.
<ul>
  <ul>
    <li>Otherwise, if entities, that are being imported, don't exist, they are going to be created with the next specific: for all new services system will increment standard UUID, so it is possible to operate with it in order to maintain services uniqueness.</li>
  </ul>
</ul>

When import is completed, system displays import result table with the following columns:
- **Name** - name of the service participated in import operation.
- **Status** - import status for particular service. Possible values:
	- **Created** - new service is successfully imported.
	- **Updated** - imported data from archive is successfully merged with existing one for particular service with matched ID.
	- **Ignored** - service is ignored during the import.
	- **Error** - service import failed.
- **Message** - additional import message if it is available.

### Export Service(s)

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

System allows exporting service. There are two possible ways to export service(s):
- From **"Context Services"** page - mark specific services with checkboxes and click ![Download](img/cloud-download.svg) (Export).
- From exact service page - simply click ![Download](img/cloud-download.svg) (Export) from the action menu ![More](img/more.svg).
