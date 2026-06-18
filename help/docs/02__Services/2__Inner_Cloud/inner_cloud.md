# Inner Cloud Services
## Description

---
Qubership Integration Platform is able to integrate with Inner Cloud Services, that are located within the environment. Unlike for external services, Qubership Integration Platform might perform a lookup in order to fetch all Inner Services available within the Cloud.

## User Interface

---
### View Inner Cloud Services
<ins>Web UI</ins>

Table with Inner Cloud services is accessible by navigating to **Services** → **Inner Cloud** tab. Next columns and elements are available for the table:

- **Name** - clickable name of the service or specification group. When clicked, system navigates to respective entity.
- **Protocol** - service's integration protocol. Possible values: **_http, soap, kafka, amqp, GraphQL, gRPC_**. Value for this parameter will be propagated from the firstly imported API specification. There is no ability to upload API specifications with another protocol after that.
- **Status** - API Specification status. Possible values:
  - 🔵 _**New**_ - initial state of API specification, uploaded manually or imported by service discovery.
  - 🟢 _**In Use**_ - status indicates that API Specification is utilized within at least one chain.
  - 🔴 _**Deprecated**_ - this status indicates that such specification is outdated and won't be available for selection in newly added chain elements. Old elements, where specification with this status is already selected may still continue using it.
- **Source** - specifies the way specification was created. Possible values:
  - **Manual** - uploaded manually.
  - **Discovered** - added as the result of service discovery. This is only applicable to Inner Cloud Services.
- **Labels** - list of colored labels of the service, specification group or specification, unique within particular entity of each type.
- **Created At** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Modified At** - datetime of entity modifying (hidden by default).
- **Modified By** - shows the user, who modified an entity (hidden by default).
- **Actions menu** - list of operations, accessed via ![more](img/more.svg) menu under each service. Contains next operations:
  - **Edit** ![edit](img/edit.svg) - opens pop-up to change service name, description or set of **custom** labels.
  - **Delete** ![delete](img/delete.svg) - deletes entity.
  - **Add Specification Group** ![plus](img/plus.svg) - allows to add a specification group.
  - **Expand All** ![column-height](img/column-height.svg) - fully expands the entity.
  - **Collapse All** ![vertical-align-middle](img/vertical-align-middle.svg) - fully collapses the entity.
  - **Export** ![cloud-download](img/cloud-download.svg) - allows to export the entity.
- **Control panel** - panel, placed on top of the table. Provides next capabilities:
  - **Search field** - search box, provides ability to find respective data in the table.
  - ![cloud-sync](img/cloud-sync.svg) - Service Discovery.
  - ![filter](img/filter.svg) - opens filter pop-up.
  - ![setting](img/setting.svg) - opens pop-up with table properties that allows to adjust visibility and sequence of columns except **Name**.
  - ![cloud-download](img/cloud-download.svg) - exports the service.
  - ![cloud-upload](img/cloud-upload.svg) - opens pop-up for service import.
  - ![plus](img/plus.svg) - provides ability to add new service.

<ins>VS Code Extension</ins>

All services created using VS Code Extension appears under "Services" folder. This folder can be located by expanding "QIP" folder in the left bottom.

### View Parameters
Parameters tab contains the following information:
- **Name** - mandatory service name.
- **Description** - description of service.
- **Protocol** - service's integration protocol.
- **Labels** - list of colored labels of the service, specification group or specification, unique within particular entity of each type.
  It might contain **custom** labels, entered by user via Qubership Integration Platform UI or **technical** labels,
  populated as part of the **deployment via Samples Repository**. Custom labels can be added or removed clicking on the field.
  **Technical** labels cannot be updated manually.

For <ins>Web UI</ins> there are some additional information:

- **Created** - datetime of entity creation.
- **Modified** - datetime of entity modifying.

### View Specification Groups
When service is clicked, the system shows the table with all specification groups and specifications, available for clicked service. Next columns and elements are available for the table:
- **Name** - clickable name of the specification group or specification. When clicked, system navigates to respective entity.
- **Status** - API Specification status. Possible values:
  - 🔵 _**New**_ - initial state of API specification, uploaded manually or imported by service discovery.
  - 🟢 _**In Use**_ - status indicates that API Specification is utilized within at least one chain.
  - 🔴 _**Deprecated**_ - this status indicates that such specification is outdated and won't be available for selection in newly added chain elements. Old elements, where specification with this status is already selected may still continue using it.
