# RabbitMQ Trigger
## Description

---
**RabbitMQ Trigger** element allows to integrate the chain with RabbitMQ instance and setup messages consumption from specific queue and exchange.

>**ℹ️Note**: According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md).

## User Interface

---
### "Parameters" Tab
#### Common Parameters

- **"Manual"** connection source type

| Parameter     | Mandatory | Data Type | Description                                                                                                                                                             | Sample          |
| ------------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| Queue(s) Name | M         | String    | Name of the queue(s), from where system will consume messages. Use comma as a separator when specifying multiple queues.                                                | command.queue   |
| Addresses     | M         | String    | Address of target AMQP server.<pre style="background-color: #F5F5F7"><code style="color: #000000">server1:12345</code></pre>                                            | localhost:15672 |
| Username      | O         | String    | Username value, required for authenticated access. To avoid security issues, suggest utilizing a reference to respective secured variable, that holds a username value. | N/A             |
| Password      | O         | String    | Password value, required for authenticated access. To avoid security issues, suggest utilizing a reference to respective secured variable, that holds a username value. | N/A             |
| Exchange Name | M         | String    | Determines the exchange, messages will be pulled from.                                                                                                                  | logs            |
| Routing Key   | O         | String    | The routing key to use when binding a consumer queue to the exchange.                                                                                                   | rkey-1          |

- **"MaaS"** connection source type

| Parameter                  | Mandatory | Data Type | Description                                                                                                                                                                                                             | Sample        |
| -------------------------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| Vhost MaaS Classifier Name | M         | String    | Vhost classifier name.<br>**Default value:** public.                                                                                                                                                                    | public        |
| Classifier Namespace       | O         | String    | Specifies classifier namespace, that shall be used instead of default one. If left empty, default namespace will be utilized. Only works, when MaaS has a security permission rule to access a different namespace.     | newNamespace  |
| Exchange Name              | M         | String    | Determines the exchange, produced messages will be sent to.                                                                                                                                                             | logs          |
| Queue(s) Name              | M         | String    | Name of the queue(s), from where system will consume messages. When entered data is missing in MaaS, system shows warning icon with respective "tip" message. Use comma as a separator when specifying multiple queues. | command.queue |
| Routing Key                | O         | String    | The routing key to use when binding a consumer queue to the exchange.                                                                                                                                                   | rkey1         |

#### Advanced Parameters
| Parameter                 | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                | Sample                 |
| ------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------- |
| Acknowledge Mode          | M         | List      | Controls the way the acknowledge is being sent. <ul><li>NONE - no acknowledgment will be sent.</li><li>MANUAL - system will send an acknowledgment (ack) or negative acknowledgment (nack) immediately after receiving the message.</li><li>AUTO - normal or negative acknowledgment will be sent only when chain finishes and system operates normally.</li></ul>**Default value: ** AUTO | N/A                    |
| Exchange Type             | M         | List      | The exchange type such as direct or topic. Enum values: <ul><li>direct</li><li>fanout</li><li>headers</li><li>topic</li></ul>**Default value: ** direct                                                                                                                                                                                                                                    | direct                 |
| Dead Letter Exchange      | O         | String    | The name of the dead letter exchange, to where invalid/dropped messages will be catered.                                                                                                                                                                                                                                                                                                   | x-dead-letter-exchange |
| Dead Letter Exchange Type | O         | List      | The type of the dead letter exchange.Enum values:<ul><li>direct</li><li>fanout</li><li>headers</li><li>topic</li></ul>**Default value: ** direct                                                                                                                                                                                                                                           | direct                 |
| Dead Letter Queue         | O         | String    | The name of the dead letter queue, to where invalid/dropped messages will be catered.                                                                                                                                                                                                                                                                                                      | basic.queue            |
| Dead Letter Routing Key   | O         | String    | The routing key to use when binding a consumer dead letter queue to the dead letter exchange.                                                                                                                                                                                                                                                                                              | dlrkey1                |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                                |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | ----------------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | RabbitMQ Trigger                                      |
| Description | O         | String    | Free text field, that contains description of the element. | Trigger consumes notification requests from the queue |

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
Can't set input dependency to this element.
