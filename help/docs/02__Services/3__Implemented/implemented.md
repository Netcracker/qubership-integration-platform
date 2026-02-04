# Implemented Services
## Description

---
**Implemented Services** tab provides capabilities to build a very specific http services with custom schemes, validations, operations, etc. Implemented service API specification could be only used at the start of the chain, hence it is only possible to use it with [[docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger|HTTP Trigger]].

There are 2 options to configure the Implemented Service:
- Implement existing interfaces from **manually imported API specification**, allowing user to move a particular service functionality to the platform. In this case, validation scheme will be **predefined** in swagger file and **cannot** be customized in HTTP Trigger.

- Create an API Specification from previously implemented chain(s) with HTTP Trigger element configured. In this case, validation scheme must be defined **manually**.

## User Interface

---

### View Implemented Services

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

Table with Implemented services is accessible by navigating to **Services** → **Implemented** tab. Next columns and elements are available for the table:

- **Name** - clickable name of the service or specification group. When clicked, system navigates to respective entity.
- **Protocol** - service's integration protocol. Possible values: **_http, soap, kafka, amqp, graphql, grpc_**. Value for this parameter will be propagated from the firstly imported API specification. There is no ability to upload API specifications with another protocol after that.
- **Status** - API Specification status. Possible values:
	- <span style="color:blue; font-size: 30px;">&#8226</span> _**New**_ - initial state of API specification, uploaded manually or imported by service discovery.
	- <span style="color:green; font-size: 30px;">&#8226</span> _**In Use**_ - status indicates that API Specification is utilized within at least one chain.
	- <span style="color:red; font-size: 30px;">&#8226</span> _**Deprecated**_ - this status indicates that such specification is outdated and won't be available for selection in newly added chain elements. Old elements, where specification with this status is already selected may still continue using it.
- **Source** - specifies the way specification was created. Possible values:
	- **Manual** - uploaded manually.
	- **Discovered** - added as the result of service discovery. This is only applicable to Inner Cloud Services.
- **Labels** - list of colored labels of the service, specification group or specification, unique within particular entity of each type.
- **Created When** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Actions menu** - list of operations, accessed via <img src="docs/02__Services/1__External/img/more.svg" width="20" height="20"> menu under each service. Contains next operations:
	- **Edit** <img src="docs/02__Services/3__Implemented/img/edit.svg" width="20" height="20"> - opens pop-up to change service name, description or set of **custom** labels.
	- **Delete** <img src="docs/02__Services/3__Implemented/img/delete.svg" width="20" height="20"> - deletes entity.
	- **Expand All** <img src="docs/02__Services/3__Implemented/img/column-height.svg" width="20" height="20"> - fully expands the entity.
	- **Collapse All** <img src="docs/02__Services/3__Implemented/img/vertical-align-middle.svg" width="20" height="20"> - fully collapses the entity.
	- **Export** <img src="docs/02__Services/3__Implemented/img/cloud-upload.svg" width="20" height="20"> - allows to export the entity.

In general at the right down side only one operation is available under <img src="docs/02__Services/3__Implemented/img/more.svg" width="20" height="20"> button:
* <img src="docs/02__Services/3__Implemented/img/plus.svg" width="20" height="20"> - Create Service.
* <img src="docs/02__Services/3__Implemented/img/cloud-upload.svg" width="20" height="20"> - Upload Service.
* <img src="docs/02__Services/3__Implemented/img/cloud-download.svg" width="20" height="20"> - Download Selected Services.

### View Parameters

When service is clicked, system shows Parameters tab with the following information:
* Name - mandatory service name.
* Description - description of service.
* Protocol - service's integration protocol.
* Type - type of service. Possible values:
	* External;
	* Internal;
	* Implemented.
