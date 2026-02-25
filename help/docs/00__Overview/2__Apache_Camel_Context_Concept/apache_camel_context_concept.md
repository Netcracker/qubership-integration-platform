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

| Trigger Type     | Exchange Object Properties | Exchange Object Headers                           | Exchange Object Body                                |
| ---------------- | -------------------------- | ------------------------------------------------- | --------------------------------------------------- |
| AsyncAPI Trigger | -                          | Kafka Headers<br>RabbitMQ Headers                 | - Kafka Message's Body<br>- RabbitMQ Message's Body |
| Chain Trigger    | Source Chain's Properties  | Source Chain's Headers                            | Source Chain's Body                                 |
| HTTP Trigger     | Path Parameters            | - Headers<br>- Query Parameters                   | HTTP Message's Body                                 |
| JMS Trigger      | -                          | - Headers<br>- JMS Properties                     | JMS Message's Body                                  |
| Kafka Trigger    | -                          | Kafka Headers                                     | Kafka Message's Body                                |
| PubSub Trigger   | -                          | PubSub Headers                                    | PubSub Message's Body                               |
| RabbitMQ Trigger | -                          | RabbitMQ Headers                                  | RabbitMQ Message's Body                             |
| Scheduler        | -                          | - Scheduler's Headers<br>- Scheduler's Properties | -                                                   |
| SDS Trigger      | -                          | - Headers<br>- Path Parameters                    | -                                                   |
| SFTP Trigger     | -                          | -                                                 | Inbound File                                        |

## User Interface

---
Concept is being supported by [set of elements](../../01__Chains/1__Graph/graph.md) and functionality that do have a user interface. User interface capabilities and specifics are covered by respective articles, introduced for each particular element.
