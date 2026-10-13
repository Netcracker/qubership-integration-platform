# Overview

Qubership Integration Platform (QIP) is an open-source solution built on Apache Camel (for more details see [Apache Camel](https://camel.apache.org/index.html)).
It enables integration between diverse systems while handling critical tasks such as data transformation (incoming/outgoing), process orchestration and mapping between different system formats.

The key concept of QIP is its use of chains, which define the workflow for processing requests — from receiving a request to generating a response. These chains are designed in the frontend interface and deployed in the backend via the Apache Camel framework. This framework manages the execution of chains using Apache Camel Context, which configures how they interact with systems [Apache Camel Context](https://camel.apache.org/manual/camelcontext.html).

QIP simplifies complex operations by separating design (frontend) and execution (backend). For example, users can create chains to automate workflows, map data formats between systems, or orchestrate processes without deep technical expertise. The platform’s modular architecture ensures scalability, adapting to evolving integration needs.

## OpenCode chat

The **OpenCode** icon in the navigation bar opens a separate chat connected directly to an OpenCode 1.19.0 server.
The connection form accepts a local server address or a remote HTTPS address, with a username and password when required.
The server must permit the UI origin through its CORS settings.

The chat supports session history, file attachments, streamed replies, tool activity, permission requests, and questions
with answer choices or a custom response. **New chat** creates a session. **Stop** interrupts execution. **Clear** deletes
the selected OpenCode session after confirmation and starts a new one.

Attachments are limited to five files of up to 10 MB each. Supported formats include text, Markdown, JSON, YAML, CSV,
PDF, PNG, JPEG, GIF, and WebP. Image and PDF interpretation depends on the model configured in OpenCode.