* Labels - list of colored labels of the service, specification group or specification, unique within particular entity of each type. It might contain **custom** labels, entered by user via Qubership Integration Platform UI or **technical** labels, populated as part of the **deployment via Samples Repository**. Custom labels can be added or removed clicking on the field. **Technical** labels cannot be updated manually.

For _Web UI_ there are some additional information:

* Created - datetime of entity creation.
* Modified - datetime of entity modifying.

### View Specification Groups

* **Name** - clickable name of the specification group or specification. When clicked, system navigates to respective entity.
- **Status** - API Specification status. Possible values:
	- <span style="color:blue; font-size: 30px;">&#8226</span> _**New**_ - initial state of API specification, uploaded manually or imported by service discovery.
	- <span style="color:green; font-size: 30px;">&#8226</span> _**In Use**_ - status indicates that API Specification is utilized within at least one chain.
	- <span style="color:red; font-size: 30px;">&#8226</span> _**Deprecated**_ - this status indicates that such specification is outdated and won't be available for selection in newly added chain elements. Old elements, where specification with this status is already selected may still continue using it.
- **Source** - specifies the way specification was created. Possible values:
	- **Manual** - uploaded manually.
	* **Discovered** - added as the result of service discovery. This is only applicable to Inner Cloud Services.
* **Labels** - list of colored labels of the specification group, unique within particular specification group.
- **Used By** - expand, that contains the list of chains, where specification is being utilized. Each chain name under this expand is clickable and navigates to respective configuration graph.
* **Created When** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Modified When** - datetime of entity modification.
- **Modified By** - shows the user, who modified an entity.

To add new information, click on the button <img src="docs/02__Services/3__Implemented/img/setting.svg" width="20" height="20"> located on the right side. The following field are available:

* **Protocol** - shows what protocol is used.
* **Extended Protocol** - shows special rules for the service protocol.
* **Specification** - the service's instruction.
* **Internal Service Name** - shows internal service name.
- **Method** -  method of the operation, mentioned in the specification.
- **URL** - operation path.

**Actions menu** - list of operations, accessed via <img src="docs/02__Services/3__Implemented/img/more.svg" width="20" height="20"> menu. Contains the following operations:
* <img src="docs/02__Services/3__Implemented/img/down.svg" width="20" height="20"> - Expand.
* <img src="docs/02__Services/3__Implemented/img/plus.svg" width="20" height="20"> - Add Specification.
* <img src="docs/02__Services/3__Implemented/img/delete.svg" width="20" height="20"> - Delete.

In general at the right down side only one operation is available under <img src="docs/02__Services/3__Implemented/img/more.svg" width="20" height="20"> button:
* <img src="docs/02__Services/3__Implemented/img/cloud-upload.svg" width="20" height="20"> - Import Specifications.
* <img src="docs/02__Services/3__Implemented/img/cloud-download.svg" width="20" height="20"> - Export All Groups.

### View Specifications

When particular specification group name is clicked, system opens new page with the table of available specifications for clicked group. Next columns and elements are available for the table:

- **Name** - specification name, which is also considered as a version. Specification name **must be unique** inside of API Specification group for any type of service. For **Swagger** and **AsyncAPI** specifications version is retrieving from appropriate _"version"_ parameter in specification file. For **WSDL, GraphQL, Protobuf** specifications - _filename_ will be considered as a specification version.
- **Status** - API Specification status. Possible values:
	- <span style="color:blue; font-size: 30px;">&#8226</span> _**New**_ - initial state of API specification, uploaded manually or imported by service discovery.
	- <span style="color:green; font-size: 30px;">&#8226</span> _**In Use**_ - status indicates that API Specification is utilized within at least one chain.
	- <span style="color:red; font-size: 30px;">&#8226</span> _**Deprecated**_ - this status indicates that such specification is outdated and won't be available for selection in newly added chain elements. Old elements, where specification with this status is already selected may still continue using it.
- **Source** - specifies the way specification was created. Possible values:
	- **Manual** - uploaded manually.
	* **Discovered** - added as the result of service discovery. This is only applicable to Inner Cloud Services.