- **Source** - specifies the way specification was created. Possible values:
  - **Manual** - uploaded manually.
  - **Discovered** - added as the result of service discovery. This is only applicable to Inner Cloud Services.
- **Labels** - list of colored labels of the specification group, unique within particular specification group.
- **Used By** - expand, that contains the list of chains, where specification is being utilized. Each chain name under this expand is clickable and navigates to respective configuration graph.
- **Created At** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Modified At** - datetime of entity modification.
- **Modified By** - shows the user, who modified an entity.
- **Protocol** - shows what protocol is used.
- **Extended Protocol** - shows special rules for the service protocol.
- **Specification** - the service's instruction.
- **Internal Service Name** - shows internal service name.
- **Method** -  method of the operation, mentioned in the specification.
- **URL** - operation path.

Column visibility and order can be adjusted using the ![setting](img/setting.svg) button located above the table in the top-right corner of the page.

**Actions menu** - list of operations, accessed via ![more](img/more.svg) menu. Contains the following operations:
- **Expand** ![down](img/down.svg) / **Collapse** ![up](img/up.svg) - fully expands or collapses the entity.
- **Add Specification** ![plus](img/plus.svg) - allows to add a new specification to the group.
- **Delete** ![delete](img/delete.svg) - deletes entity.

In general at the right top the next operation is available only for <ins>Web UI</ins>:
- ![cloud-download](img/cloud-download.svg) - Export service.

### View Specifications
When particular specification group name is clicked, the system opens new page with the table of available specifications for clicked group. Next columns and elements are available for the table:

- **Name** - specification name, which is also considered as a version. Specification name **must be unique** inside of
  API Specification group for any type of service. For **Swagger** and **AsyncAPI** specifications version is retrieving
  from appropriate _"version"_ parameter in specification file. For **WSDL, GraphQL, Protobuf** specifications -
  _filename_ will be considered as a specification version.
- **Status** - API Specification status. Possible values:
  - 🔵 _**New**_ - initial state of API specification, uploaded manually or imported by service discovery.
  - 🟢 _**In Use**_ - status indicates that API Specification is utilized within at least one chain.
  - 🔴 _**Deprecated**_ - this status indicates that such specification is outdated and won't be available for selection in newly added chain elements. Old elements, where specification with this status is already selected may still continue using it.
- **Source** - specifies the way specification was created. Possible values:
  - **Manual** - uploaded manually.
  - **Discovered** - added as the result of service discovery. This is only applicable to Inner Cloud Services.
- **Labels** - list of colored labels of the specification.
- **Used By** - expand, that contains the list of chains, where specification is being utilized. Each chain name under this expand is clickable and navigates to respective configuration graph.
- **Method** - method of the operation, mentioned in the specification (GET, POST, etc.)
- **URL** - operation path.

To add new information, click on the button ![setting](img/setting.svg) located on the right side. The following field are available:

- **Protocol** - shows what protocol is used.
- **Extended Protocol** - shows special rules for the service protocol.
- **Specification** - the service's instruction.
- **Internal Service Name** - shows internal service name.
- **Created When** - datetime of entity creation.
- **Created By** - shows the user, who created an entity.
- **Modified When** - datetime of entity modification.
- **Modified By** - shows the user, who modified an entity.

**Actions menu** - list of operations, accessed via ![more](img/more.svg) menu. Contains the following operations:
- ![down](img/down.svg) / ![up](img/up.svg) - expands the specification and shows all operations under it or collapses the specification and hides operations under it.
- ![stop](img/stop.svg) - deprecates the specification, that makes it unavailable for newly added chain elements.
- ![cloud-download](img/cloud-download.svg) - exports the specification.

In general at the right top the following operations are available:
- ![cloud-upload](img/cloud-upload.svg) - opens pop-up for the specification import.
- ![cloud-download](img/cloud-download.svg) - exports the specification (available only for <ins>Web UI</ins>).

### View Operations
When specification is clicked, the system opens new page with the table of available operations for clicked specifications. Next columns and elements are available for the table:

- **Name** - Clickable short operation name. If the name has not been found in the initial specification, QIP generates its own name by concatenating **method** with the first found **entity**,
identified in the **path** (parameters, mentioned in the **{ }** are ignored). Resulted name will be put in square brackets (e.g. for method **GET** and path **/api/v1/test/config**, the name will be [getConfig]). When clicked, the system shows **"Operation info"** window with available details for specification and request/response schemes.
- **Method** - method of the operation, mentioned in the specification (GET, POST, etc.)
- **URL** - operation path. Applicable for services using **HTTP** and **gRPC** protocols.
- **Topic** - topic name used for publishing and consuming messages. Only applicable for services using **Kafka** protocol.
- **Channel** - channel used for queuing, publishing and consuming messages. Only applicable for services using **AMQP** protocol.
- **Operation** - GraphQL operation definition (without operation type). Only applicable for services using **GraphQL** protocol.
- **Used by** - list, that contains references to the chains, utilizing this operation.
- **Control panel** - panel placed on the top of the table. Provides next capabilities:
  - ![cloud-download](img/cloud-download.svg) - export specification (available only for <ins>Web UI</ins>).
  
