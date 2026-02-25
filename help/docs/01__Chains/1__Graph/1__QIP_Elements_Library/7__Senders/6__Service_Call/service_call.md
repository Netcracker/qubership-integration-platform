# Service Call
## Description

---
**Service Call** is an element, that allows to invoke specified service, available within the instance or outside of it.

## User Interface

---
### "Endpoint" Tab

The tab is responsible for choosing service, it's API specification and operation which will be invoked. All services configured in [Services](../../../../../02__Services/services.md) are available for use.

Service Call support operations from **Swagger/WSDL/AsyncAPI/GraphQL/Protobuf** specifications.

| Parameter           | Mandatory | Data Type | Description                                                                                                                   | Sample           |
| ------------------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------------------------------------- | ---------------- |
| Integration Service | M         | List      | List with all services, available to be selected. Services without a single API specification won't be presented in the list. | Petstore         |
| API Specification   | M         | List      | List with specifications, grouped by specification groups.                                                                    | v1.0.0           |
| Operation           | M         | List      | List with all operations, available for selected API Specification.                                                           | GET /pet/{petId} |

Depending on the selected service, specification and operation, additional section(s) with parameters will appear:

- Path Parameters

| Parameter | Mandatory | Data Type | Description                                                                       | Sample                 |
| --------- | :-------- | :-------- | --------------------------------------------------------------------------------- | ---------------------- |
| Name      | O         | String    | Path parameter name.                                                              | petId                  |
| Value     | O         | String    | Path parameter value. Can be specified as exchangeProperty, constant or variable. | ${exchangeProperty.Id} |

- Query Parameters

| Parameter | Mandatory | Data Type | Description                                                                        | Sample |
| --------- | :-------- | :-------- | ---------------------------------------------------------------------------------- | ------ |
| Name      | O         | String    | Query parameter name.                                                              | limit  |
| Value     | O         | String    | Query parameter value. Can be specified as exchangeProperty, constant or variable. | 10     |

>**ℹ️Note**: The checkbox **"Skip empty parameters"** is provided to allow users to omit any empty/null values for optional query parameters specified under http protocol based service operation. By default, this checkbox is disabled for all new and existing Service Call elements.

Parameters in sections below are either predefined for selected type of service or propagated from the other sources, such as Environment settings, etc.

>**ℹ️Note**: Parameter and its value, added to the element will override the one, specified on the environment, if parameter's name matches. If mentioned override is detected, parameter will be marked with label **"overridden"** in the table under element.


- Additional Parameters

| Parameter                                                   | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                       | Sample                                |
| ----------------------------------------------------------- | --------------------------------------- | :-------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| <div style="width:200px">connectTimeout </div>              | O                                       | String                                  | <div style="width:400px">Determines the timeout in milliseconds until a connection is established.<br/><b>Default value:</b> 120000 </div>                        | <div style="width:300px">120000</div> |
| <div style="width:200px">soTimeout</div>                    | O                                       | String                                  | Defines the socket timeout in milliseconds, which is the timeout for waiting for data.  <br>**Default value:** 120000                                             | 120000                                |
| <div style="width:200px">connectionRequestTimeout</div>     | O                                       | String                                  | <div style="width:400px">The timeout in milliseconds used when requesting a connection from the connection manager.<br/><b>Default value:</b> 120000</div>        | <div style="width:300px">120000</div> |
| responseTimeout                                             | O                                       | String                                  | Determines the timeout in milliseconds until arrival of a response. Infinite timeout will be applied when zero value is specified.  <br>**Default value:** 120000 | 120000                                |
| deleteWithBody                                              | O                                       | Boolean                                 | Indicates that DELETE request contains body.                                                                                                                      | false                                 |
| getWithBody                                                 | O                                       | Boolean                                 | Indicates that GET request contains body.                                                                                                                         | false                                 |
| <div style="width:200px"> reuseEstablishedConnection </div> | O                                       | Boolean                                 | <div style="width:400px">Enable ability to use same connection for multiple HTTP services requests.<br/><b>Default value:</b> false</div>                         | false                                 |