*  **Labels** - list of colored labels of the specification.
- **Used By** - expand, that contains the list of chains, where specification is being utilized. Each chain name under this expand is clickable and navigates to respective configuration graph.
- **Method** - method of the operation, mentioned in the specification (GET, POST, etc.)
- **URL** - operation path.

To add new information, click on the button <img src="docs/02__Services/3__Implemented/img/setting.svg" width="20" height="20"> located on the right side. The following field are available:

* **Protocol** - shows what protocol is used.
* **Extended Protocol** - shows special rules for the service protocol.
* **Specification** - the service's instruction.
* **Internal Service Name** - shows internal service name.
- **Created When** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Modified When** - datetime of entity modification.
- **Modified By** - shows the user, who modified an entity.

**Actions menu** - list of operations, accessed via <img src="docs/02__Services/3__Implemented/img/more.svg" width="20" height="20"> menu. Contains the following operations:
* <img src="docs/02__Services/3__Implemented/img/down.svg" width="20" height="20"> - Expand.
* <img src="docs/02__Services/3__Implemented/img/stop.svg" width="20" height="20"> - Deprecate.
* <img src="docs/02__Services/3__Implemented/img/export.svg" width="20" height="20"> - Export.

In general at the right down side only one operation is available under <img src="docs/02__Services/3__Implemented/img/more.svg" width="20" height="20"> button:
* <img src="docs/02__Services/3__Implemented/img/cloud-upload.svg" width="20" height="20"> - Import Specifications.
* <img src="docs/02__Services/3__Implemented/img/cloud-download.svg" width="20" height="20"> - Export Selected Specifications.

### View Operations

When specification is clicked, system opens new page with the table of available operations for clicked specifications. Next columns and elements are available for the table:

- **Name** - Clickable short operation name. If the name has not been found in the initial specification, The system generates its own name by concatenating **method** with the first found **entity**, identified in the **path** (parameters, mentioned in the **{ }** are ignored). Resulted name will be put in square brackets (e.g. for method **GET** and path **/api/v1/test/config**, the name will be [getConfig]). When clicked, system shows **"Operation info"** window with available details for specification and request/response schemes.
- **Method** - method of the operation, mentioned in the specification (GET, POST, etc.)
- **URL** - operation path.
- **Used by** - list, that contains references to the chains, utilizing this operation.

### Add Implemented Service

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To add new implemented service, click **"Create service"** button marked with <img src="docs/02__Services/1__External/img/plus.svg" width="20" height="20">  via action menu marked with <img src="docs/02__Services/1__External/img/more.svg" width="20" height="20"> on the bottom right of the screen. Specify service name and description on a newly opened pop-up and click "**Create**" button. System opens new window with three tabs:
- **Parameters**
- **API Specifications**
- **Environments**

Parameters tab contains minimal set of parameters, that allows to save the implemented service:
- **Name** - mandatory service name.
- **Description** - description of service.
- **Labels** - set of labels for service.
- **Created** - non-editable. Datetime and author of specification group creation.
- **Modified** - non-editable. Datetime and author of last specification group modification.

Specify the required fields and click **"Save"**. Notification about successful saving means that implemented service is added to the list of implemented services.
### Add Specification Group

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To add specification group to Implemented service:
1. Select <img src="docs/02__Services/3__Implemented/img/cloud-upload.svg" width="20" height="20"> "**Import Specifications**" option in action menu <img src="docs/02__Services/3__Implemented/img/more.svg" width="20" height="20"> for desired service.
2. Specify the **name** of the specification group on the opened pop-up.
3. There are two options to add API Specification:
    - **Import File** - on this tab you can import file with API specification by dragging it to the **"drop"** window or by using **"browse"** option.
	- **Import from Chains** - on this tab, it is possible to select existing [HTTP Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) endpoint, configured within a particular chain and create API Specification from it.
   <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
        <b>Note:</b><br>
            Via checkbox <b><i>"External routes only"</i></b> it is possible to control showing only respective <a href="doc/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md">HTTP Triggers</a> in the list.
    </div>