### Discover Inner Cloud Services
**`⛔ Not available via VS Code extension`**

To find all available Inner Services, there is a specific button ![cloud-sync](img/cloud-sync.svg) **"Service Discovery"**  available on **"Inner Cloud Services"** tab under **"Services"** section. To start a discovery process, simply click the mentioned button. Progress bar will show the percentage of discovery completion. Notification at the top-right will additionally signal about process start and finish. The result of the discovery might be:

- **New Service** - if new service has been discovered.
- **New Specification Group** under the existing service - if no new services were discovered but there are new URL paths found.
- **New Specification** under the existing service - if there is a new specification found for existing inner service.
- **Metamodel** - if metadata has been fetched from Metamodel Provider Backend.

For inner services, that are discovered in K8S namespace, system uses their names as unique IDs.

>ℹ️**Note:** Depending on the capacity, network and environment state, Discovery process might take some time to complete.


### Add Inner Cloud Service
<ins>Web UI</ins>

To add new Inner Cloud Service, click **"Create service"** button marked with ![plus](img/plus.svg)  on the top right of the screen. Specify service name and description on a newly opened pop-up and click "**Create**" button. System opens new window with three tabs:
- **Parameters**
- **API Specifications**
- **Environments**

Parameters tab contains minimal set of parameters, that allows to save the internal service:

- **Name** - mandatory service name.
- **Description** - description of service.
- **Internal name** - non-editable. Field that contains K8S name of the service. Specified only for services that are already available on Kubernetes side.
- **Labels** - set of labels for service:
- **Created** - non-editable. Datetime and author of specification group creation.
- **Modified** - non-editable. Datetime and author of last specification group modification.

Specify the required fields and click **"Save"**. Notification about successful saving means that service is added to the list of inner services.

<ins>VS Code Extension</ins>

To create any service using VS Code Extension, follow the steps outlined below:

1. Open "VS Code Extension" in Visual Studio Code.
2. In the left bottom find QIP section and expand it.
3. Near the "Services" folder click on appearing button "QIP Create service".
4. At the top of Visual Studio Code enter the name of the chain, select the type of the service, enter some description and click Enter. Next, it opens "Parameters" tab of the created service.

### Add Specification Group
To add specification group to Inner Cloud service:
1. Select ![plus](img/plus.svg) "**Add Specification Group**" option.
2. Specify the **name** of the specification group on the opened pop-up.
3. **Upload** file or archive with API specification by dragging it to the **"drop"** window or by using **"browse"** option.
> ℹ️ **Note:**
> - For the service with _**grpc**_ protocol there could be uploaded `.zip` archive with more than one `.proto` file.
> - For **WSDL**, **GraphQL**, **Protobuf** specifications, system will generate the name by autoincrement (e.g. 1.0.0 -> 2.0.0), rename if required.
5. Confirm operation with **"Import File"** button.

When API specification is added you will see the specification group with respective name and dates. All specifications will be placed under this specification group.

### Add API Specification
To add API specification into existing specification group:
1. Select ![cloud-upload](img/cloud-upload.svg) "**Import Specification**" option for desired group.
2. **Upload** file or archive with API specification by dragging it to the **"drop"** window or by using **"browse"** option.
> ℹ️ **Note:**
> - **API Specification version must be unique inside of API Specification group for any type of service**. Import of API Specification with non-unique version will result in version duplication error.
> - For service with _**grpc**_ protocol, import archive could contain more than one `.proto` file.
> - Only for **WSDL**, **GraphQL**, **Protobuf** specifications, system will generate the name by autoincrement (e.g. 1.0.0 -> 2.0.0), rename if required.
3. Confirm operation with **"Import File"** button.

### Add Environment

There is no manual option to create new environment for Inner Cloud Service - it will be created automatically as a result of service discovery process. Each particular Inner Cloud Service could have only one related environment.

### Environment Update
To update the environment for Inner Cloud Service, follow the steps specified below:

