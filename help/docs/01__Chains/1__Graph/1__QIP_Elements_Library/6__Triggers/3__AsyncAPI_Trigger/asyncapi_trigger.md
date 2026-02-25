# AsyncAPI Trigger
## Description

---
**AsyncAPI Trigger** is an element, that allows to integrate with services, based on **AsyncAPI specifications** and work via **Kafka** or **AMQP**. In order to fulfill its capabilities, this element must be placed at the start of the chain, connected with asynchronous service and properly filled with required integration details, depending on utilized protocol.

>**ℹ️Note**: According to **Apache Camel** framework, when chain is triggered, system creates **Exchange Object**, that handles input data following the logic, described in respective article: [Apache Camel Context Concept](../../../../../00__Overview/2__Apache_Camel_Context_Concept/apache_camel_context_concept.md).

## User Interface

---
### "Endpoint" Tab

**"Endpoint"** tab is responsible for choosing AsyncAPI specification and it's operations (task). Only services with AsyncAPI specification(s) in **Services** repository are available for selection.

| Parameter         | Mandatory | Data Type | Description                                                                                                                                | Sample               |
| ----------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------- |
| Service           | M         | List      | List with all asynchronous services, available to be selected. Services without a single API specification won't be presented in the list. | kafka-service-sample |
| API Specification | M         | List      | List with specifications, grouped by specification groups.                                                                                 | v1.0.0               |
| Operation         | M         | List      | List with all operations, available for selected API Specification.                                                                        | onTaskStartRefs      |

When service is added, additional section with parameters (based on service type) will appear:

- **Kafka Parameters**

| Parameter                     | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                    | Sample                               |
| ----------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------ |
| topic                         | M         | String    | Kafka topic, from where messages will be consumed.                                                                                                                                                                                                                                                                                                                                                                             | sample-kafka-topic                   |
| groupId                       | O         | String    | A string, that uniquely identifies the consumer group, to which this trigger belongs. Triggers with identical group id would consume messages in parallel.                                                                                                                                                                                                                                                                     | group1                               |
| maas.classifier.name          | M         | String    | Specifies the name of the MaaS classifier, that corresponds to topic.                                                                                                                                                                                                                                                                                                                                                          | topic1-classifier                    |
| maas.classifier.namespace     | O         | String    | Specifies classifier namespace, that shall be used instead of default one. If left empty, default namespace will be utilized. Only works, when MaaS has a security permission rule to access a different namespace.                                                                                                                                                                                                            | newNamespance                        |
| maas.classifier.tenantEnabled | M         | String    | Enables "tenantId" field in classifier. <br>**Default value:** false                                                                                                                                                                                                                                                                                                                                                           | false                                |
| maas.classifier.tenantId      | O         | String    | Specifies tenant unique identifier. If not specified, default value will be used. Only works, when "**maas.classifier.tenantEnabled**" is "true".                                                                                                                                                                                                                                                                              | d334cf82-11aa-4vz9-a1a6-ba9f6aa06e09 |
| consumerConsistencyMode       | M         | String    | Blue-green specific parameter. Possible values: <ul><li>**EVENTUAL** – Default option when blue-green is supported. Consumers offset for candidate during promotion stays the same </li><li>**GUARANTEE_CONSUMPTION** - consumers offset for candidate during promotion is copied from lowest offset for this group of consumers</li></ul><br>ℹ️**Note:** This parameter and its value should be entered manually if required. | EVENTUAL                             |


>ℹ️**Note:**
>When message processing is slow, it could lead to <font color="red">interruptions</font> and other negative effects, such as <font color="red">repeated message processing</font> from the topic. In such cases, it must be considered to:
>* Increase value for parameter **maxPollIntervalMs**, responsible for the maximum delay between invocations of polls when using consumer group management. Default is **300000** ms.
>* Decrease value for parameter **maxPollRecords**, responsible for the maximum number of records returned in a single call to poll. Default is **500**.
>This will allow to process the messages smoothly, with lower risk of reaching the timeout.


- **RabbitMQ Parameters**

| Parameter                 | Mandatory | Data Type | Description                                                                                                                                                                                                         | Sample             |
| ------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| exchangeName              | M         | String    | The exchange name determines the exchange, messages will be consumed from.                                                                                                                                          | sample-exchange-v1 |
| queues                    | M         | String    | Name of the queue(s), from where trigger will consume messages. Use comma as a separator when specifying multiple queues.                                                                                           | qip-maas-queue-1   |
| maas.classifier.name      | M         | String    | Vhost classifier name. Parameter is only available for MaaS connection type. **Default value:** public                                                                                                              | public             |
| maas.classifier.namespace | O         | String    | Specifies classifier namespace, that shall be used instead of default one. If left empty, default namespace will be utilized. Only works, when MaaS has a security permission rule to access a different namespace. | newNamespance      |
| routingKey                | O         | String    | The routing key to use when binding a consumer queue to the exchange.                                                                                                                                               | rkey-1             |

### "Validate Request" Tab

The tab **"Validate request"** is responsible for choosing JSON schema for message validation. Click "**Add**" button, select message type and click "**Create**" to add validation.

### "Filter Request" Tab

On this tab, you could add headers into the **"Header whitelist"** table to avoid processing of incoming messages that have headers with  values, that do not correspond to the table, or have no mentioned headers at all.
To add a header, simply click **"Add"** button, then specify header's name and value. Keep the table blank if no filter logic required.

### "Idempotency" Tab

This tab allows setting idempotent behavior in order to avoid processing of the same message by one consumer (especially relevant for Blue-Green deployment approach). The Exchange which has the same idempotency key is regarded as a duplicate. Duplication check is performing on full idempotency key, which has the following structure:
<pre style="background-color: #F5F5F7"><style> .line-numbers-rows { display: none !important; }</style><code style="color: #000000">&lt;idempotency-key&gt; = dupcheck:&lt;context-expression&gt;:&lt;key-expression&gt;</code></pre>

where ```<context-expression>``` and ```<key-expression>``` are fully configurable parts (see the table with parameters description below).

>ℹ️**Note**: The <code>&lt;context-expression&gt;</code> and <code>&lt;key-expression&gt;</code> values can be retrieved via system properties **`systemProperty_idempotencyContext`** and **`systemProperty_idempotencyKey`**, respectively.

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

### "Parameters" Tab
#### Common Parameters
| Parameter       | Mandatory | Data Type | Description                                           | Sample |
| --------------- | :-------- | :-------- | ----------------------------------------------------- | ------ |
| Reconnect delay | O         | Number    | Specifies delay between reconnection in milliseconds. | 30000  |

#### Metadata
| Parameter   | Mandatory | Data Type | Description                                                | Sample                                  |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | --------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | AsyncAPI Trigger                        |
| Description | O         | String    | Free text field, that contains description of the element. | Subscribed to message queue to get info |

## Constraints

---
Please consider next constraints:
- Only one validation can be applied. Button "**Add**" will be disabled on "**Validate Request**" tab if at least one validation is already added.
- Common Apache Camel Kafka parameters "resumeStrategy", "seekTo", "offsetRepository", "allowManualCommit", "autoCommitEnable", "kafkaManualCommitFactory" are not fully supported and shall not be specified for Kafka type of trigger. If specified - issue will be presented during the chain deployment.
- **AsyncAPI Trigger** with **Kafka** type will always operate with next default settings for common Apache Camel Kafka parameters: "autoCommitOnStop"  = "sync", "allowManualCommit" = "false", "autoCommitEnable" = "true".
- Can't set input dependency to element.
