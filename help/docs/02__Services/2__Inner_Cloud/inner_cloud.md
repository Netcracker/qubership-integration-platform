# Inner Cloud Services
## Description

---
Qubership Integration Platform is able to integrate with Inner Cloud Services, that are located within the environment. Unlike for external services, Qubership Integration Platform might perform a lookup in order to fetch all Inner Services available within the Cloud.

## User Interface

---
### View Inner Cloud Services

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

Table with Inner Cloud services is accessible by navigating to **Services** → **Inner Cloud** tab. Next columns and elements are available for the table:

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
- **Actions menu** - list of operations, accessed via <img src="docs/02__Services/2__Inner_Cloud/img/more.svg" width="20" height="20"> menu under each service. Contains next operations:
	- **Edit** <img src="docs/02__Services/2__Inner_Cloud/img/edit.svg" width="20" height="20"> - opens pop-up to change service name, description or set of **custom** labels.
	- **Delete** <img src="docs/02__Services/2__Inner_Cloud/img/delete.svg" width="20" height="20"> - deletes entity.
	- **Expand All** <img src="docs/02__Services/2__Inner_Cloud/img/column-height.svg" width="20" height="20"> - fully expands the entity.
	- **Collapse All** <img src="docs/02__Services/2__Inner_Cloud/img/vertical-align-middle.svg" width="20" height="20"> - fully collapses the entity.
	- **Export** <img src="docs/02__Services/2__Inner_Cloud/img/cloud-upload.svg" width="20" height="20"> - allows to export the entity.

In general at the right down side only one operation is available under <img src="docs/02__Services/2__Inner_Cloud/img/more.svg" width="20" height="20"> button:
* <img src="docs/02__Services/2__Inner_Cloud/img/plus.svg" width="20" height="20"> - Create Service.
* <img src="docs/02__Services/2__Inner_Cloud/img/cloud-upload.svg" width="20" height="20"> - Upload Service.
* <img src="docs/02__Services/2__Inner_Cloud/img/cloud-download.svg" width="20" height="20"> - Download Selected Services.

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
- **Created When** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Modified When** - datetime of entity modification.
- **Modified By** - shows the user, who modified an entity.

To add new information, click on the button <img src="docs/02__Services/2__Inner_Cloud/img/setting.svg" width="20" height="20"> located on the right side. The following field are available:

* **Protocol** - shows what protocol is used.
* **Extended Protocol** - shows special rules for the service protocol.
* **Specification** - the service's instruction.
* **Internal Service Name** - shows internal service name.
- **Method** -  method of the operation, mentioned in the specification.
- **URL** - operation path.

**Actions menu** - list of operations, accessed via <img src="docs/02__Services/2__Inner_Cloud/img/more.svg" width="20" height="20"> menu. Contains the following operations:
* <img src="docs/02__Services/2__Inner_Cloud/img/down.svg" width="20" height="20"> - Expand.
* <img src="docs/02__Services/2__Inner_Cloud/img/plus.svg" width="20" height="20"> - Add Specification.
* <img src="docs/02__Services/2__Inner_Cloud/img/delete.svg" width="20" height="20"> - Delete.

In general at the right down side only one operation is available under <img src="docs/02__Services/2__Inner_Cloud/img/more.svg" width="20" height="20"> button:
* <img src="docs/02__Services/2__Inner_Cloud/img/cloud-upload.svg" width="20" height="20"> - Import Specifications.
* <img src="docs/02__Services/2__Inner_Cloud/img/cloud-download.svg" width="20" height="20"> - Export All Groups.

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

To add new information, click on the button <img src="docs/02__Services/2__Inner_Cloud/img/setting.svg" width="20" height="20"> located on the right side. The following field are available:

* **Protocol** - shows what protocol is used.
* **Extended Protocol** - shows special rules for the service protocol.
* **Specification** - the service's instruction.
* **Internal Service Name** - shows internal service name.
- **Created When** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Modified When** - datetime of entity modification.
- **Modified By** - shows the user, who modified an entity.