- Kafka Parameters

| Parameter                                           | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                         | Sample                                            |
| --------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| <div style="width:150px">topic</div>                | M                                       | String                                  | <div style="width:400px">Name of the topic to use. On the consumer you can use comma to separate multiple topics. A producer can only send a message to a single topic.</div>                                       | <div style="width:350px">sample-kafka-topic</div> |
| <div style="width:150px">maas.classifier.name</div> | M                                       | String                                  | <div style="width:400px">Topic classifier name. Parameter is only available for MaaS connection type.	</div>                                                                                                        | <div style="width:350px">topic1-classifier</div>  |
| maas.classifier.namespace                           | O                                       | String                                  | Specifies classifier namespace, that shall be used instead of default one. If left empty, default namespace will be utilized. Only works, when MaaS has a security permission rule to access a different namespace. | newNamespace                                      |
| maas.classifier.tenantEnabled                       | M                                       | Boolean                                 | Checkbox enables "tenantId" field in classifier.<br>**Default value**: false                                                                                                                                        | false                                             |
| maas.classifier.tenantId                            | O                                       | String                                  | Specifies tenant unique identifier. If not specified, default value will be used. Only works, when **"maas.classifier.tenantEnabled"** is "true".                                                                   | d334cf82-11aa4vz9-a1a6-ba9f6aa06e09               |

- gRPC Parameters

| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                | Sample                               |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| <div style="width:150px">synchronous</div> | M                                       | Boolean                                 | <div style="width:400px">Checkbox, that defines synchronicity of the service.<br/>**Default:** false</div> | <div style="width:350px">false</div> |

>**⚠️Warning:** Service call via gRPC does not support sending of any custom headers.

- RabbitMQ Parameters

| Parameter            | Mandatory | Data Type | Description                                                                                               | Sample             |
| -------------------- | :-------- | :-------- | --------------------------------------------------------------------------------------------------------- | ------------------ |
| exchangeName         | M         | String    | The exchange name determines the exchange, produced messages will be sent to.                             | sample-exchange-v1 |
| maas.classifier.name | M         | String    | Vhost classifier name. Parameter is only available for MaaS connection type.<br>**Default value:** public | public             |

- GraphQL Query

| Parameter      | Mandatory | Data Type | Description                                                                                                                 | Sample        |
| -------------- | :-------- | :-------- | --------------------------------------------------------------------------------------------------------------------------- | ------------- |
| Operation Name | O         | String    | The query or mutation name. Optional if query contains a single operation.                                                  | GetTicketById |
| Query          | M         | String    | GraphQL query, required to be executed.<br><br>**ℹ️Note:** More than one query or mutation can be entered at the same time. | N/A           |
| Variables JSON | O         | String    | The JsonObject instance, that contains the operation variables. Camel Exchange variables can also be used.                  | N/A           |

<style>
summary {
  display: list-item;
  list-style: disclosure-closed inside;
  cursor: pointer;
}
details[open] > summary {
  list-style: disclosure-open inside;
}
</style>
<details open><summary>Query sample</summary>
<pre style="background-color: #F5F5F7"><code  style="color: #000000">query getVehicle { 
    vehicles(count: $countvar) {
        id
        type
        modelCode
    }
}
</code></pre>

**Variables:**
<pre style="background-color: #F5F5F7"><code  style="color: #000000">{
    "countvar": 2
}
</code></pre>
</details>

-  Body
   Specifies the way of request body formation. Possible values:
   * **None** - removes the body from the message.
   * **Inherit** - passes the request body from Camel Exchange as is. Default option.
   * **multipart/form-data** - utilizes composite content type, built by specifying multiple pair of fields and their values:

