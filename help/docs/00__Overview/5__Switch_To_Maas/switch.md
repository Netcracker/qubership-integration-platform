# Switch To MaaS

## Description

---
To improve and ease connection management for chains that are utilizing such elements as **AsyncAPI Trigger**,
**Kafka Trigger/Sender**, **RabbitMQ Trigger/Sender** and **Service Call**, Qubership Integration Platform introduced
integration with **"MaaS"** service. This option provides ability to manage connection details in one place
with only specifying the reference to those settings when configuring your chain's elements.

Please refer to the high-level diagram below for visual representation of differences in two approaches.

![Switch to MaaS diagram](img/switch_to_maas.svg)

## User Interface

---
To enable communication with MaaS in QIP, please follow simple steps mentioned below.

- When working with **Service Call** and **AsyncAPI Trigger**:
  - Go to your **service**, edit the environment and switch **Source type** to MaaS. Click "**Restore defaults**" button and save the changes to proceed with default settings or add additional properties for specific scenarios if required.
  - For **Service Call** element make sure that you've selected correct service (already configured for MaaS via Environment page) and specify parameters according to [Service Call](../../01__Chains/1__Graph/1__Elements_Library/7__Senders/6__Service_Call/service_call.md) page.
  - For **AsyncAPI Trigger** element make sure that you've selected correct service (already configured for MaaS via Environment page) and specify parameters according to [AsyncAPI Trigger](../../01__Chains/1__Graph/1__Elements_Library/6__Triggers/3__AsyncAPI_Trigger/asyncapi_trigger.md) page.
- When working with **Kafka** and **RabbitMQ**:
  - For **Kafka Trigger** element switch Connection source type to "MaaS" and specify parameters according to [Kafka Trigger](../../01__Chains/1__Graph/1__Elements_Library/6__Triggers/8__Kafka_Trigger/kafka_trigger.md) page.
  - For **RabbitMQ Trigger** element switch Connection source type to "MaaS" and specify parameters according to [RabbitMQ Trigger](../../01__Chains/1__Graph/1__Elements_Library/6__Triggers/6__RabbitMQ_Trigger/rabbitmq_trigger.md) page.
  - For **Kafka Sender** element switch Connection source type to "MaaS" and specify parameters according to [Kafka Sender](../../01__Chains/1__Graph/1__Elements_Library/7__Senders/2__Kafka_Sender/kafka_sender.md) page.
  - For **RabbitMQ Sender** element switch Connection source type to "MaaS" and specify parameters according to [RabbitMQ Sender](../../01__Chains/1__Graph/1__Elements_Library/7__Senders/1__RabbitMQ_Sender/rabbitmq_sender.md) page.

>ℹ️**Note**: No entities are going to be automatically created when using Kafka/RabbitMQ triggers and senders. All queues, topics, etc. shall be pre-created via **MaaS** before respective element starts processing.
