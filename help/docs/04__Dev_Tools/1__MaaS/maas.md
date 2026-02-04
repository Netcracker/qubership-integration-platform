# MaaS (Web UI only)

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

| Parameter                                            | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                       | Sample                                     |
| ---------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------ |
| <div style="width:150px">Namespace</div>             | M                                       | String                                  | <div style="width:400px">Virtual K8S namespace. System will attempt to get default namespace, according to current configuration. | <div style="width:350px">kube-dev1</div>   |
| <div style="width:150px">Topic Classifier Name</div> | M                                       | String                                  | <div style="width:400px">Identifier for topic in MaaS.</div>                                                                      | <div style="width:350px">devMessages</div> |

### View RabbitMQ tab
Tab contains RabbitMQ-specific parameters, that allow to create classifier in MaaS.

| Parameter                                           | <div style="width:75px">Mandatory</div> | <div style="width:75px">Data Type</div> | Description                                                                                                                                                                                                                          | Sample                                       |
| --------------------------------------------------- | :-------------------------------------- | :-------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------- |
| <div style="width:150px">Namespace</div>            | M                                       | String                                  | <div style="width:400px">Virtual K8S namespace. System will attempt to get default namespace, according to current configuration.</div>                                                                                              | <div style="width:350px">kube-dev1</div>     |
| <div style="width:150px">Vost Classifier Name</div> | M                                       | String                                  | <div style="width:400px">Unique name of theRabbitMQ virtual container, that contains other resources.<br/><b>Default value: </b>public</div>                                                                                         | <div style="width:350px">public</div>        |
| <div style="width:150px">Exchange Name</div>        | C                                       | String                                  | <div style="width:400px">Determines the exchange, that will be used to pull/publish messages. "Exchange Name" and "Queue Name" must not be empty at the same time. Parameter becomes mandatory, when "Routing Key" is entered.</div> | <div style="width:350px">logs</div>          |
| <div style="width:150px">Queue Name</div>           | C                                       | String                                  | <div style="width:400px">The queue identifier, utilized in message consumption or publishing. "Exchange Name" and "Queue Name" must not be empty at the same time. Parameter becomes mandatory, when "Routing Key" is entered.</div> | <div style="width:350px">command.queue</div> |
| <div style="width:150px">Routing Key</div>          | O                                       | String                                  | <div style="width:400px">The routing key to create a binding between queue and exchange. When entered, both "Exchange Name" and "Queue Name" must be also specified.</div>                                                           | <div style="width:350px">rkey1</div>         |

### Export Configuration File
To export MaaS configuration file, simply click <img src="docs/04__Dev_Tools/1__MaaS/img/cloud-download.svg" width="20" height="20"> **Export**  button. All mandatory fields must be specified before button becomes active. 