# PubSub Trigger

## Description

---
**Google PubSub** element allows to integrate the chain with **Cloud Pub/Sub Infrastructure** via utilizing **Google Cloud Java Client** and read the messages from the specified topic.

>**ℹ️Note**: According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md).


## User Interface

---
### "Parameters" Tab
#### Common Parameters

| Parameter           | Mandatory | Data Type | Description                                                                                                                                                                          | Sample           |
| ------------------- | --------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------- |
| Project id          | M         | String    | Google Cloud PubSub Project Identifier.                                                                                                                                              | sandbox          |
| Destination name    | M         | String    | Specifies the name of the source subscription.                                                                                                                                       | subscriptionName |
| Service account key | M         | String    | Specifies service account key, that can be used as credentials for the PubSub publisher. <br><br>**ℹ️Note**: Value for this parameter must be presented as encoded base64 json file. | #{pubsub_key}    |

#### Advanced Parameters

| Parameter                | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                    | Sample      |
| ------------------------ | --------- | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| Max ack extension period | O         | String    | Set the maximum period in seconds a message ack deadline will be extended. If not specified, default value is utilized.<br>**Default:** 3600                                                                                                                                                                                                   | 3600        |
| Max messages per poll    | O         | String    | Specifies the maximum number of messages to receive from the server in a single poll.<br>**Default:** 1                                                                                                                                                                                                                                        | 1           |
| Retryable error codes    | M         | List      | Comma-separated list of error codes for synchronous pull, that could be retried. If not specified, default value is utilized. Possible values: <ul><li>ABORTED</li><li>CANCELLED</li><li>DEADLINE_EXCEEDED</li><li>INTERNAL</li><li>RESOURCE_EXHAUSTED</li><li>UNKNOWN</li><li>UNAVAILABLE</li></ul>**Default:** ABORTED, UNAVAILABLE, UNKNOWN | UNAVAILABLE |
| Ack mode                 | M         | List      | Possible values:<ul><li>AUTO  - Exchange gets ack’ed/nack’ed on completion.</li><li>NONE - Downstream process has to ack/nack explicitly.</li></ul>**Default:** AUTO                                                                                                                                                                           | AUTO        |

#### Metadata

| Parameter   | Mandatory | Data Type | Description                                                | Sample                                                     |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ---------------------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | PubSub Trigger                                             |
| Description | O         | String    | Free text field, that contains description of the element. | Triggers the chain when message is received from the topic |

### "Idempotency" Tab

This tab allows setting idempotent behavior in order to avoid processing of the same message by one consumer (especially relevant for Blue-Green deployment approach). The Exchange which has the same idempotency key is regarded as a duplicate. Duplication check is performing on full idempotency key, which has the following structure:
<pre style="background-color: #F5F5F7"><style> .line-numbers-rows { display: none !important; }</style><code style="color: #000000">&lt;idempotency-key&gt; = dupcheck:&lt;context-expression&gt;:&lt;key-expression&gt;</code></pre>

where ```<context-expression>``` and ```<key-expression>``` are fully configurable parts (see the table with parameters description below).

>**ℹ️Note**: The <code>&lt;context-expression&gt;</code> and <code>&lt;key-expression&gt;</code> values can be retrieved via system properties **`systemProperty_idempotencyContext**` and **`systemProperty_idempotencyKey`**, respectively.

The following parameters are available on the tab:
- **Enabled** checkbox - main parameter, which defines whether idempotency will be applied (checked) for trigger or not. Default value: unchecked. If checked, the following set of parameters becomes editable:

| Parameter                     | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                         | Sample                           |
| ----------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------- |
| Context expression            | М         | String    | Field should contain some meaningful value that is unique across all the other chains. Supports Camel Simple language. For example in case of Kafka service you may use: <ul><li><code>\<topic> + \<group id></code></li><li><code>\<classifier> + \<group id></code>, etc.</li></ul>In case of RabbitMQ service:<ul><li><code>\<queue></code></li><li><code>\<classifier> + \<queue></code>, etc.</li></ul>        | topic1-group1                    |
| Key expression                | M         | String    | Free text field that supports Camel Simple language. Key examples:<ul><li>From message header: <code>${header.header_name}</code></li><li>From message body part: <code>${jq('.field_name')}</code>, <code>${jsonpath('$.field_name')}</code>, <code> ${xpath('/root/field_name/text()')}</code></li></ul>                                                                                                          | <code>${jsonpath('$.id')}</code> |
| Key expiration time (seconds) | O         | String    | Specifies time in seconds after which idempotency key will be expired<br>**Default value:** 600<br><br>**ℹ️Note**: The Key expiration time (seconds) value can be retrieved via system property **`systemProperty_keyExpiry`**.                                                                                                                                                                                     | 600                              |
| Action on duplicate           | M         | List      | Defines action that should be performed in case of key duplication detected. Possible values:<li><i>Ignore</i> - stop session and do not process the call</li><li>*Throw exception* - raise exception and stop call processing</li><li>*Execute subchain* - call selected chain via Chain Trigger. In case this option has been chosen, two more parameters (below in the table) becomes visible and editable.</li> | Throw exception                  |
| Chain trigger                 | C         | List      | Dropdown list, that contains all chains with [Chain Trigger](../2__Chain_Trigger/chain_trigger.md) elements. Select one which has to be triggered in case of idempotency key duplication.<br><br>Visible only if **"Action on duplicate"** is *"Execute subchain"*.                                                                                                                                                 | Idempotency handling subchain    |
| Chain call timeout (ms)       | C         | String    | Timeout in milliseconds for calling the chain with [Chain Trigger](../2__Chain_Trigger/chain_trigger.md) element. If timeout is reached, session will fail. When left blank, default value will be applied.<br>**Default value:** 600<br><br>Visible only if **"Action on duplicate"** is *"Execute subchain"*.                                                                                                     | 600                              |

## Constraints

---
There are no specific constraints for the element.