**Actions menu** - list of operations, accessed via <img src="docs/02__Services/2__Inner_Cloud/img/more.svg" width="20" height="20"> menu. Contains the following operations:
* <img src="docs/02__Services/2__Inner_Cloud/img/down.svg" width="20" height="20"> - Expand.
* <img src="docs/02__Services/2__Inner_Cloud/img/stop.svg" width="20" height="20"> - Deprecate.
* <img src="docs/02__Services/2__Inner_Cloud/img/export.svg" width="20" height="20"> - Export.

In general at the right down side only one operation is available under <img src="docs/02__Services/2__Inner_Cloud/img/more.svg" width="20" height="20"> button:
* <img src="docs/02__Services/2__Inner_Cloud/img/cloud-upload.svg" width="20" height="20"> - Import Specification.
* <img src="docs/02__Services/2__Inner_Cloud/img/cloud-download.svg" width="20" height="20"> - Export Selected Specifications.

### View Operations

When specification is clicked, system opens new page with the table of available operations for clicked specifications. Next columns and elements are available for the table:

- **Name** - Clickable short operation name. If the name has not been found in the initial specification, The system generates its own name by concatenating **method** with the first found **entity**, identified in the **path** (parameters, mentioned in the **{ }** are ignored). Resulted name will be put in square brackets (e.g. for method **GET** and path **/api/v1/test/config**, the name will be [getConfig]). When clicked, system shows **"Operation info"** window with available details for specification and request/response schemes.
- **Method** - method of the operation, mentioned in the specification (GET, POST, etc.)
- **URL** - operation path.
- **Used by** - list, that contains references to the chains, utilizing this operation.

### Add Inner Cloud Service

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To add new Inner Cloud Service, click **"Create service"** button marked with <img src="docs/02__Services/1__External/img/plus.svg" width="20" height="20">  via action menu marked with <img src="docs/02__Services/1__External/img/more.svg" width="20" height="20"> on the bottom right of the screen. Specify service name and description on a newly opened pop-up and click "**Create**" button. System opens new window with three tabs:
- **Parameters**
- **API Specifications**
- **Environments**

Parameters tab contains minimal set of parameters, that allows to save the internal service:

- **Name** - mandatory service name.
- **Description** - description of service.
- **Internal name** - non-editable. Field that contains K8S name of the service. Specified only for services that are already available on Kubernetes side.
- **Labels** - set of labels for service.
- **Created** - non-editable. Datetime and author of specification group creation.
- **Modified** - non-editable. Datetime and author of last specification group modification.

Specify the required fields and click **"Save"**. Notification about successful saving means that service is added to the list of inner services.

### Add Specification Group

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To add specification group to Inner Cloud service:
1.  Select <img src="docs/02__Services/2__Inner_Cloud/img/cloud-upload.svg" width="20" height="20"> "**Import Specifications**" option in action menu <img src="docs/02__Services/2__Inner_Cloud/img/more.svg" width="20" height="20"> for desired service.
2. Specify the **name** of the specification group on the opened pop-up.
3. **Upload** file or archive with API specification by dragging it to the **"drop"** window or by using **"browse"** option.
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
  <b>Note:</b><br>
      For the service with <b><i>grpc</i></b> protocol there could be uploaded <code>.zip</code> archive with more than one <code>.proto</code> file.
  </div>
4. For **WSDL**, **GraphQL**, **Protobuf** specifications, system will generate the name by autoincrement (e.g. 1.0.0 -> 2.0.0), rename if required.
5. Confirm operation with **"Create"** button.

When API specification is added you will see the specification group with respective name and dates. All specifications will be placed under this specification group.

