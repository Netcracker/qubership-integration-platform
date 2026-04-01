# Apache Camel Context Concept
## Description

---
### Overview

Every integration call is being processed by **QIP Integration Engine**, which is based on **Apache Camel** framework. Based on this framework, when new session is started, there will be always **Exchange Object** created as the result. This object might be divided into three sections:

- **Properties** - contains exchange properties, technical context data, declared variables and path parameters from request.
- **Headers** - contains headers data, either received in the request as part of headers or query parameters or created as part of the chain itself.
- **Body** - contains body data.

The main purpose of utilizing Apache Camel in Qubership Integration Platform is to provide wide set of mechanism for message parsing, data modification and complex routing while maintaining simple, understandable solution with user-friendly and convenient application. The application itself does not require deep knowledge in Apache Camel, as most of the available functionality is wrapped up into human-understandable elements, such as chain elements, entities card, etc. 

![](img/camel_chain.svg)

**Steps Description**

| #   | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Apache Camel Exchange object is being created as part of initial chain processing step. Data from request is merged into this newly created exchange object. Merge logic is described in the next table.                                                                                                                                                                                                                                                                                                                                 |
| 2   | Path and query parameters from request are being transformed to properties and headers respectively.                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| 3   | Some of the chain elements are able to access and update data from every section of exchange object (properties, headers and body). Here, **Mapper** is presented as an example of such elements.                                                                                                                                                                                                                                                                                                                                        |
| 4   | Other chain elements are only able to access specific part of exchange object. <b>Header Modification</b> chain's element is presented on the diagram as an example of element, that can only access Headers in exchange object. It can read, update, add and remove headers from the exchange.                                                                                                                                                                                                                                          |
| 5   | Service call is a complex element, that is able to access and apply operations against any section of exchange object via different instruments: <li>**Prepare Request** - can work with every section of exchange object.</li><li>**Authorization** - has access to Headers.</li><li>**Request Attempt** - makes a call to outbound service while pushing headers and body, currently available in exchange.</li><li>**Handle Response** - merges data into exchange object from response, while also having ability to update it.</li> |
| 6   | Finally, Headers and Body are being returned in response to chain's requester, while properties are being erased from the system.                                                                                                                                                                                                                                                                                                                                                                                                        |

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
There are multiple chain elements, that have very specific ways of processing context data. Those specifics are described in the next respective sections.

### Split
Diagram below shows how context data is being managed by **Split** element:
![](img/camel_split.svg)

**Steps Description**

| #   | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | When context data reaches ***Split*** container, it is being copied to every available Split Element.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| 2   | Each ***Split Element*** processes context data in parallel. Context data state will fully depend on the set of elements, used within each particular Split Element.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| 3   | At the end of the processing, Split element merges context data from all branches together.<br>Merge logic:<ul><li><b>JSON bodies</b> of several <b><i>Split Elements</b></i> will be aggregated into single JSON file.</li><li><b>Headers</b><ul><li>Headers from <b><i>Main Split Element</b></i> will be aggregated as-is, without any prefixes.</li><li>Headers from every <b><i>Split Element</b></i> with marked "Propagate headers" checkbox will be aggregated with branch's name as a prefix, separated with a dot.</li></ul></li><li><b>Properties</b><ul><li>Properties from <b><i>Main Split Element</i></b> will be aggregated as-is, without any prefixes.</li><li>Properties from every <b><i>Split Element</i></b> with marked "Propagate properties" checkbox will be aggregated with branch's name as a prefix, separated with a dot.</li></ul></li></ul> |

### Split Async
Diagram below shows how context data is being managed by **Split Async** element:

![](img/camel_asplit.svg)

**Steps Description**

| #   | Description                                                                                                     |
| --- | --------------------------------------------------------------------------------------------------------------- |
| 1   | When reaching ***Split Async Element***, context data is being copied to each of available ***Split Element***. |
| 2   | Context is being processed independently within each split element.                                             |
| 3   | System does not merge output from ***Async Split Element*** and simply erases it when processing is completed.  |

### Checkpoint & Retry
Diagrams below show how context data is being managed by **Checkpoint** element:

