# PubSub Sender

## Description

---
**Google PubSub** element allows to integrate the chain with **Cloud Pub/Sub Infrastructure** via utilizing **Google Cloud Java Client** and pass the messages to the specified topic.

## User Interface

---
### "Parameters" Tab
#### Common Parameters

| Parameter           | Mandatory | Data Type | Description                                                                                                                                                                         | Sample        |
| ------------------- | --------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| Project id          | M         | String    | Google Cloud PubSub Project Identifier.                                                                                                                                             | sandbox       |
| Destination name    | M         | String    | Specifies the name of the target topic.                                                                                                                                             | topicName     |
| Service account key | M         | String    | Specifies service account key, that can be used as credentials for the PubSub publisher.<br><br>**ℹ️Note**: Value for this parameter must be presented as encoded base64 json file. | #{pubsub_key} |


#### Advanced Parameters

| Parameter                | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | Sample              |
| ------------------------ | --------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------- |
| Lazy start producer      | M         | Boolean   | Checkbox, that defines if the producer should be started during the first message processing.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | N/A                 |
| Message ordering enabled | M         | Boolean   | Used with ordering functionality. Defines related messages for which publish order should be respected.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | N/A                 |
| Message ordering key     | C         | String    | Used in pair with "**Message ordering enabled**" checkbox. Defines the key, by which ordering will be performed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | #{pubsub_order_key} |
| Propagate context        | M         | Boolean   | Checkbox, that defines if context to special ("Technical") headers before sending message will be propagated or not.<ul><li>If **checked** (default): context to this call will be propagated, which will lead to the reinstatement of all technical headers, that are stored in context.</li><li>If **unchecked**: call propagation will be switched off, hence values of technical headers, that are stored in the context won't be reinstated.</li></ul>Additionally, when **"Propagate context"** is checked, **"Override Technical Context Headers"** table becomes available to the user. This table allows to override the value for the specific header, that has been propagated from context. <br><br>**ℹ️Note**: For the actual list of technical headers, please, contact system administrator. | N/A                 |

#### Metadata

| Parameter   | Mandatory | Data Type | Description                                                | Sample                                       |
| ----------- | :-------- | :-------- | ---------------------------------------------------------- | -------------------------------------------- |
| Name        | M         | String    | Name of the element.                                       | PubSub Sender                                |
| Description | O         | String    | Free text field, that contains description of the element. | This element publishes messages to the topic |

## Constraints

---
There are no specific constraints for the element.