### Add API Specification

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To add API specification into existing specification group:
1. Select <img src="docs/02__Services/2__Inner_Cloud/img/cloud-upload.svg" width="20" height="20"> "**Import Specifications**" option in action menu <img src="docs/02__Services/2__Inner_Cloud/img/more.svg" width="20" height="20"> for desired group.
2. **Upload** file or archive with API specification by dragging it to the **"drop"** window or by using **"browse"** option.
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
  <b>Note:</b><br>
  <ul>
      <li><b>API Specification version must be unique inside of API Specification group for any type of service</b>. Import of API Specification with non-unique version will result in version duplication error (specification will not be uploaded).</li>
      <li>For service with <b><i>gprc</i></b> protocol there could be uploaded <code>.zip</code> archive with more than one <code>.proto</code> file.</li>
  </ul>
  </div>
3. Only for **WSDL**, **GraphQL**, **Protobuf** specifications, system will generate the name by autoincrement (e.g. 1.0.0 -> 2.0.0), rename if required.
4. Confirm operation with **"Create"** button.

### Add Environment

There is no manual option to create new environment for Inner Cloud Service  - it will be created automatically as a result of service discovery process. Each particular Inner Cloud Service could have only one related environment.

### Environment Update

To update the environment for Inner Cloud Service, follow the steps specified below:

1. Find appropriate service, which for you want to update the environment settings.
2. Click the name of the service.
3. Navigate to **"Environment"** tab.
4. Click the name of the environment.
5. Specify or update the fields (described below) according to your needs:
    1. **Name** - mandatory name for environment.
    3. **Source type** (only for _**kafka**_ or _**amqp**_ protocol) - it would be either **MaaS** or **Manual**.
    4. **Address** - defines the URL for the current environment. Field is editable if Manual source type has been selected.
    5. **Properties** - section to manage properties for the environment:
        - To add new property, click the icon <img src="docs/02__Services/2__Inner_Cloud/img/caret-down.svg" width="20" height="20"> near the section **"Properties"**, press button <img src="docs/02__Services/2__Inner_Cloud/img/plus.svg" width="20" height="20">, enter suitable data and click **`Enter`**.
        - To bulk create/update of environment properties, turn on the slider **"Show as Key Value"**, put pairs of property name and value and click **`Enter`**. See the format below:
       <pre style="background-color: #F5F5F7"><code style="color: #000000">property1_name=property1_value;
       property2_name=property2_value;
       </code></pre>
6. Click **"Save"** or shortcut combination of **`Ctrl+Enter`**.

Default properties are unique for different protocols and described below:
<ul>
  <ul>
    <li><b>http</b></li>
  </ul>
</ul>

| Parameter                                               | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                               | Sample                                |
| ------------------------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| <div style="width:150px">connectTimeout </div>          | String                                  | <div style="width:400px">Determines the timeout in milliseconds until a connection is established. Infinite timeout will be applied when zero value is specified.<br/><b>Default value:</b> 120000</div>                  | <div style="width:350px">120000</div> |
| <div style="width:150px">soTimeout</div>                | String                                  | <div style="width:400px">Defines the socket timeout in milliseconds, which is the timeout for waiting for data. Infinite timeout will be applied when zero value is specified.<br/><b>Default value:</b> 120000</div>     | <div style="width:350px">120000</div> |
| <div style="width:150px">connectionRequestTimeout</div> | String                                  | <div style="width:400px">The timeout in milliseconds used when requesting a connection from the connection manager. Infinite timeout will be applied when zero value is specified.<br/><b>Default value:</b> 120000</div> | <div style="width:350px">120000</div> |
| <div style="width:150px">responseTimeout</div>          | String                                  | <div style="width:400px">Determines the timeout in milliseconds until arrival of a response. Infinite timeout will be applied when zero value is specified.<br/><b>Default value:</b> 120000</div>                        | <div style="width:350px">120000</div> |
| <div style="width:150px">deleteWithBody</div>           | Boolean                                 | <div style="width:400px">Indicates that DELETE request contains body.<br/><b>Default value:</b> false</div>                                                                                                               | <div style="width:350px">false</div>  |
| <div style="width:150px">getWithBody</div>              | Boolean                                 | <div style="width:400px">Indicates that GET request contains body.<br/><b>Default value:</b> false</div>                                                                                                                  | <div style="width:350px">false</div>  |

