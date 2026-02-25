# MaaS [Web UI only]

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>

## Description

---
This tab allows to create data in MaaS right from the application and export configuration file. Only limited set of MaaS-related parameters is available for manual enter, while other technical and connection data is handled by system.

## User Interface

---
### View Kafka tab
Tab contains Kafka-specific parameters, required to create classifier in MaaS.

| Parameter             | Mandatory | Data Type | Description                                                                                              | Sample      |
| --------------------- | :-------- | :-------- | -------------------------------------------------------------------------------------------------------- | ----------- |
| Namespace             | M         | String    | Virtual K8S namespace. System will attempt to get default namespace, according to current configuration. | kube-dev1   |
| Topic Classifier Name | M         | String    | Identifier for topic in MaaS.                                                                            | devMessages |

### View RabbitMQ tab
Tab contains RabbitMQ-specific parameters, that allow to create classifier in MaaS.

| Parameter            | Mandatory | Data Type | Description                                                                                                                                                                                           | Sample        |
| -------------------- | :-------- | :-------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| Namespace            | M         | String    | Virtual K8S namespace. System will attempt to get default namespace, according to current configuration.                                                                                              | kube-dev1     |
| Vost Classifier Name | M         | String    | Unique name of theRabbitMQ virtual container, that contains other resources.<br>**Default value**: public                                                                                             | public        |
| Exchange Name        | C         | String    | Determines the exchange, that will be used to pull/publish messages. "Exchange Name" and "Queue Name" must not be empty at the same time. Parameter becomes mandatory, when "Routing Key" is entered. | logs          |
| Queue Name           | C         | String    | The queue identifier, utilized in message consumption or publishing. "Exchange Name" and "Queue Name" must not be empty at the same time. Parameter becomes mandatory, when "Routing Key" is entered. | command.queue |
| Routing Key          | O         | String    | The routing key to create a binding between queue and exchange. When entered, both "Exchange Name" and "Queue Name" must be also specified.                                                           | rkey1         |

### Export Configuration File
To export MaaS configuration file, simply click ![](img/cloud-download.svg) **Export**  button. All mandatory fields must be specified before button becomes active. 