1. Find appropriate service, which for you want to update the environment settings.
2. Click the name of the service.
3. Navigate to **"Environment"** tab.
4. Click the name of the environment.
5. Specify or update the fields (described below) according to your needs:
    1. **Name** - mandatory name for environment.
    2. **Source type** (only for _**kafka**_ or _**amqp**_ protocol) - it would be either **MaaS** or **Manual**.
    3. **Address** - defines the URL for the current environment. Field is editable if Manual source type has been selected.
    4. **Properties** - section to manage properties for the environment:
        - To add new property, click the icon ![right](img/right.svg) near the section **"Properties"**, press button ![plus](img/plus.svg), enter suitable data and click **`Enter`**.
        - To bulk create/update of environment properties, turn on the slider **"Show as key-value"**, put pairs of property name and value and click **`Enter`**. See the format below:
        ```text
        property1_name=property1_value;
        property2_name=property2_value;
        ```
6. Click **"Save"** or shortcut combination of **`Ctrl+Enter`**.

Default properties are unique for different protocols and described below:
#### http

| Parameter                | Data Type | Description                                                                                                                                                                            | Sample |
| ------------------------ | :-------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| connectTimeout           | String    | Determines the timeout in milliseconds until a connection is established. Infinite timeout will be applied when zero value is specified.<br>**Default value:** 120000                  | 120000 |
| soTimeout                | String    | Defines the socket timeout in milliseconds, which is the timeout for waiting for data. Infinite timeout will be applied when zero value is specified.<br>**Default value:** 120000     | 120000 |
| connectionRequestTimeout | String    | The timeout in milliseconds used when requesting a connection from the connection manager. Infinite timeout will be applied when zero value is specified.<br>**Default value:** 120000 | 120000 |
| responseTimeout          | String    | Determines the timeout in milliseconds until arrival of a response. Infinite timeout will be applied when zero value is specified.<br>**Default value:** 120000                        | 120000 |
| deleteWithBody           | Boolean   | Indicates that DELETE request contains body.<br>**Default value:** false                                                                                                               | false  |
| getWithBody              | Boolean   | Indicates that GET request contains body.<br>**Default value:** false                                                                                                                  | false  |

#### kafka

