# MCP Trigger
## Description

---
**MCP Trigger** allows to expose the Chain as an **MCP tool** for clients that use the **Model Context Protocol (MCP)**. The trigger is placed at the beginning of a chain and starts chain processing when an MCP client calls the configured tool.

> **Note:** According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md).

## User Interface

---
### "Parameters" Tab
On this tab user can set the MCP parameters by which chain could be triggered.

| Parameter                                                          | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                       | Sample                                   |
| ------------------------------------------------------------------ | :-------- | :-------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| MCP services                                                       | M         | List      | List of MCP services, available for selection. Select one or more services that will expose this trigger to MCP clients.                                                                                                                                                           | Customer MCP service                     |
| Identifier                                                         | M         | String    | Unique identifier of the MCP tool or resource that is exposed to MCP clients and used to trigger the chain.                                                                                                                                                                        | getCustomerInfo                          |
| Title                                                              | O         | String    | Human-readable title of the MCP tool or resource. MCP clients can use this value in user interfaces.                                                                                                                                                                               | Get customer information                 |
| Description                                                        | M         | String    | Description of the MCP tool or resource. MCP clients can use this text to show the tool purpose or to help a model decide when the tool should be called.                                                                                                                         | Gets customer profile details.           |
| Tool doesn't modify its environment                                | O         | Boolean   | Checkbox. If checked, indicates that calling the tool does not modify its environment.                                                                                                                                                                                             | N/A                                      |
| Tool may perform destructive updates (delete/overwrite data)       | O         | Boolean   | Checkbox. If checked, indicates that the tool may perform destructive updates, such as deleting or overwriting data.                                                                                                                                                                | N/A                                      |
| Calling repeatedly with same args has no additional effect         | O         | Boolean   | Checkbox. If checked, indicates that repeated calls with the same arguments have no additional effect.                                                                                                                                                                              | N/A                                      |
| Tool may interact with external entities beyond its local environment | O      | Boolean   | Checkbox. If checked, indicates that the tool may interact with external systems or entities beyond its local environment.                                                                                                                                                          | N/A                                      |
| Name                                                               | M         | String    | Name of the element.                                                                                                                                                                                                                                                              | MCP Trigger                              |
| Description                                                        | O         | String    | Free text field, that contains description of the element.                                                                                                                                                                                                                        | Expose integration chain as an MCP tool or resource. |

### "Input schema" Tab
The tab **"Input schema"** allows to define MCP Input schema. The configured schema describes the expected input structure and is used by MCP clients when preparing a tool call.

### "Output schema" Tab
The tab **"Output schema"** allows to define a JSON schema for the MCP tool response. The configured schema describes the response structure that MCP clients can expect after the chain is processed. If output schema is not specified, the tool call result is returned as plain text.

### "Idempotency" Tab
This tab allows setting idempotent behavior in order to avoid processing of the same message by one consumer. The Exchange which has the same idempotency key is regarded as a duplicate. Duplication check is performing on full idempotency key, which has the following structure:
```text
<idempotency-key> = dupcheck:<context-expression>:<key-expression>
```

where ```<context-expression>``` and ```<key-expression>``` are fully configurable parts (see the table with parameters description below).

> **Note:** The `<context-expression>` and `<key-expression>` values can be retrieved via system properties **`systemProperty_idempotencyContext`** and **`systemProperty_idempotencyKey`**, respectively.

The following parameters are available on the tab:
- **Enabled** checkbox - main parameter, which defines whether idempotency will be applied (checked) for trigger or not. Default value: unchecked. If checked, the following set of parameters becomes editable:

| Parameter                     | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                           | Sample                        |
| ----------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| Key expiration time (seconds) | O         | String    | Specifies time in seconds after which idempotency key will be expired<br>**Default value:** 600<br><br>**Note:** The Key expiration time (seconds) value can be retrieved via system property **`systemProperty_keyExpiry`**.                                                                                                                                                                                                         | 600                           |
| Context expression            | M         | String    | Field should contain some meaningful value that is unique across all the other chains. Supports Camel Simple language. For example you may use: `<HTTP Method> + \<path>`.                                                                                                                                                                                                                                                            | GET /routes/myChain           |
| Key expression                | M         | String    | Free text field that supports Camel Simple language. Key examples:<ul><li>From message header: `${header.header_name}`</li><li>From message body part: `${jq('.field_name')}`, `${jsonpath('$.field_name')}`, `${xpath('/root/field_name/text()')}`</li></ul>                                                                                                                                                                        | `${jsonpath('$.id')}`         |
| Action on duplicate           | M         | List      | Defines action that should be performed in case of key duplication detected. Possible values:<ul><li>*Ignore* - respond with 202 Accepted HTTP code and do not process the call</li><li>*Throw exception* - raise exception and stop call processing</li><li>*Execute subchain* - call selected chain via Chain Trigger. In case this option has been chosen, two more parameters (below in the table) becomes visible and editable.</li></ul> | Throw exception               |
| Chain trigger                 | C         | List      | Dropdown list, that contains all chains with [Chain Trigger](../../6__Triggers/2__Chain_Trigger/chain_trigger.md) elements. Select one which has to be triggered in case of idempotency key duplication.<br><br>Visible only if **"Action on duplicate"** is *"Execute subchain"*.                                                                                                                                                    | Idempotency handling subchain |
| Chain call timeout (ms)       | C         | String    | Timeout in milliseconds for calling the chain with [Chain Trigger](../../6__Triggers/2__Chain_Trigger/chain_trigger.md) element. If timeout is reached, session will fail. When left blank, default value will be applied.<br>**Default value:** 600<br><br>Visible only if **"Action on duplicate"** is *"Execute subchain"*.                                                                                                        | 30000                         |

## Constraints

---

- **MCP Trigger** must be the first element in the chain.
- Can't set input dependency to element.