<ul>
  <ul>
    <li><b>kafka</b></li>
  </ul>
</ul>

| Parameter                                              | <div style="width:75px">Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                                         | Sample                                                                                                                             |
| ------------------------------------------------------ | :-------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| <div style="width:165px">key</div>                     | String                            | <div style="width:400px">The record key (or null if no key is specified). If this option has been configured, then it takes precedence over header KafkaConstants#KEY.</div>                                                                                                                                                                                                                                                                        | <div style="width:350px">key_sample </div>                                                                                         |
| <div style="width:165px">sslProtocol</div>             | String                            | <div style="width:400px">The SSL protocol used to generate the SSLContext. Default setting is TLS, which is used for most of the cases. Allowed values in recent JVMs are TLS, TLSv1.1 and TLSv1.2. SSL, SSLv2 and SSLv3 may be supported in older JVMs, but their usage is discouraged due to known security vulnerabilities.</div>                                                                                                                | <div style="width:350px">TLSv1.2</div>                                                                                             |
| <div style="width:165px">saslMechanism</div>           | String                            | <div style="width:400px">The Simple Authentication and Security Layer (SASL) Mechanism used. For the valid values see http://www.iana.org/assignments/sasl-mechanisms/sasl-mechanisms.xhtml.  </div>                                                                                                                                                                                                                                                | <div style="width:350px">GSSAPI</div>                                                                                              |
| <div style="width:165px">saslJaasConfig</div>          | String                            | <div style="width:400px">Expose Kafka sasl.jaas.config parameter.</div>                                                                                                                                                                                                                                                                                                                                                                             | <div style="width:350px">org.apache.kafka.common.security.plain.PlainLoginModule required username=example password=example;</div> |
| <div style="width:165px">securityProtocol</div>        | String                            | <div style="width:400px">Protocol used to communicate with brokers. Possible values:<ul><li>SASL_PLAINTEXT</li><li>PLAINTEXT</li><li>SASL_SSL</li><li>SSL</li></ul> </div>                                                                                                                                                                                                                                                                          | <div style="width:350px">PLAINTEXT</div>                                                                                           |
| <div style="width:150px">consumerConsistencyMode</div> | String                            | <div style="width:400px">Blue-green specific parameter. Possible values: <ul><li>EVENTUAL – Default option when blue-green is supported. Consumers offset for candidate during promotion stays the same </li><li>GUARANTEE_CONSUMPTION - consumers offset for candidate during promotion is copied from lowest offset for this group of consumers</li></ul> <b>Note:</b> This parameter and its value should be entered manually if required.</div> | <div style="width:350px">EVENTUAL</div>                                                                                            |
| <div style="width:165px">sslEnabledProtocols</div>     | String                            | <div style="width:400px">The list of protocols enabled for SSL connections. The default is TLSv1.2,TLSv1.3 when running with Java 11 or newer, TLSv1.2 otherwise. With the default value for Java 11, clients and servers will prefer TLSv1.3 if both support it and fallback to TLSv1.2 otherwise (assuming both support at least TLSv1.2). This default should be fine for most cases.</div>                                                      | <div style="width:350px">TLSv1,TLSv1.1,TLSv1.2</div>                                                                               |
| <div style="width:165px">sslEndpointAlgorithm</div>    | String                            | <div style="width:400px">The endpoint identification algorithm to validate server hostname using server certificate.</div>                                                                                                                                                                                                                                                                                                                          | <div style="width:350px">HTTPS</div>                                                                                               |

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>When message processing is slow, it could lead to interruption due to reached timeout. In such cases, it is recommended to specify parameter <b>maxPollIntervalMs</b> (The maximum delay between invocations of polls when using consumer group management.) with value <b>higher</b>, than default <b>300000</b> and parameter <b>maxPollRecords</b> (The maximum number of records returned in a single call to poll) with value <b>lower</b>, than defaulted <b>500</b>. This will allow to process the messages smoothly, with lower risk of timeout.
</div>
<ul>
  <ul>
    <li><b>amqp</b></li>
  </ul>
