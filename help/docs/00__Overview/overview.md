# Overview

Qubership Integration Platform (QIP) is an open-source solution built on Apache Camel (for more details see [Apache Camel](https://camel.apache.org/index.html)).
It enables integration between diverse systems while handling critical tasks such as data transformation (incoming/outgoing), process orchestration and mapping between different system formats.

The key concept of QIP is its use of chains, which define the workflow for processing requests — from receiving a request to generating a response. These chains are designed in the frontend interface and deployed in the backend via the Apache Camel framework. This framework manages the execution of chains using Apache Camel Context, which configures how they interact with systems [Apache Camel Context](https://camel.apache.org/manual/camelcontext.html).

QIP simplifies complex operations by separating design (frontend) and execution (backend). For example, users can create chains to automate workflows, map data formats between systems, or orchestrate processes without deep technical expertise. The platform’s modular architecture ensures scalability, adapting to evolving integration needs.