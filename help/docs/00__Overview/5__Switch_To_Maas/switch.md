# Switch To MaaS

## Description

---
To improve and ease connection management for chains that are utilizing such elements as **AsyncAPI Trigger**, **Kafka Trigger/Sender**, **RabbitMQ Trigger/Sender** and **Service Call**, Qubership Integration Platform introduced integration with **"MaaS"** service. This option provides ability to manage connection details in one place with only specifying the reference to those settings when configuring your chain's elements.

Please refer to the high-level diagram below for visual representation of differences in two approaches.

![[switch_to_maas.svg]]

## User Interface

---
To enable communication with MaaS in QIP, please follow simple steps mentioned below.

- When working with **Service Call** and **AsyncAPI Trigger**:
    - Go to your **service**, edit the environment and switch **Source type** to MaaS. Click "**Restore defaults**" button and save the changes to proceed with default settings or add additional properties for specific scenarios if required.
    - For <u>Service Call</u> element make sure that you've selected correct service (already configured for MaaS via Environment page) and specify parameters according to [Service Call](docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md) page.
    - For <u>AsyncAPI Trigger</u> element make sure that you've selected correct service (already configured for MaaS via Environment page) and specify parameters according to [AsyncAPI Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/3__AsyncAPI_Trigger/asyncapi_trigger.md) page.
- When working with **Kafka** and **RabbitMQ**:
    - For <u>Kafka Trigger</u> element switch Connection source type to "MaaS" and specify parameters according to [Kafka Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/8__Kafka_Trigger/kafka_trigger.md) page.
    - For <u>RabbitMQ Trigger</u> element switch Connection source type to "MaaS" and specify parameters according to [RabbitMQ Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/6__RabbitMQ_Trigger/rabbitmq_trigger.md) page.
    - For <u>Kafka Sender</u> element switch Connection source type to "MaaS" and specify parameters according to [Kafka Sender](docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/2__Kafka_Sender/kafka_sender.md) page.
    - For <u>RabbitMQ Sender</u> element switch Connection source type to "MaaS" and specify parameters according to [RabbitMQ Sender](docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/1__RabbitMQ_Sender/rabbitmq_sender.md) page.

Please note, that no entities are going to be automatically created when using Kafka/RabbitMQ triggers and senders. All queues, topics, etc. shall be pre-created via **MaaS** <u>before</u> respective element starts processing.
