# RabbitMQ Sender
## Description

---
**RabbitMQ Sender** element allows to integrate the chain with RabbitMQ instance and setup sending the messages to specified exchange and queue.

## User Interface

---
### "Parameters" Tab
- **"Manual"** connection source type

| Parameter     | Mandatory | Data Type | Description                                                                                                                                                                                                            | Sample          |
| ------------- | :-------- | :-------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| Address       | M         | String    | Address of target AMQP server.<br>**Format:** `server1:12345`                                                                          | localhost:15672 |
| Username      | O         | String    | Username value, required for authenticated access. To avoid security issues, suggest utilizing a reference to respective secured variable, that holds a username value. When left empty, default value will be passed. | N/A             |
| Password      | O         | String    | Password value, required for authenticated access. To avoid security issues, suggest utilizing a reference to respective secured variable, that holds a username value. When left empty, default value will be passed. | N/A             |
| Exchange Name | M         | String    | Determines the exchange to which the produced messages will be sent to.                                                                                                                                                | logs            |
| Routing Key   | O         | String    | The routing key the message is published with. The exchange uses it to pick the bindings that deliver the message.                                                                                                     | rkey1           |

- **"MaaS"** connection source type

| Parameter                  | Mandatory | Data Type | Description                                                                                                                                                                                                         | Sample        |
| -------------------------- | :-------- | :-------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| Vhost MaaS Classifier Name | M         | String    | Vhost classifier name.<br>**Default value:** public                                                                                                                                                                 | public        |
| Classifier Namespace       | O         | String    | Specifies classifier namespace, that shall be used instead of default one. If left empty, default namespace will be utilized. Only works, when MaaS has a security permission rule to access a different namespace. | newNamespace |
| Exchange Name              | M         | String    | Determines the exchange, produced messages will be sent to.                                                                                                                                                         | logs          |
| Routing Key                | O         | String    | The routing key the message is published with. The exchange uses it to pick the bindings that deliver the message.                                                                                                  | rkey1         |

> ℹ️ **Note:** A message is published to an exchange, never to a queue directly. To reach one queue, type `default` as the exchange name and set the routing key to the queue name: `default` is the exchange every broker pre-declares, and it routes by queue name. The chain does not create the exchange, and a deployment whose exchange is missing stays in `PROCESSING` and is retried until it appears.

The following parameters are common for all cases:
| Parameter         | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | Sample |
| ----------------- | :-------- | :-------- |--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------| ------ |
| Propagate context | M         | Boolean   | Checkbox, that defines if context to special ("Technical") headers before sending message will be propagated or not.<ul><li>If **checked** (default): context to this call will be propagated, which will lead to the reinstatement of all technical headers, that are stored in context.</li><li>If **unchecked**: call propagation will be switched off, hence values of technical headers, that are stored in the context won't be reinstated.</li></ul>Additionally, when **"Propagate context"** is checked, **"Override Technical Context Headers"** table becomes available to the user. This table allows to override the value for the specific header, that has been propagated from context. <br><br>ℹ️ **Note:** For the actual list of technical headers, please, contact system administrator. | N/A    |
| Name        | M         | String    | Name of the element.                                       | RabbitMQ Sender                          |
|Description | O         | String    | Free text field, that contains description of the element. | This element sends messages to the queue |

## Constraints

---
Header "Authorization" will be removed by the system before sending the message.
