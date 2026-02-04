# Context Storage

## Description

---
Context Storage provides an ability to manage context data through the chain using a [Context Services](docs/02__Services/4__Context/context.md).

<div style="background-color: #ffdedb; border-left: 6px solid #ff4538; padding: 10px"><b>Warning:</b><br>Context Storage <b>shall NOT</b> be used to store/manage sensitive data.</div>

## User Interface

---
### "Operations" Tab

| Parameter                                                       | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | Sample                                               |
| --------------------------------------------------------------- | --------------------------------------- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| <div style="width:150px">Context Storage</div>                  | M                                       | List                                    | <div style="width:400px">Parameter to choose particular Context Service from the list of available ones.</div>                                                                                                                                                                                                                                                                                                                                                                  | <div style="width:350px">Redis context storage</div> |
| <div style="width:150px">Context ID</div>                       | C                                       | String                                  | <div style="width:400px">Unique identifier of chain context. Supports Camel Simple language Field is unavailable if "Use correlation ID as session context ID" is checked.<br/><div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">The full context key has the following format:</br><b><code>context:\<context-service-id\>:\<context-id\></code></b> (e.g. <code>context:870934b3-5294-46ff-ae74-efef2f84089b:Order125</code>)</div></div> | <div style="width:350px">Order125</div>              |
| <div style="width:150px">Use correlation Id as context Id</div> | M                                       | Boolean                                 | <div style="width:400px">If specified, then user will be able to use correlation id as a context id. When this option is selected, parameter "<b>Context ID</b>" becomes unavailable.<br/><b>Default value:</b> unchecked.</div>                                                                                                                                                                                                                                                | <div style="width:350px">N/A</div>                   |
| <div style="width:150px">Operation</div>                        | M                                       | List                                    | <div style="width:400px">List, that contains all available context operations. Possible values:<li>Create context</li><li>Get context</li><li>Delete context</li></div>                                                                                                                                                                                                                                                                                                         | <div style="width:350px">Get context</div>           |

Depending on selected operation, **additional** parameters will be presented:
- **Create context**

| Parameter                                   | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                                               | Sample                                     |
|---------------------------------------------|-----------------------------------------|-----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| <div style="width:150px">Key</div>          | M                                       | String                                  | <div style="width:400px">Mandatory key name to store the context.	</div>                                                                                                                                                                                  | <div style="width:350px">Context Key</div> |
| <div style="width:150px">Value</div>        | O                                       | String                                  | <div style="width:400px">Context value. When blank value is passed and there is a context data already exist for given session id, this data will be cleared out.</div>                                                                                   | <div style="width:350px">Text</div>        |
| <div style="width:150px">Lifetime (s)</div> | M                                       | Integer                                 | <div style="width:400px">Time period in seconds by which Context is active.<br/><b>Default value:</b> 21600<br/><br/><div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">Value cannot be less than 1 second</div></div> | <div style="width:350px">21600</div>       |

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">Updating any parameter (Key, Value or Lifetime) renews the object’s expiration time, setting it to <b><code>currentTime + lifeTime</code></b></div>

- **Get context**

| Parameter                                          | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                      | Sample                                     |
|----------------------------------------------------|-----------------------------------------|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| <div style="width:150px">Keys</div>                | O                                       | List                                    | <div style="width:400px">Custom list. Keys that shall be returned in the response.	</div>                                                                                        | <div style="width:350px">Field1</div>      |
| <div style="width:150px">Target</div>              | O                                       | List                                    | <div style="width:400px">Specifies where chain context details will be placed:<li>Body</li><li>Header</li><li>Property</li></div>                                                | <div style="width:350px">Body </div>       |
| <div style="width:150px">Target field's name</div> | C                                       | List                                    | <div style="width:400px">Exact target field's name where context details will be placed. The field becomes unavailable when 'Body' is selected for the "Target" parameter.</div> | <div style="width:350px">contextData</div> |
| <div style="width:150px">Unwrap </div>             | M                                       | Boolean                                 | <div style="width:400px">Available only when single key is requested. When checkbox is marked, key will be received as a string and won't be wrapped to array.</div>             | <div style="width:350px">N/A</div>         |

- **Delete context**<br>
  No additional parameters available for operation. It is enough to just pass context identifier.

### "Parameters" Tab

#### Metadata

| Parameter                                  | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                               | Sample                                                               |
|--------------------------------------------|:----------------------------------------|:----------------------------------------|-------------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| <div style="width:150px">Name</div>        | M                                       | String                                  | <div style="width:400px">Name of the element.</div>                                       | <div style="width:350px">Create Customer Context Storage</div>       |
| <div style="width:150px">Description</div> | O                                       | String                                  | <div style="width:400px">Free text field, that contains description of the element.</div> | <div style="width:350px">Element allows to manage context data</div> |

## Constraints

---
There are no specific constraints for the element.