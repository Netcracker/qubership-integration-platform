# JMS Trigger
## Description

---
This element allows to trigger the chain by consuming messages from **JMS Queue** or **JMS Topic**, depending on the setup.

> ℹ️ **Note:** According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md).

## User Interface

---
### "Parameters" Tab
| Parameter                                              | Mandatory | Data Type | Description                                                                                                                                                                                                                          | Sample                                                                      |
|--------------------------------------------------------|-----------------------------------------|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| Provider URL            | M                                       | String                                  | Path to JNDI service provider.                                                                                                                                                                        | "t3://devapp066.qip.com:7154"       |
| Destination             | M                                       | String                                  | This field either identifies a **Queue** or **Topic**, available on JNDI side, depending on the option, selected in the list "**Type**".                                                     | "jms/BulkQuotationRequest/queue"            |
| Initial context factory | M                                       | String                                  | Address of JNDI lookup service. Object, that builds a context.                                                                                                                                        | "weblogic.jndi.WLInitialContextFactory"      |
| Connection factory      | M                                       | String                                  | JMS object identifier in JNDI factory.                                                                                                                                                                | "jms/BulkQuotationRequest/connectionfactory" |
| Type                    | M                                       | List                                    | Type of the source: <li>queue</li> <li>topic</li><br/>**Default value:** queue                                                                                                                     | queue                                        |
| Acknowledgement mode    | M                                       | List                                    | The JMS acknowledgement mode. Possible options: <li>AUTO_ACKNOWLEDGE</li><li>CLIENT_ACKNOWLEDGE</li><li>DUPS_OK_ACKNOWLEDGE</li><li>SESSION_TRANSACTED</li><br/>**Default value:** AUTO_ACKNOWLEDGE | AUTO_ACKNOWLEDGE                             |
| Password                | O                                       | String                                  | Password to use with the ConnectionFactory. Both username and password can also be configured directly on the ConnectionFactory side.                                                                 | N/A                                          |
| Username                | O                                       | String                                  | Username to use with the ConnectionFactory. Both username and password can also be configured directly on the ConnectionFactory side.                                                                 | N/A                                         |
| Name       | M                                         | String                                    | Name of the element.                                        | JMS Trigger                                                    |
| Description | O                                         | String                                    | Free text field, that contains description of the element.  | Trigger allows fetching messages from the specified JMS topic  |

### "Idempotency" Tab
This tab allows setting idempotent behavior in order to avoid processing of the same message by one consumer (especially relevant for Blue-Green deployment approach). The Exchange which has the same idempotency key is regarded as a duplicate. Duplication check is performing on full idempotency key, which has the following structure:

```text
<idempotency-key> = dupcheck:<context-expression>:<key-expression>
```

where ```<context-expression>``` and ```<key-expression>``` are fully configurable parts (see the table with parameters description below).

> ℹ️ **Note:** The `<context-expression>` and `<key-expression>` values can be retrieved via system properties **`systemProperty_idempotencyContext`** and **`systemProperty_idempotencyKey`**, respectively.

The following parameters are available on the tab:
- **Enabled** checkbox - main parameter, which defines whether idempotency will be applied (checked) for trigger or not. Default value: unchecked. If checked, the following set of parameters becomes editable:

| Parameter                     | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                    | Sample                        |
| ----------------------------- | :-------- | :-------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| Context expression            | M         | String    | Field should contain some meaningful value that is unique across all the other chains. Supports Camel Simple language. For example in case of Kafka service you may use: <ul><li>`\<topic> + \<group id>`</li><li>`\<classifier> + \<group id>`, etc.</li></ul>In case of RabbitMQ service:<ul><li>`\<queue>`</li><li>`\<classifier> + \<queue>`, etc.</li></ul>                                               | topic1-group1                 |
| Key expression                | M         | String    | Free text field that supports Camel Simple language. Key examples:<ul><li>From message header: `${header.header_name}`</li><li>From message body part: `${jq('.field_name')}`, `${jsonpath('$.field_name')}`, ` ${xpath('/root/field_name/text()')}`</li></ul>                                                                                                                                                 | `${jsonpath('$.id')}`         |
| Key expiration time (seconds) | O         | String    | Specifies time in seconds after which idempotency key will be expired<br>**Default value:** 600<br><br>ℹ️ **Note:** The Key expiration time (seconds) value can be retrieved via system property **`systemProperty_keyExpiry`**.                                                                                                                                                                               | 600                           |
| Action on duplicate           | M         | List      | Defines action that should be performed in case of key duplication detected. Possible values:<li>*Ignore* - stop session and do not process the call</li><li>*Throw exception* - raise exception and stop call processing</li><li>*Execute subchain* - call selected chain via Chain Trigger. In case this option has been chosen, two more parameters (below in the table) becomes visible and editable.</li> | Throw exception               |
| Chain trigger                 | C         | List      | Dropdown list, that contains all chains with [Chain Trigger](../../6__Triggers/2__Chain_Trigger/chain_trigger.md) elements. Select one which has to be triggered in case of idempotency key duplication.<br><br>Visible only if **"Action on duplicate"** is *"Execute subchain"*.                                                                                                                             | Idempotency handling subchain |
| Chain call timeout (ms)       | C         | String    | Timeout in milliseconds for calling the chain with [Chain Trigger](../../6__Triggers/2__Chain_Trigger/chain_trigger.md) element. If timeout is reached, session will fail. When left blank, default value will be applied.<br>**Default value:** 600<br><br>Visible only if **"Action on duplicate"** is *"Execute subchain"*.                                                                                 | 600                           |

## Constraints

---
Can't set input dependency to element.