4. Confirm operation with **"Create"** button.

When API specification is added you will see the specification group with respective name and dates. All specifications will be placed under this specification group.

### Add API Specification

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To add API specification into existing specification group:
1. Select <img src="docs/02__Services/3__Implemented/img/cloud-upload.svg" width="20" height="20"> "**Import Specifications**" option in action menu <img src="docs/02__Services/3__Implemented/img/more.svg" width="20" height="20"> for desired group.
2. There are two options to add API Specification:
    - **Import File** - on this tab you can import file with API specification by dragging it to the **"drop"** window or by using **"browse"** option.
    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    <b>Note:</b><br>
         API Specification <b>version must be unique inside of API Specification group for any type of service</b>. Import of API Specification with non-unique version will result in version duplication error. 
        </div>
    - **Import from Chains** - on this tab, it is possible to select existing [HTTP Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md) endpoint, configured within a particular chain and create API Specification from it.
    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
        <b>Note:</b><br>
            Checked <b><i>"External routes only"</i></b> parameter allows to create specification only from chains with external <a href="doc/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md">HTTP Triggers</a>.
    </div>
3. Confirm operation with **"Create"** button.

### Add Environment

There is no manual option to create new environment for Implemented Service - it will be created automatically after API specification is successfully uploaded. Each particular Implemented Service could have only one related environment.

### Environment Update

To update the environment for the implemented service, follow the steps specified below:

1. Find appropriate service, which for you want to update the environment settings.
2. Click the name of the service.
3. Navigate to **"Environment"** tab.
4. Click the name of the environment.
5. Specify or update the fields (described below) according to your needs.
6. Click **"Save"**.

Below you can find the detailed information of all available parameters for the Environment, opened in Edit mode:

- **Name** - mandatory name for environment.
- **Address** - defines the first part of the path for given chain. Base path and operation under the related HTTP Trigger form the finalized endpoint.
- **Properties** - section to manage properties for the environment:
    - To add new property, click the icon <img src="docs/02__Services/3__Implemented/img/caret-down.svg" width="20" height="20"> near the section **"Properties"**, press button <img src="docs/02__Services/3__Implemented/img/plus.svg" width="20" height="20">, enter suitable data and click **Save** button.
    - To bulk create/update of environment properties, turn on the slider **"Show as Key Value"**, put pairs of property name and value and click **`Enter`**. See the format below:
      <pre style="background-color: #F5F5F7"><code style="color: #000000">property1_name=property1_value;
      property2_name=property2_value;
      </code></pre>

Default properties are described below:

| Parameter                                               | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                               | Sample                                |
| ------------------------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| <div style="width:150px">connectTimeout </div>          | String                                  | <div style="width:400px">Determines the timeout in milliseconds until a connection is established. Infinite timeout will be applied when zero value is specified.<br/><b>Default value:</b> 120000</div>                  | <div style="width:350px">120000</div> |
| <div style="width:150px">soTimeout</div>                | String                                  | <div style="width:400px">Defines the socket timeout in milliseconds, which is the timeout for waiting for data. Infinite timeout will be applied when zero value is specified.<br/><b>Default value:</b> 120000</div>     | <div style="width:350px">120000</div> |
| <div style="width:150px">connectionRequestTimeout</div> | String                                  | <div style="width:400px">The timeout in milliseconds used when requesting a connection from the connection manager. Infinite timeout will be applied when zero value is specified.<br/><b>Default value:</b> 120000</div> | <div style="width:350px">120000</div> |
| <div style="width:150px">responseTimeout</div>          | String                                  | <div style="width:400px">Determines the timeout in milliseconds until arrival of a response. Infinite timeout will be applied when zero value is specified.<br/><b>Default value:</b> 120000</div>                        | <div style="width:350px">120000</div> |
| <div style="width:150px">deleteWithBody</div>           | Boolean                                 | <div style="width:400px">Indicates that DELETE request contains body.<br/><b>Default value:</b> false</div>                                                                                                               | <div style="width:350px">false</div>  |
| <div style="width:150px">getWithBody</div>              | Boolean                                 | <div style="width:400px">Indicates that GET request contains body.<br/><b>Default value:</b> false</div>                                                                                                                  | <div style="width:350px">false</div>  |

