# Apache Camel Context Concept
## Description

---
### Overview

Every integration call is being processed by **QIP Integration Engine**, which is based on **Apache Camel** framework. Based on this framework, when new session is started, there will be always **Exchange Object** created as the result. This object might be divided into three sections:

- **Properties** - contains exchange properties, technical context data, declared variables and path parameters from request.
- **Headers** - contains headers data, either received in the request as part of headers or query parameters or created as part of the chain itself.
- **Body** - contains body data.

The main purpose of utilizing Apache Camel in Qubership Integration Platform is to provide wide set of mechanism for message parsing, data modification and complex routing while maintaining simple, understandable solution with user-friendly and convenient application. The application itself does not require deep knowledge in Apache Camel, as most of the available functionality is wrapped up into human-understandable elements, such as chain elements, entities card, etc. 

**Apache Camel Exchange Object** will "accumulate" the data from Triggers, depending on their type and operable data type. Table below shows to which part **Camel Exchange Object** data from every trigger goes.

| Trigger Type                                     | Exchange Object Properties                               | Exchange Object Headers                                                                             | Exchange Object Body                                                                                  |
|--------------------------------------------------|----------------------------------------------------------|-----------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| <div style="width:195px">AsyncAPI Trigger</div>	 | <div style="width:250px">-</div>                         | <div style="width:250px"><ul><li>Kafka Headers</li><li>RabbitMQ Headers</li></ul></div>             | <div style="width:250px"><ul><li>Kafka Message's Body</li><li>RabbitMQ Message's Body</li></ul></div> |
| <div style="width:195px">Chain Trigger</div>     | <div style="width:250px">Source Chain's Properties</div> | <div style="width:250px">Source Chain's Headers</div>                                               | <div style="width:250px">Source Chain's Body</div>                                                    |
| <div style="width:195px">HTTP Trigger </div>     | <div style="width:250px">Path Parameters</div>           | <div style="width:250px"><ul><li>Headers</li><li>Query Parameters</li></ul></div>                   | <div style="width:250px">HTTP Message's Body</div>                                                    |
| <div style="width:195px">JMS Trigger </div>      | <div style="width:250px">-</div>                         | <div style="width:250px"><ul><li>Headers</li><li>JMS Properties</li></ul></div>                     | <div style="width:250px">JMS Message's Body</div>                                                     |
| <div style="width:195px">Kafka Trigger </div>    | <div style="width:250px">-</div>                         | <div style="width:250px">Kafka Headers</div>                                                        | <div style="width:250px">Kafka Message's Body</div>                                                   |
| <div style="width:195px">PubSub Trigger </div>   | <div style="width:250px">-</div>                         | <div style="width:250px">PubSub Headers</div>                                                       | <div style="width:250px">PubSub Message's Body</div>                                                  |
| <div style="width:195px">RabbitMQ Trigger</div>  | <div style="width:250px">-</div>                         | <div style="width:250px">RabbitMQ Headers</div>                                                     | <div style="width:250px">RabbitMQ Message's Body</div>                                                |
| <div style="width:195px">Scheduler     </div>    | <div style="width:250px">-</div>                         | <div style="width:250px"><ul><li>Scheduler's Headers</li><li>Scheduler's Properties</li></ul></div> | <div style="width:250px">-</div>                                                                      |
| <div style="width:195px">SDS Trigger   </div>    | <div style="width:250px">-</div>                         | <div style="width:250px"><ul><li>Headers</li><li>Path Parameters</li></ul></div>                    | <div style="width:250px">-</div>                                                                      |
| <div style="width:195px">SFTP Trigger   </div>   | <div style="width:250px">-</div>                         | <div style="width:250px">-</div>                                                                    | <div style="width:250px">Inbound File</div>                                                           |

## User Interface

---
Concept is being supported by [set of elements](docs/01__Chains/1__Graph/graph.md) and functionality that do have a user interface. User interface capabilities and specifics are covered by respective articles, introduced for each particular element.
