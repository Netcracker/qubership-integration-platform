# Service Call
## Description

---
**Service Call** is an element, that allows to invoke specified service, available within the instance or outside of it.

## User Interface

---
### "Endpoint" Tab
The tab is responsible for choosing service, it's API specification and operation which will be invoked. All services configured in [Services](docs/02__Services/services.md) are available for use.

Service Call support operations from **Swagger/WSDL/AsyncAPI/GraphQL/Protobuf** specifications.

| Parameter                                          | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                  | Sample                                          |
| -------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------- |
| <div style="width:150px">Integration Service</div> | M                                       | List                                    | <div style="width:400px">List with all services, available to be selected. Services without a single API specification won't be presented in the list.</div> | <div style="width:350px">Petstore</div>         |
| <div style="width:150px">API Specification</div>   | M                                       | List                                    | <div style="width:400px">List with specifications, grouped by specification groups.</div>                                                                    | <div style="width:350px">v1.0.0</div>           |
| <div style="width:150px">Operation</div>           | M                                       | List                                    | <div style="width:400px">List with all operations, available for selected API Specification.</div>                                                           | <div style="width:350px">GET /pet/{petId}</div> |

Depending on the selected service, specification and operation, additional section(s) with parameters will appear:

- Path Parameters

| Parameter                            | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                      | Sample                                                |
| ------------------------------------ | :-------------------------------------- | :-------------------------------------- | ---------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| <div style="width:150px">Name</div>  | O                                       | String                                  | <div style="width:400px">Path parameter name.</div>                                                              | <div style="width:350px">petId</div>                  |
| <div style="width:150px">Value</div> | O                                       | String                                  | <div style="width:400px">Path parameter value. Can be specified as exchangeProperty, constant or variable.</div> | <div style="width:350px">${exchangeProperty.Id}</div> |

- Query Parameters

| Parameter                            | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                       | Sample                               |
| ------------------------------------ | :-------------------------------------- | :-------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| <div style="width:150px">Name</div>  | O                                       | String                                  | <div style="width:400px">Query parameter name.</div>                                                              | <div style="width:350px">limit</div> |
| <div style="width:150px">Value</div> | O                                       | String                                  | <div style="width:400px">Query parameter value. Can be specified as exchangeProperty, constant or variable.</div> | <div style="width:350px">10</div>    |

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>The checkbox <b>"Skip empty parameters"</b> is provided to allow users to omit any empty/null values for optional query parameters specified under http protocol based service operation. By default, this checkbox is disabled for all new and existing Service Call elements.</div>

Parameters in sections below are either predefined for selected type of service or propagated from the other sources, such as Environment settings, etc.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>Parameter and its value, added to the element will override the one, specified on the environment, if parameter's name matches. If mentioned override is detected, parameter will be marked with label <b>"overridden"</b> in the table under element.</div>


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
| maas.classifier.tenantEnabled                       | M                                       | Boolean                                 | Checkbox enables "tenantId" field in classifier.<br><br>**Default value**: false                                                                                                                                    | false                                             |
| maas.classifier.tenantId                            | O                                       | String                                  | Specifies tenant unique identifier. If not specified, default value will be used. Only works, when **"maas.classifier.tenantEnabled"** is "true".                                                                   | d334cf82-11aa4vz9-a1a6-ba9f6aa06e09               |

- gRPC Parameters

| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                | Sample                               |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| <div style="width:150px">synchronous</div> | M                                       | Boolean                                 | <div style="width:400px">Checkbox, that defines synchronicity of the service.<br/>**Default:** false</div> | <div style="width:350px">false</div> |

<div style="background-color: #fff1f0; border-left: 6px solid #ff4538; padding: 10px"><b>Note:</b><br>Service call via gRPC does not support sending of any custom headers</div>

- RabbitMQ Parameters

| Parameter                                           | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                  | Sample                                            |
| --------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| <div style="width:150px">exchangeName</div>         | M                                       | String                                  | <div style="width:400px">The exchange name determines the exchange, produced messages will be sent to.</div>                                 | <div style="width:350px">sample-exchange-v1</div> |
| <div style="width:150px">maas.classifier.name</div> | M                                       | String                                  | <div style="width:400px">Vhost classifier name. Parameter is only available for MaaS connection type.</br> **Default value:** public  </div> | <div style="width:350px">public</div>             |

- GraphQL Query

| Parameter                                     | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                             | Sample                                       |
| --------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| <div style="width:150px">Operation Name</div> | O                                       | String                                  | <div style="width:400px">The query or mutation name. Optional if query contains a single operation.</div>                                                                                                                                               | <div style="width:350px">GetTicketById</div> |
| <div style="width:150px">Query</div>          | M                                       | String                                  | <div style="width:400px">GraphQL query, required to be executed.</br><div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>More than one query or mutation can be entered at the same time.</div></div> | N/A                                          |
| <div style="width:150px">Variables JSON</div> | O                                       | String                                  | <div style="width:400px">The JsonObject instance, that contains the operation variables. Camel Exchange variables can also be used.</div>                                                                                                               | N/A                                          |

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