| Parameter | Mandatory | Data Type | Description                                                                                                                       | Sample             |
| --------- | :-------- | :-------- | --------------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| Name      | M         | String    | Name of a section/part in the multipart message.                                                                                  | exchangeId         |
| MIME type | M         | List      | Customizable list with possible options of MIME types. This fields also allows entering the types, that do not exist in the list. | "application/json" |
| File name | O         | String    | When section contains a file, its name must be specified in this field.                                                           | ${exchangeId}.json |
| Value>    | M         | String    | Value of the section, depending on selected MIME type. When file is being sent, its content must be entered in bytes.             | ${bodyAs(byte[])}  |

* **application/x-www-form-urlencoded** - message is formed as one query string, where name/value pairs are separated by "&".  Generally used for small sized text-based payloads. When this option is selected, system allows to specify key\pair values via table:

| Parameter | Mandatory | Data Type | Description                                                                                                                                                                    | Sample   |
| --------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------- |
| Name      | M         | String    | Specifies key name. Non-alphanumeric characters will be URL encoded.                                                                                                           | FieldOne |
| Value     | M         | String    | Specifies key value. Non-alphanumeric characters will be URL encoded.<br><br>**ℹ️Note**: Large values must not be entered due to technical limitations of the encoding method. | ValueOne |

### "Prepare request" Tab

The tab is responsible for choosing an action on receiving the request. Possible actions:
- **None** - no specific actions.
- **Scripting** - groovy script (specified in the code block) will be executed on request. More additional information available in [Script](../../5__Transformation/1__Script/script.md).

    >**ℹ️Note**: **Some element's details are stored in Camel Exchange properties** and available for usage locally via every "Scripting" module under the Service Call. Such properties (specific ones for the protocol) are listed in the next table (click on the expandable section below):
    >  <details><summary>Service Call exchange properties</summary>   <table cellspacing="2" border="1" cellpadding="5">
    >     <thead>
    >        <tr>
    >            <th>Protocol</th>
    >            <th>Property name</th>
    >            <th>Property description</th>
    >        </tr>
    >    </thead>
    >    <tbody>
    >        <tr>
    >            <td rowspan="7"> <strong>HTTP</strong> </td>
    >            <td>serviceCallMethod</td>
    >            <td>HTTP method</td>
    >        </tr>
	>            <td>serviceCallSkipEmptyQueryParams</td>
    >            <td>Property indicates whether the "Skip empty query parameters" option in Service Call element is checked/unchecked.</td>
    >        </tr>
    >        <tr>
    >            <td>serviceCallUrl</td>
    >            <td>Constructed URL for HTTP call<br><br>ℹ️Note: If <b>"Skip empty query parameters"</b> option is checked, any query parameters specified will be excluded from the constructed URL.<br> In case above option is unchecked, all specified query parameters <i>(including parameters having null/empty values)</i> will be included in the constructed URL.
	                </td>
            </tr>
             <tr>
                <td>serviceCallAddress</td>
                <td>Address part of an URL, resolved from service environment</td>
            </tr>
             <tr>
                <td>serviceCallPath</td>
                <td>Path for operation with path parameters placeholders</td>
            </tr>
             <tr>
                <td>serviceCallQueryParameter_&lt;parameter&gt;</td>
                <td>Property for each query parameter, where &lt;parameter&gt; substring is a pure query param name (e.g. <i>serviceCallQueryParameter_limit</i>)</td>
            </tr>
             <tr>
                <td>serviceCallPathParameter_&lt;parameter&gt;</td>
                <td>Property for each path parameter, where &lt;parameter&gt; substring is a pure path param name (e.g. <i>serviceCallQueryParameter_orders</i>)</td>
            </tr>
             <tr>
                <td>serviceCallParameter_&lt;parameter&gt;</td>
                <td>Property for each additional service call parameter, where &lt;parameter&gt; substring is a pure parameter name (e.g. <i>serviceCallQueryParameter_connectTimeout</i>)</td>
            </tr>
            <tr>
                <td rowspan="4"><strong>Kafka</strong></td>
                <td>serviceCallMethod</td>
                <td>AsyncAPI operation method</td>
            </tr>
            <tr>
                <td>serviceCallTopic</td>
                <td>Kafka topic name</td>
            </tr>
            <tr>
                <td>serviceCallBrokers</td>
                <td>Kafka brokers</td>
            </tr>
            <tr>
                <td>serviceCallParameter_&lt;parameter&gt;</td>
                <td>Property for each additional service call parameter, where &lt;parameter&gt; substring is a pure parameter name (e.g. <i>serviceCallQueryParameter_connectTimeout</i>)</td>
            </tr>
             <tr>
                <td rowspan="4"><strong>AMQP</strong></td>
                <td>serviceCallMethod</td>
                <td>AsyncAPI operation method</td>
            </tr>
            <tr>
                <td>serviceCallExchange</td>
                <td>RabbitMQ exchange name</td>
            </tr>
            <tr>
                <td>serviceCallAddress</td>
                <td>Server addresses</td>
            </tr>
            <tr>
                <td>serviceCallParameter_&lt;parameter&gt;</td>
                <td>Property for each additional service call parameter, where &lt;parameter&gt; substring is a pure parameter name (e.g. <i>serviceCallQueryParameter_connectTimeout</i>)</td>
            </tr>
            <tr>
                <td rowspan="4"><strong>gRPC</strong></td>
                <td>serviceCallMethod</td>
                <td>Service method to call</td>
            </tr>
            <tr>
                <td>serviceCallService</td>
                <td>Service name</td>
            </tr>
            <tr>
                <td>serviceCallAddress</td>
                <td>Server address</td>
            </tr>
            <tr>
                <td>serviceCallParameter_&lt;parameter&gt;</td>
                <td>Property for each additional service call parameter, where &lt;parameter&gt; substring is a pure parameter name (e.g. <i>serviceCallQueryParameter_connectTimeout</i>)</td>
            </tr>
            <tr>
                <td rowspan="5"><strong>GraphQL</strong></td>
                <td>serviceCallAddress</td>
                <td>Server address</td>
            </tr>
            <tr>
                <td>serviceCallMethod</td>
                <td>HTTP method</td>
            </tr>
            <tr>
                <td>serviceCallPath</td>
                <td>Operation path</td>
            </tr>
            <tr>
                <td>serviceCallQueryParameter_&lt;parameter&gt;</td>
                <td>Property for each query parameter, where &lt;parameter&gt; substring is a pure query param name (e.g. <i>serviceCallQueryParameter_operationName</i>)</td>
            </tr>
            <tr>
                <td>serviceCallParameter_&lt;parameter&gt;</td>
                <td>Property for each additional service call parameter, where &lt;parameter&gt; substring is a pure parameter name (e.g. <i>serviceCallQueryParameter_connectTimeout</i>)</td>
            </tr>
        </tbody>
   ></table>   
  ></details>