</ul>

| Parameter                                 | <div style="width:75px">Data Type | Description                                                                                                                                                              | Sample                               |
| ----------------------------------------- | :-------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------ |
| <div style="width:165px">username</div>   | String                            | <div style="width:400px">Username in case of authenticated access.</div>                                                                                                 | <div style="width:350px">N/A</div>   |
| <div style="width:165px">password</div>   | String                            | <div style="width:400px">Password for authenticated access.</div>                                                                                                        | <div style="width:350px">N/A</div>   |
| <div style="width:165px">routingKey</div> | String                            | <div style="width:400px">The routing key to use when binding a consumer queue to the exchange. For producer routing keys, you set the header rabbitmq.ROUTING_KEY.</div> | <div style="width:350px">rkey1</div> |

Additionally, if it is required to use same connection for multiple requests of **http** and **graphql** services, it is possible to specify **reuseEstablishedConnection** property with values: true/false.
When environment is saved, its updated card will be available under the environment tab. The card will have next information and elements:

- **Name** - name of the environment, specified during configuration.
- **Address** - exact environment address.
- **Source** - MaaS or Manual.
- **Modified** - datetime and author of environment modification.
- **Used by** - the list of chains where service environment is being used.

### Import Service(s)

<span style="background:#deebff;color:#0747a6;padding:4px 8px;border-radius:6px;font-weight:600;">
  Not available via VS Code extension
</span>

To import the service(s), click the icon <img src="docs/02__Services/2__Inner_Cloud/img/cloud-upload.svg" width="20" height="20">, drag and drop **.zip** file into import area or click **"browse"** link and select **single** file with respective format from the explorer menu. When appropriate file is added to the window, click **"Import"** button to start the import process. API Specification version in archive <font color="#fa0000">**must be unique**</font> for each API Specification. During the import, system follows next logic:
- Verify Import Instructions, saved in the system. Proceed with the step below only if they exist:
	- Fetch the list of service ids with **ignore** action and skip import process for them.
- Find existing services, specification groups and specification by ids from import archive:
	- If there are specifications with ids already exist in the system, regardless of their parent specification groups and services, system **ignores** them.
  - If system already has entities with ids, specified in import archive:
    - Merge data from archive, including **custom labels**, into existing entities.
    - **Technical labels** are going to be removed from existing entities if they are updated as a part of import process.
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>If import is done as a part of deployment process or it is initiated directly via API with <b>corresponding headers</b>, the current set of technical labels is <b>always overridden</b> by the values, received from import archive. This might lead to technical labels to be removed from existing entities if imported file has no corresponding technical labels for them.
</div>
<ul>
  <ul>
    <ul> 
      <li><b>"Discovered"</b> status of API specification for Inner Cloud Service will be switched to <b>"Manually uploaded"</b> as the result of manual import of the specification with the same ID.</li>
	</ul> 
	<li>Otherwise, if entities, that are being imported, don't exist, they are going to be created with the next specifics:</li>
    <ul>
      <li>For all new services system will increment standard UUID, so it is possible to operate with it in order to maintain services uniqueness.</li>
      <li>For <b>Swagger</b> and <b>AsyncAPI</b> system will build specification version from "version" parameter in specification file.</li>
      <li>For <b>WSDL</b>, <b>GraphQL</b> and <b>Protobuf</b> API specification's, their versions will be generated by autoincrement (1.0.0, 2.0.0, etc.), but it is also possible to manually rename specifications before import.</li>
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

System allows to export service with all its API specifications, environments and sources. From **"External Services"** page - mark specific services with checkboxes and click <img src="docs/02__Services/2__Inner_cloud/img/cloud-download.svg" width="20" height="20"> **Export**. Or simply click this button to export all services at once after confirmation.

### Constraints

---

Please consider next constraints:
- **Technical** labels cannot be imported via UI or exported.