<b>Variables:</b>
<pre style="background-color: #F5F5F7"><code  style="color: #000000">{
    "countvar": 2
}
</code></pre>
</details>
<br/>

-  Body<br>
   Specifies the way of request body formation. Possible values:
<ul>
<li><b>None</b> - removes the body from the message.</li>
<li><b>Inherit</b> - passes the request body from Camel Exchange as is. Default option.</li>
<li><b>multipart/form-data</b> - utilizes composite content type, built by specifying multiple pair of fields and their values:</li>
</ul>


| Parameter                                | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                      | Sample                                            |
| ---------------------------------------- | :-------------------------------------- | :-------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| <div style="width:150px">Name</div>      | M                                       | String                                  | <div style="width:400px">Name of a section/part in the multipart message.</div>                                                                                  | <div style="width:350px">exchangeId</div>         |
| <div style="width:150px">MIME type</div> | M                                       | List                                    | <div style="width:400px">Customizable list with possible options of MIME types. This fields also allows entering the types, that do not exist in the list.</div> | <div style="width:350px">"application/json"</div> |
| <div style="width:150px">File name</div> | O                                       | String                                  | <div style="width:400px">When section contains a file, its name must be specified in this field.</div>                                                           | <div style="width:350px">${exchangeId}.json</div> |
| <div style="width:150px">Value</div>     | M                                       | String                                  | <div style="width:400px">Value of the section, depending on selected MIME type. When file is being sent, its content must be entered in bytes.</div>             | <div style="width:350px">${bodyAs(byte[])}</div>  |

<ul><ul>
<li><b>application/x-www-form-urlencoded</b> - message is formed as one query string, where name/value pairs are separated by "&".  Generally used for small sized text-based payloads. When this option is selected, system allows to specify key\pair values via table:</li>
</ul></ul>

| Parameter                            | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                                                           | Sample                                  |
| ------------------------------------ | :-------------------------------------- | :-------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- |
| <div style="width:150px">Name</div>  | M                                       | String                                  | <div style="width:400px">Specifies key name. Non-alphanumeric characters will be URL encoded.</div>                                                                                                                                                                                                   | <div style="width:350px">FieldOne</div> |
| <div style="width:150px">Value</div> | M                                       | String                                  | <div style="width:400px">Specifies key value. Non-alphanumeric characters will be URL encoded.<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br>Large values must not be entered due to technical limitations of the encoding method.</div></div> | <div style="width:350px">ValueOne</div> |

### "Prepare request" Tab
The tab is responsible for choosing an action on receiving the request. Possible actions:
- **None** - no specific actions.
- **Scripting** - groovy script (specified in the code block) will be executed on request. More additional information available in [Script](docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/1__Script/script.md).
    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    <b>Note:</b><br>
       <b>Some element's details are stored in Camel Exchange properties</b> and available for usage locally via every "Scripting" module under the Service Call. Such properties (specific ones for the protocol) are listed in the next table (click on the expandable section below):
    <details><summary>Service Call exchange properties</summary>
         <table cellspacing="2" border="1" cellpadding="5">
         <thead>
            <tr>
                <th>Protocol</th>
                <th>Property name</th>
                <th>Property description</th>
            </tr>
        </thead>
        <tbody>
            <tr>
                <td rowspan="7"> <strong>HTTP</strong> </td>
                <td>serviceCallMethod</td>
                <td>HTTP method</td>
            </tr>
	            <td>serviceCallSkipEmptyQueryParams</td>
                <td>Property indicates whether the "Skip empty query parameters" option in Service Call element is checked/unchecked.</td>
            </tr>
            <tr>
                <td>serviceCallUrl</td>
                <td>Constructed URL for HTTP call<br><br><div style="background-color: #fffde7; border-left: 6px solid #fff36b; padding: 10px"><b>Note:</b><br>If <b>"Skip empty query parameters"</b> option is checked, any query parameters specified will be excluded from the constructed URL.<br> In case above option is unchecked, all specified query parameters <i>(including parameters having null/empty values)</i> will be included in the constructed URL.</div>
                    <br/></td>
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
    </table>   
  </details>
  </div><br>

- **Mapping** - specific mapping rules will be applied on request, with no possibility to edit schemes, that come with service call. More additional information available in [Mapper](docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/mapper.md).
    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    <b>Note:</b><br>
    <b>For GraphQL</b> and <b>Protobuf</b> specification there is no ability to select request body schema for mapping from the API Specification. Please, define it manually.
    </div>