- **Mapping** - specific mapping rules will be applied on request, with no possibility to edit schemes, that come with service call. More additional information available in [Mapper](../../5__Transformation/2__Mapper/mapper.md).

>**ℹ️Note**: **For GraphQL** and **Protobuf** specification there is no ability to select request body schema for mapping from the API Specification. Please, define it manually.

### "Authorization" Tab

This tab allows to set up an authorization for service call.

Possible selectable options:
- **Inherit** - default option, that allows to keeps exchange Authorization header and its value
- **None** - removes auth settings (removes Authorization header)
- **Basic Auth** - basic authorization with username and password
- **Bearer Token** - authorization with bearer token
- **M2M Token** - service call will put machine to machine token into Authorization header.

| Parameter | Mandatory | Data Type | Description                                                                                                       | Sample                    |
| --------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------- |
| Token     | O         | String    | Available for **"Bearer Token"** option. This fields is supposed to contain a token.                              | ${exchangeProperty.token} |
| Username  | O         | String    | Available for **"Basic Auth"** option. This fields is supposed to contain a username to be used in authorization. | #{username}               |
| Password  | O         | String    | Available for **"Basic Auth"** option. This fields is supposed to contain a password to be used in authorization. | #{password}               |

### "Validations" Tab

This tab allows to set up scheme validation(s) for service's response in order to instantly fail the chain without its further processing in case of receiving invalid response. Validation(s) must be added for specific pair of code and content type, accessed by clicking "**Add**" button. Only codes from range 200-299, which are **properly and fully described** in the response scheme and have **Json** - based content type are available for applying validations against. Validation will only happen for service's response when a pair of HTTP code and content type in response fully matches with the settings, added under Validation tab, otherwise validation will be skipped.

