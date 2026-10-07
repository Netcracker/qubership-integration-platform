# Glossary

## Description

---

The vocabulary Cloud Integration Platform uses in its UI, its documentation, and its APIs. Each entry links to the
page that describes the concept in full.

## Terms

---

### API Specification

A standardized description of the operations a service exposes. Specifications belong to a service, carry a status
(**New**, **In Use**, or **Deprecated**), and are uploaded manually or added by service discovery. Not available for
Context and MCP services. See [Services](../../02__Services/readme.md).

### API Specification Group

A grouping of a service's specifications by business or technical logic — for example, one group for customer
management and another for order management. Not available for Context and MCP services. See
[Services](../../02__Services/readme.md).

### Chain

An integration configuration made of Apache Camel (or customized) modules, intended to perform one particular
integration task. A chain starts with a trigger and must be deployed on at least one engine domain before anything can
trigger it. See [Chains](../../01__Chains/readme.md).

### Checkpoint

An element that sets a save point in a chain. It preserves the context passing through it and re-sends that context
when a failed session is retried, either from the **Sessions** tab or via API. See
[Checkpoint](../../01__Chains/1__Graph/1__Elements_Library/3__Composite_Triggers/1__Checkpoint/readme.md).

### Common Variable

A variable whose value is stored in Consul and visible to the user. Use it for data that is not sensitive, such as a
namespace or a server address. Compare [Secured Variable](#secured-variable). See
[Variables](../../03__Admin_Tools/2__Variables/readme.md).

### Consul

The key-value store that holds platform settings outside the catalog database: common variable values, and the
default and per-chain logging settings the engine reads at deployment time. Consul has the highest priority for
logging settings; the platform falls back to its own defaults when Consul holds none. See
[Logging](../../01__Chains/5__Logging/readme.md).

### Deployment

A snapshot placed onto a specific engine domain, which is what makes a chain reachable. A chain has one deployment
instance per engine domain, and can be deployed on several domains at once. See
[Deployments](../../01__Chains/3__Deployments/readme.md).

### Design Template

A template used to generate a design document (DDS) from chain data. Templates are uploaded under **Admin Tools** →
**Design Templates** and are available to every user of the system. See
[Design Templates](../../03__Admin_Tools/7__Design_Templates/readme.md).

### Design Time Variable

A variable referenced inside a chain with the `#{variable_name}` syntax. Its value is resolved when the chain is
deployed, so changing the value on the **Variables** tab takes effect only after you redeploy the chain or restart the
engine pod. See [Variables](../../03__Admin_Tools/2__Variables/readme.md).

### DPT

Distributed Process Tracing and Monitoring system. A chain publishes events to DPT when **Produce DPT Events** is
enabled on its **Logging** tab, and DPT can send session retry requests back to the platform over a dedicated Kafka
topic. See [Logging](../../01__Chains/5__Logging/readme.md) and
[Retry Events From DPT (via Kafka)](../../05__How_To/6__Retry_Events_From_DPT_Via_Kafka/readme.md).

### Egress Gateway

The gateway through which the platform reaches services outside its Kubernetes environment. Creating an environment
for a REST, SOAP, or GraphQL external service registers its address in the Egress gateway routing table; Kafka and
RabbitMQ external services are called directly instead. See
[External Services](../../02__Services/1__External/readme.md).

### Element

A single building block inside a chain — a trigger, a transformation, a sender, a container. You drag elements from
the library panel onto the graph and connect them in the order the integration requires. See
[Graph](../../01__Chains/1__Graph/readme.md).

### Engine

A pod that executes deployed chains with the Apache Camel framework. At deployment time each engine retrieves the
chain details it needs from the catalog over REST. See
[Getting Started](../0__Getting_Started/readme.md#how-a-chain-reaches-an-engine).

### Engine Domain

A Kubernetes deployment holding one or more engine pods. A chain deployed on a domain is deployed on every engine pod
under it. Domains are either **Classic**, pre-configured via the deployment descriptor, or **Micro**, provisioned on
demand as a Camel K custom resource when you deploy to a domain name that does not exist yet. See
[Domains](../../03__Admin_Tools/1__Domains/readme.md).

### Environment

A service's address for one target landscape — Dev, QA, or Production — by which the platform calls its API. External
services may have multiple environments and switch between them; Inner Cloud and Implemented services have exactly
one; Context and MCP services have none. See [Services](../../02__Services/readme.md).

### Graph

The blueprint-like work environment in which you add, connect, and edit the elements that form a chain. See
[Graph](../../01__Chains/1__Graph/readme.md).

### Import Instruction

A configuration pairing an entity with the action to take on it during import: remove it, ignore it, or override a
chain with a new version. Instructions extend the standard import logic. See
[Import Instructions](../../03__Admin_Tools/4__Import_Instructions/readme.md).

### Live Exchange

An active, unfinished exchange inside a deployed chain. Unfinished exchanges hold processing threads, so the
**Live Exchanges** tab lets you find the resource-intensive ones and terminate them. See
[Live Exchanges](../../03__Admin_Tools/8__Live_Exchanges/readme.md).

### MaaS

The service the platform integrates with to hold messaging connection details in one place. With MaaS enabled, an
**AsyncAPI Trigger**, **Kafka Trigger/Sender**, **RabbitMQ Trigger/Sender**, or **Service Call** element references
those settings instead of carrying its own connection configuration. See
[Switch To MaaS](../../05__How_To/3__Switch_To_MaaS/readme.md).

### Masking

Log masking applied to named fields so that their values are not exposed in session logs. Configure the fields on a
chain's **Masking** tab, and enable **"Enable logging masking"** on its **Logging** tab. See
[Masking](../../01__Chains/6__Masking/readme.md).

### Operation

One endpoint within an API specification, corresponding to a particular business operation. Not available for Context
and MCP services. See [Services](../../02__Services/readme.md).

### Reuse

A container element holding a repeatable part of chain logic — error handling or validation, typically — that other
parts of the chain invoke through a **Reuse Reference** element instead of duplicating it. See
[Reuse](../../01__Chains/1__Graph/1__Elements_Library/1__Routing/2__Reuse/readme.md).

### Secured Variable

A variable for protected data such as credentials. Values are stored in Kubernetes secrets and hidden in the UI. To
reference one that lives in a non-default secret, prefix the name with the secret name and a `:` delimiter. See
[Variables](../../03__Admin_Tools/2__Variables/readme.md).

### Service

An entity holding the integration settings of a real system inside or outside the platform: API specifications,
environment addresses, properties. There are five types — [External](../../02__Services/1__External/readme.md),
[Inner Cloud](../../02__Services/2__Inner_Cloud/readme.md),
[Implemented](../../02__Services/3__Implemented/readme.md),
[Context](../../02__Services/4__Context/readme.md), and [MCP](../../02__Services/5__MCP/readme.md) — described in
[Services](../../02__Services/readme.md):

- **External** — a service located outside the environment, reachable only via the Egress Gateway.
- **Inner Cloud** — also called an internal service; it shares the Kubernetes environment with the platform and is
  called directly.
- **Implemented** — a custom service, usually created from an HTTP Trigger.
- **Context** — a database instance used to store chain contexts, so a chain can create, read, and delete context
  data.
- **MCP** — a service exposing chains as tools through the Model Context Protocol.

### Session

The record of one chain processing a request step by step. A session is created when a chain is triggered
successfully, and sessions sharing a correlation id are grouped together in the **Sessions** table. See
[Sessions](../../01__Chains/4__Sessions/readme.md).

### Snapshot

A chain state captured at a particular moment, stored as an XML representation of the chain. A snapshot is what a
deployment refers to, and a chain can be reverted to any snapshot that still exists. See
[Snapshots](../../01__Chains/2__Snapshots/readme.md).

### Swimlane

A grouping element that gathers parts of a chain into colored blocks, so the graph reads more clearly. The first
swimlane captures the whole chain and is labeled `Default`; **Reuse** elements are always captured in a separate
swimlane labeled `Reuse`. See
[Swimlane](../../01__Chains/1__Graph/1__Elements_Library/8__Grouping/1__Swimlane/readme.md).

### Variable

A data item usable in chain elements, services, and configuration management settings. Variables are either
[common](#common-variable) or [secured](#secured-variable). See
[Variables](../../03__Admin_Tools/2__Variables/readme.md).