Additionally, if it is required to use same connection for multiple requests, it is possible to specify **reuseEstablishedConnection** property with values: true/false.

When environment is saved, its updated card will be available under the environment tab. The card will have next information and elements:

- **Name** - name of the environment, specified during configuration.
- **Address** - path, specified under the environment.
- **Source** - MaaS or Manual.
- **Modified** - datetime and author of environment modification.
- **Used by** - the list of chains where service environment is being used.

### Import Service(s)

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To import the service(s), click the icon <img src="docs/02__Services/3__Implemented/img/cloud-upload.svg" width="20" height="20">, drag and drop **.zip** file into import area or click **"browse"** link and select **single** file with respective format from the explorer menu. When appropriate file is added to the window, click **"Import"** button to start the import process. API Specification version in archive <font color="#fa0000">**must be unique**</font> for each API Specification. During the import, system follows next logic:
- Verify Import Instructions, saved in the system. Proceed with the step below only if they exist:
    - Fetch the list of service ids with **ignore** action and skip import process for them.
- Find existing services, specification groups and specification by ids from import archive:
    - If there are specifications with ids already exist in the system, regardless of their parent specification groups and services, system **ignores** them.
    - If system already has entities with ids, specified in import archive:
        - Merge data from archive, including **custom labels**, into existing entities.
        - Technical labels are going to be removed from existing entities if they are updated as a part of import process.
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>If import is done as a part of deployment process or it is initiated directly via API with <b>corresponding headers</b>, the current set of technical labels is <b>always overridden</b> by the values, received from import archive. This might lead to technical labels to be removed from existing entities if imported file has no corresponding technical labels for them.
</div>
<ul>
  <ul>
    <li>Otherwise, if entities, that are being imported, don't exist, they are going to be created with the next specifics:</li>
      <ul>
        <li>For all new services system will increment standard UUID, so it is possible to operate with it in order to maintain services uniqueness.</li>
        <li>For <b>Swagger</b> system will build specification version from "version" parameter in specification file.</li>
      </ul>
  </ul>
</ul>
<div style="background-color: #fff1f0; border-left: 6px solid #ff4538; padding: 10px">
<b>Note:</b><br>
When importing <b>grpc</b> services, it is absolutely required to have protobuf files properly structured within the archive, according to the native logic, described in public <b>Protocol Buffers Documentation Language Guide (proto 3)</b>. Following this logic, protobuf files must be placed to the folders, fully corresponding to the path, described in <b>"import"</b> statements of the files. For example, when protobuf file <b>proto_1</b> has a statement "<b><i>import test/proto/types/active/proto_2.proto</i></b>", it means that <b>proto_2</b> file must be placed to "<b><i>test/proto/types/active/</i></b>" folder in the archive.
</div>

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

System allows to export service with all its API specifications, environments and sources. From **"External Services"** page - mark specific services with checkboxes and click <img src="docs/02__Services/3__Implemented/img/cloud-download.svg" width="20" height="20"> **Export**. Or simply click this button to export all services at once after confirmation.

### Constraints

---

Please consider next constraints:
- When implemented service is being created from HTTP Trigger element, it (trigger) <b>must</b> have a <b>single HTTP method</b> configured in the settings. Otherwise, creation will fail with error.
- **Technical** labels  be imported via UI or exported.