### "Handle Validation Failure" Tab

This tab is specifically designed to control validation message format, that will be returned in case of validation failure. There are three options of handling, presented as values for "**Actions**" list:

- **Default** - default message will be presented in the response, when validation fails.
- **Mapper** - system shows mapper interface with default message structure in the source section. Customized message could be built in the target section if required.
- **Scripting** - system presents script box for custom message building.

### "Handle Response" Tab

The tab is responsible for configuring the handling logic based on response code, which can either be selected from list of predefined response codes in API Specification or new one (custom) can be created by entering the code number and clicking **`Enter`** button. Once it is defined, next actions become available for selection:
- **None** - no specific actions will be performed.
- **Scripting** - script, specified in the code block, will be executed for added code/range. Additional information is available in specialized section: [Script](../../5__Transformation/1__Script/script.md).
- **Mapping** - system shows mapper interface, that allows the structure of the response message to be mapped to the desired/target message structure, while also applying transformations, if necessary. Specific mapping rules will be applied to added code/range, with no possibility to edit schemes, that come with service call. Additional information is available in specialized section: [Mapper](../../5__Transformation/2__Mapper/mapper.md).

There is also "**Throw exception on transformation failure**" checkbox available on the tab, when option "**Mapping**" is selected. When it is checked, Integration Platform throws an exception if data transformation fails during chain processing.

>ℹ️**Note:** For **GraphQL** and **Protobuf** specification there is no ability to select response body schema for mapping from the API Specification. Please, define it manually.

### "Parameters" Tab

The tab is responsible for configuring common Service call parameters.

#### Common Parameters
| Parameter        | Mandatory | Data Type | Description                                                                        | Sample |
| ---------------- | :-------- | :-------- | ---------------------------------------------------------------------------------- | ------ |
| Retry count      | M         | Number    | Specifies the number of retries for the call before it is considered to be failed. | 1      |
| Retry delay (ms) | M         | Number    | Specifies delay between retries in milliseconds.                                   | 2000   |

#### Advanced Parameters
| Parameter               | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | Sample           |
| ----------------------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------- |
| Receive correlation id  | M         | Boolean   | Checkbox, that enables ability to define correlation id.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | N/A              |
| Correlation Id Position | C         | List      | Position of correlation id in request. Possible values:<ul><li>Header</li><li>Body</li></ul>Visible if **"Receive correlation id"** checkbox is marked.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | Header           |
| Correlation Id Key      | C         | String    | The exact name of the header or body parameter, that holds correlation id value.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | correlationIdKey |
| Propagate context       | M         | Boolean   | Checkbox, that defines if context to special ("Technical") headers before sending message will be propagated or not.<ul><li>If **checked** (default): context to this call will be propagated, which will lead to the reinstatement of all technical headers, that are stored in context.</li><li>If **unchecked**: call propagation will be switched off, hence values of technical headers, that are stored in the context won't be reinstated.</li></ul>Additionally, when **"Propagate context"** is checked, **"Override Technical Context Headers"** table becomes available to the user. This table allows to override the value for the specific header, that has been propagated from context. <br><br>**ℹ️Note**: For the actual list of technical headers, please, contact system administrator. | N/A              |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                  |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | --------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | Get Session Status                      |
| Description | O         | String    | Free text field, that contains description of the element. | Service call to the sessions management |


## Constraints

---
Header "Authorization" will be removed by the system before sending the message to the Kafka or RabbitMQ services.