![](img/camel_checkpoint.svg)

**Steps Description**

| #   | Description                                                                                                                                                                                                                                |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1,2 | When context data reaches ***Checkpoint***, it is being serialized and preserved to DB, so this data could be accessed later in case of chain failure. At this step system creates ***Checkpoint*** from which session could be restarted. |
| 2   | Original context data is being processed as-is through next elements in the chain.                                                                                                                                                         |


Diagrams below show how context data is being handled when **retry** is requested for failed session:

![](img/camel_retry.svg)

**Steps Description**

| #   | Description                                                                                                                                                                                                                      |
| --- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | When chain failed and session is retried from ***Checkpoint***, the request itself might contain body, that contains context data (properties, headers and body).                                                                |
| 2   | Received request is handled by ***HTTP Trigger*** capabilities, wrapped into Checkpoint element. At this step, Apache Camel Exchange object is being created based on the data, available in the DB for current ***Checkpoint*** |
| 3   | Data from **retry request** is being merged into exchange object with **higher priority**, overriding available data in the object.                                                                                              |
| 4   | Resulted context data is being processed through sequential elements in the chain.                                                                                                                                               |

### Loop
Diagram below shows how context data is being managed by **Loop** element:

![](img/camel_loop.svg)

**Steps Description**

| #   | Description                                                                                                 |
| --- | ----------------------------------------------------------------------------------------------------------- |
| 1   | In each new iteration, ***Loop*** reuses context data from previous iteration, overriding it as the result. |
| 2   | At the end of ***Loop*** processing, data from the last iteration will be in the context.                   |


### Reuse
Diagram below shows how context data is being managed by pair of **Reuse Reference** and **Reuse** elements:

![](img/camel_reuse.svg)

**Steps Description**

| #   | Description                                                                                                                     |
| --- | ------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Reuse reference routes context data to the ***Reuse*** container, where other elements are placed.                              |
| 2   | When ***Reuse*** element finishes processing, context data is transferred back to ***Reuse Reference*** for further processing. |


### Condition
Diagram below shows how context data is being managed by **Condition** element:

![](img/camel_condition.svg)

**Steps Description**

| #   | Description                                                                                                                                                                   |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Condition element routes context data to appropriate ***If*** container with respective chain part, depending on the expression and priority, configured in these containers. |
| 2   | Context data reaches ***Else*** sub-element when data has not been handled by any ***If*** expression.                                                                        |


### Try-Catch-Finally
Diagram below shows how context data is being managed by **Try-Catch-Finally** element:

![461](img/camel_tcf.svg)

**Steps Description**

| #   | Description                                                                                                                |
| --- | -------------------------------------------------------------------------------------------------------------------------- |
| 1   | ***Try-Catch-Finally*** makes an attempt to execute logic under ***Try*** by using input context data.                     |
| 2   | When attempt made and there is an error in response, resulted data will be passed to ***Catch*** sub-element if it exists. |
| 3   | Sub element ***Finally*** receives finalized context from previous blocks and processes it.                                |


### Circuit Breaker
Diagram below shows how context data is being managed by **Circuit Breaker** element in case of sunny-day scenario:

![](img/camel_cb.svg)

**Steps Description**

| #   | Description                                                                                   |
| --- | --------------------------------------------------------------------------------------------- |
| 1   | Context data always goes through ***Circuit Breaker Configuration*** sub-element.             |
| 2   | When there is no error, context data won't be passed to ***On Fallback*** sub-element at all. |

Diagram below shows how context data is being managed by **Circuit Breaker** element in case of rainy-day scenario, when fallback detected:

![](img/camel_cb_fallback.svg)


**Steps Description**

| #   | Description                                                                                                                                             |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Context data always goes through ***Circuit Breaker Configuration*** sub-element. On the diagram above, there is a fallback happened during processing. |
| 2   | ***On Fallback*** container receives context data and processes through the chain part, configured within this container.                               |

## User Interface

---
Concept is being supported by [set of elements](../../01__Chains/1__Graph/graph.md) and functionality that do have a user interface. User interface capabilities and specifics are covered by respective articles, introduced for each particular element.