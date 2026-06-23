# MCP Services
## Description

---
**MCP Services** define services that expose chains as tools through the **Model Context Protocol (MCP)**. MCP services are used in [MCP Trigger](../../01__Chains/1__Graph/1__Elements_Library/6__Triggers/10__MCP_Trigger/mcp_trigger.md) elements to group and publish configured MCP tools for MCP clients.

Each MCP service has a unique identifier, common metadata, labels, and instructions that describe how the service should be used by MCP clients.

## User Interface

---

### View MCP Services
<ins>Web UI</ins>

Table with MCP services is accessible by navigating to **Services** -> **MCP** tab. Next columns and elements are available for the table:

- **Name** - clickable name of the MCP service. When clicked, system navigates to respective entity.
- **Identifier** - unique identifier of the MCP service.
- **Labels** - list of colored labels of the MCP service, unique within particular entity.
- **Used by** - list of chains where the MCP service is used.
- **Created At** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Modified At** - datetime of entity modifying.
- **Modified By** - shows the user, who modified an entity.
- **Actions menu** - list of operations, accessed via ![more](img/more.svg) menu under each service. Contains next operations:
  - **Delete** ![delete](img/delete.svg) - deletes entity.
  - **Export** ![cloud-download](img/cloud-download.svg) - allows to export the entity.
- **Control panel** - panel, placed on top of the table. Provides next capabilities:
  - **Search services** - search box, provides ability to find respective data in the table.
  - ![filter](img/filter.svg) - opens filter pop-up.
  - ![setting](img/setting.svg) - opens pop-up with table properties that allows to adjust visibility and sequence of columns except **Name**.
  - ![cloud-download](img/cloud-download.svg) - exports the service.
  - ![cloud-upload](img/cloud-upload.svg) - opens pop-up for service import.
  - ![plus](img/plus.svg) - provides ability to add new service.

<ins>VS Code Extension</ins>

All services created using VS Code Extension appears under "Services" folder. This folder can be located by expanding "QIP" folder in the left bottom.

### Add MCP Service
<ins>Web UI</ins>

To add new MCP service, click **"Create service"** button at the top right of the screen. Specify service name and description on a newly opened pop-up and click **"Create"** button.

System opens new window with **Common Parameters** section.

<ins>VS Code Extension</ins>

To create any service using VS Code Extension, follow the steps outlined below:

1. Open "VS Code Extension" in Visual Studio Code.
2. In the left bottom find QIP section and expand it.
3. Near the "Services" folder click on appearing button "QIP Create service".
4. At the top of Visual Studio Code enter the name of the chain, select the type of the service, enter the identifier, enter some description and click Enter. Next, it opens "Common Parameters" tab of the created service.

### View Common Parameters
Common Parameters section contains the following information:

| Parameter    | Mandatory | Data Type | Description                                                                                                                                                               | Sample                         |
| ------------ | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------ |
| Name         | M         | String    | Name of the MCP service.                                                                                                                                                  | New MCP service                |
| Description  | O         | String    | Free text field, that contains description of the MCP service.                                                                                                            | Service for customer MCP tools |
| Labels       | O         | List      | List of colored labels of the MCP service, unique within particular entity. Custom labels can be added or removed clicking on the field.                                  | customer                       |
| Identifier   | M         | String    | Unique identifier of the MCP service. This identifier is used when selecting MCP services in [MCP Trigger](../../01__Chains/1__Graph/1__Elements_Library/6__Triggers/10__MCP_Trigger/mcp_trigger.md). | customer-mcp-service          |
| Instructions | O         | String    | Instructions that describe how MCP clients should use this service.                                                                                                       | Use this service to access customer-related tools. |

For <ins>Web UI</ins> there are some additional information:

- **Created** - datetime of entity creation.
- **Modified** - datetime of entity modifying.

Specify the required fields and click **"Save"**. Notification about successful saving means that MCP service is added to the list of MCP services.

### Import MCP Service(s)

**`⛔ Not available via VS Code extension`**

To import the service(s), click the icon ![cloud-upload](img/cloud-upload.svg), drag and drop **.zip** file into import area
or click **"browse"** link and select **single** file with respective format from the explorer menu.
When appropriate file is added to the window, click **"Import"** button to start the import process.

When import is completed, system displays import result table with the following columns:

- **Name** - name of the service participated in import operation.
- **Status** - import status for particular service. Possible values:
  - **Created** - new service is successfully imported.
  - **Updated** - imported data from archive is successfully merged with existing one for particular service with matched ID.
  - **Ignored** - service is ignored during the import.
  - **Error** - service import failed.
- **Message** - additional import message if it is available.

### Export MCP Service(s)

**`⛔ Not available via VS Code extension`**

System allows to export MCP service with its metadata, labels, identifier, and instructions. From **"MCP Services"** page, mark specific services with checkboxes and click ![cloud-download](img/cloud-download.svg) **"Export"**. Or simply click this button to export all services at once after confirmation.

### Constraints

---

- **Identifier** must be unique within MCP services.
- **Technical** labels cannot be imported via UI or exported.
