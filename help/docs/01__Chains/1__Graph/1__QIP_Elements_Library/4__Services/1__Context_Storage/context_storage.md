# Context Storage

## Description

---
Context Storage provides an ability to manage context data through the chain using a [Context Services](../../../../../02__Services/4__Context/context.md).

>⚠️**Warning:**
>Context Storage **shall NOT** be used to store/manage sensitive data.

## User Interface

---
### "Operations" Tab

| Parameter                        | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                  | Sample                |
| -------------------------------- | --------- | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- |
| Context Storage                  | M         | List      | Parameter to choose particular Context Service from the list of available ones.                                                                                                                                                                                                                                              | Redis context storage |
| Context ID                       | C         | String    | Unique identifier of chain context. Supports Camel Simple language Field is unavailable if "Use correlation ID as session context ID" is checked.<br>The full context key has the following format:<br>`context:\<context-service-id\>:\<context-id\></code>` (e.g. `context:870934b3-5294-46ff-ae74-efef2f84089b:Order125`) | Order125              |
| Use correlation Id as context Id | M         | Boolean   | If specified, then user will be able to use correlation id as a context id. When this option is selected, parameter **"Context ID"** becomes unavailable.<br>**Default value:** unchecked.                                                                                                                                   | N/A                   |
| Operation                        | M         | List      | List, that contains all available context operations. Possible values:<li>Create context</li><li>Get context</li><li>Delete context</li>                                                                                                                                                                                     | Get context           |

Depending on selected operation, **additional** parameters will be presented:
- **Create context**

| Parameter    | Mandatory | Data Type | Description                                                                                                                              | Sample      |
| ------------ | --------- | --------- | ---------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| Key          | M         | String    | Mandatory key name to store the context.                                                                                                 | Context Key |
| Value        | O         | String    | Context value. When blank value is passed and there is a context data already exist for given session id, this data will be cleared out. | Text        |
| Lifetime (s) | M         | Integer   | Time period in seconds by which Context is active.<br>**Default value:** 21600<br><br>ℹ️**Note**: Value cannot be less than 1 second.    | 21600       |

>**ℹ️Note**: Updating any parameter (Key, Value or Lifetime) renews the object’s expiration time, setting it to **`currentTime + lifeTime`**

- **Get context**

| Parameter           | Mandatory | Data Type | Description                                                                                                                                       | Sample      |
| ------------------- | --------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| Keys                | O         | List      | Custom list. Keys that shall be returned in the response.                                                                                         | Field1      |
| Target              | O         | List      | Specifies where chain context details will be placed:<li>Body</li><li>Header</li><li>Property</li>                                                | Body        |
| Target field's name | C         | List      | Exact target field's name where context details will be placed. The field becomes unavailable when 'Body' is selected for the "Target" parameter. | contextData |
| Unwrap              | M         | Boolean   | Available only when single key is requested. When checkbox is marked, key will be received as a string and won't be wrapped to array.             | N/A         |

- **Delete context**<br>
  No additional parameters available for operation. It is enough to just pass context identifier.

### "Parameters" Tab

#### Metadata

| Parameter   | Mandatory | Data Type | Description                                                | Sample                                |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | Create Customer Context Storage       |
| Description | O         | String    | Free text field, that contains description of the element. | Element allows to manage context data |

## Constraints

---
There are no specific constraints for the element.