### "Authorization" Tab
This tab allows to set up an authorization for service call.

Possible selectable options:
- **Inherit** - default option, that allows to keeps exchange Authorization header and its value
- **None** - removes auth settings (removes Authorization header)
- **Basic Auth** - basic authorization with username and password
- **Bearer Token** - authorization with bearer token
- **M2M Token** - service call will put machine to machine token into Authorization header.

| Parameter                               | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                         | Sample                                                   |
| --------------------------------------- | :-------------------------------------- | :-------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| <div style="width:150px">Token</div>    | O                                       | String                                  | <div style="width:400px">Available for <b>"Bearer Token"</b> option. This fields is supposed to contain a token.</div>                              | <div style="width:350px">${exchangeProperty.token}</div> |
| <div style="width:150px">Username</div> | O                                       | String                                  | <div style="width:400px">Available for <b>"Basic Auth"</b> option. This fields is supposed to contain a username to be used in authorization.</div> | <div style="width:350px">#{username}</div>               |
| <div style="width:150px">Password</div> | O                                       | String                                  | <div style="width:400px">Available for <b>"Basic Auth"</b> option. This fields is supposed to contain a password to be used in authorization.</div> | <div style="width:350px">#{password}</div>               |

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
- **Scripting** - script, specified in the code block, will be executed for added code/range. Additional information is available in specialized section: [Script](docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/1__Script/script.md).
- **Mapping** - system shows mapper interface, that allows the structure of the response message to be mapped to the desired/target message structure, while also applying transformations, if necessary. Specific mapping rules will be applied to added code/range, with no possibility to edit schemes, that come with service call. Additional information is available in specialized section: [Mapper](docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/mapper.md).

There is also "**Throw exception on transformation failure**" checkbox available on the tab, when option "**Mapping**" is selected. When it is checked, Integration Platform throws an exception if data transformation fails during chain processing.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
For <b>GraphQL</b> and <b>Protobuf</b> specification there is no ability to select response body schema for mapping from the API Specification. Please, define it manually.
</div>

### "Parameters" Tab
The tab is responsible for configuring common Service call parameters.

#### Common Parameters
| Parameter                                       | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                       | Sample                              |
| ----------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| <div style="width:150px">Retry count</div>      | M                                       | Number                                  | <div style="width:400px">Specifies the number of retries for the call before it is considered to be failed.</div> | <div style="width:350px">1</div>    |
| <div style="width:150px">Retry delay (ms)</div> | M                                       | Number                                  | <div style="width:400px">Specifies delay between retries in milliseconds.</div>                                   | <div style="width:350px">2000</div> |

#### Advanced Parameters
| Parameter                                              | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | Sample                                          |
| ------------------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| <div style="width:150px">Receive correlation id</div>  | M                                       | Boolean                                 | <div style="width:400px">Checkbox, that enables ability to define correlation id.</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | <div style="width:350px">N/A</div>              |
| <div style="width:150px">Correlation Id Position</div> | C                                       | List                                    | <div style="width:400px">Position of correlation id in request. <br/>Possible values:<ul><li>Header</li><li>Body</li></ul>Visible if "<b>Receive correlation id</b>" checkbox is marked.</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | <div style="width:350px">Header</div>           |
| <div style="width:150px">Correlation Id Key</div>      | C                                       | String                                  | <div style="width:400px">The exact name of the header or body parameter, that holds correlation id value.</div>                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | <div style="width:350px">correlationIdKey</div> |
| <div style="width:150px">Propagate context</div>       | M                                       | Boolean                                 | <div style="width:400px">Checkbox, that defines if context to special ("Technical") headers before sending message will be propagated or not.<ul><li>If <b>checked</b> (default): context to this call will be propagated, which will lead to the reinstatement of all technical headers, that are stored in context.</li><li>If <b>unchecked</b>: call propagation will be switched off, hence values of technical headers, that are stored in the context won't be reinstated.</li></ul>Additionally, when <b>"Propagate context"</b> is checked, <b>"Override Technical Context Headers"</b> table becomes available to the user. This table allows to override the value for the specific header, that has been propagated from context. <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Note:</b><br> For the actual list of technical headers, please, contact system administrator.</div></div> | <div style="width:350px">N/A</div>              |

#### Metadata
| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                               | Sample                                                                 |
| ------------------------------------------ | :-------------------------------------- | :-------------------------------------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| <div style="width:150px">Name</div>        | M                                       | String                                  | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">Get Session Status</div>                      |
| <div style="width:150px">Description</div> | O                                       | String                                  | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">Service call to the sessions management</div> |


## Constraints

---
Header "Authorization" will be removed by the system before sending the message to the Kafka or RabbitMQ services.