| Parameter               | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                             | Sample                                                                                          |
| ----------------------- | :-------- |-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------| ----------------------------------------------------------------------------------------------- |
| key                     | String    | The record key (or null if no key is specified). If this option has been configured, then it takes precedence over header KafkaConstants#KEY.                                                                                                                                                                                                                                                                           | key_sample                                                                                      |
| sslProtocol             | String    | The SSL protocol used to generate the SSLContext. Default setting is TLS, which is used for most of the cases. Allowed values in recent JVMs are TLS, TLSv1.1 and TLSv1.2. SSL, SSLv2 and SSLv3 may be supported in older JVMs, but their usage is discouraged due to known security vulnerabilities.                                                                                                                   | TLSv1.2                                                                                         |
| saslMechanism           | String    | The Simple Authentication and Security Layer (SASL) Mechanism used. For the valid values see [valid SASL mechanism values](http://www.iana.org/assignments/sasl-mechanisms/sasl-mechanisms.xhtml).                                                                                                                                                                                                                                                     | GSSAPI                                                               |
| saslJaasConfig          | String    | Expose Kafka sasl.jaas.config parameter.                                                                                                                                                                                                                                                                                                                                                                                | org.apache.kafka.common.security.plain.PlainLoginModule required username=example password=example; |
| securityProtocol        | String    | Protocol used to communicate with brokers. Possible values: SASL_PLAINTEXT, PLAINTEXT, SASL_SSL, SSL                                                                                                                                                                                                                                                                                                                    | PLAINTEXT                                                                                       |
| consumerConsistencyMode | String    | Blue-green specific parameter. Possible values: EVENTUAL – Default option when blue-green is supported. Consumers offset for candidate during promotion stays the same; GUARANTEE_CONSUMPTION - consumers offset for candidate during promotion is copied from lowest offset for this group of consumers. <br>ℹ️ **Note:** This parameter and its value should be entered manually if required.                             | EVENTUAL                                                                                        |
| sslEnabledProtocols     | String    | The list of protocols enabled for SSL connections. The default is TLSv1.2,TLSv1.3 when running with Java 11 or newer, TLSv1.2 otherwise. With the default value for Java 11, clients and servers will prefer TLSv1.3 if both support it and fallback to TLSv1.2 otherwise (assuming both support at least TLSv1.2). This default should be fine for most cases.                                                         | TLSv1,TLSv1.1,TLSv1.2                                                                           |
| sslEndpointAlgorithm    | String    | The endpoint identification algorithm to validate server hostname using server certificate.                                                                                                                                                                                                                                                                                                                             | HTTPS                                                                                           |

> ℹ️ **Note:** When message processing is slow, it could lead to interruption due to reached timeout.
> In such cases, it is recommended to specify parameter **maxPollIntervalMs**
> (The maximum delay between invocations of polls when using consumer group management.)
> with value **higher**, than default **300000** and parameter **maxPollRecords**
> (The maximum number of records returned in a single call to poll) with value **lower**, than defaulted **500**.
> This will allow to process the messages smoothly, with lower risk of timeout.

#### amqp

| Parameter  | Data Type | Description                                                                                                                               | Sample |
| ---------- | :-------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| username   | String    | Username in case of authenticated access.                                                                                                 | N/A    |
| password   | String    | Password for authenticated access.                                                                                                        | N/A    |
| routingKey | String    | The routing key to use when binding a consumer queue to the exchange. For producer routing keys, you set the header rabbitmq.ROUTING_KEY. | rkey1  |
| acknowledgeMode | String  | Flag controlling the behaviour of the container with respect to message acknowledgement. Possible values: NONE, MANUAL, AUTO | AUTO |

Additionally, if it is required to use same connection for multiple requests of **http** and **GraphQL** services, it is possible to specify **reuseEstablishedConnection** property with values: true/false.
When environment is saved, its updated item will be available under the environment tab. The environment will have next information and elements:

- **Name** - name of the environment, specified during configuration.
- **Address** - exact environment address.
- **Source** - MaaS or Manual.
- **Modified At** - datetime and author of environment modification.
- **Used by** - the list of chains where service environment is being used.

### Import Service(s)

**`⛔ Not available via VS Code extension`**

To import the service(s), click the icon ![cloud-upload](img/cloud-upload.svg), drag and drop **.zip** file into import area
or click **"browse"** link and select **single** file with respective format from the explorer menu.
When appropriate file is added to the window, click **"Import"** button to start the import process.
API Specification version in archive **must be unique** for each API Specification.
During the import, system follows next logic:
- Verify Import Instructions, saved in the system. Proceed with the step below only if they exist:
  - Fetch the list of service IDs with **ignore** action and skip import process for them.
- Find existing services, specification groups and specification by IDs from import archive:
  - If there are specifications with IDs already exist in the system, regardless of their parent specification groups and services, system **ignores** them.
  - If system already has entities with IDs, specified in import archive:
    - Merge data from archive, including **custom labels**, into existing entities.
    - **Technical labels** are going to be removed from existing entities if they are updated as a part of import process.
> ℹ️ **Note:** If import is done as a part of deployment process or it is initiated directly via API with **corresponding headers**, the current set of technical labels is **always overridden** by the values, received from import archive. This might lead to technical labels to be removed from existing entities if imported file has no corresponding technical labels for them.

- **"Discovered"** status of API specification for Inner Cloud Service will be switched to **"Manually uploaded"** as the result of manual import of the specification with the same ID.
- Otherwise, if entities, that are being imported, don't exist, they are going to be created with the next specifics:
  - For all new services system will increment standard UUID, so it is possible to operate with it in order to maintain services uniqueness.
  - For **Swagger** and **AsyncAPI** system will build specification version from "version" parameter in specification file.
  - For **WSDL**, **GraphQL** and **Protobuf** API specification's, their versions will be generated by autoincrement (1.0.0, 2.0.0, etc.), but it is also possible to manually rename specifications before import.


> ⚠️ **Warning:** When importing **grpc** services, it is absolutely required to have protobuf files properly
> structured within the archive, according to the native logic, described in public
> **Protocol Buffers Documentation Language Guide (proto 3)**. Following this logic, protobuf files must be placed
> to the folders, fully corresponding to the path, described in **"import"** statements of the files.
> For example, when protobuf file **proto_1** has a statement
> "_**import test/proto/types/active/proto_2.proto**_", it means that **proto_2** file must be placed
> to "_**test/proto/types/active/**_" folder in the archive.

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

System allows to export service with all its API specifications, environments and sources. From **"Inner Cloud Services"** page - mark specific services with checkboxes and click ![cloud-download](img/cloud-download.svg) **Export**. Or simply click this button to export all services at once after confirmation.

### Constraints

---

Please consider next constraints:
- **Technical** labels cannot be imported via UI or exported.
