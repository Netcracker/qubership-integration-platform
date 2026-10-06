# Overview

## Description

---

Cloud Integration Platform (CIP) is an open-source solution built on Apache Camel (for more details see
[Apache Camel](https://camel.apache.org/index.html)).
It enables integration between diverse systems while handling critical tasks such as data transformation
(incoming/outgoing), process orchestration and mapping between different system formats.

The key concept of CIP is its use of chains, which define the workflow for processing requests — from receiving a
request to generating a response. These chains are designed in the frontend interface and deployed in the backend via
the Apache Camel framework. This framework manages the execution of chains using Apache Camel Context, which
configures how they interact with systems
[Apache Camel Context](https://camel.apache.org/manual/camelcontext.html).

CIP simplifies complex operations by separating design (frontend) and execution (backend). For example, users can
create chains to automate workflows, map data formats between systems, or orchestrate processes without deep technical
expertise. The platform's modular architecture ensures scalability, adapting to evolving integration needs.

## This Chapter

---

**Overview** holds the orientation material: the guided path through the product, the concepts the rest of the
documentation assumes, and the vocabulary it uses. It describes how the platform works rather than which button to
press; the screen-by-screen reference lives in [Chains](../01__Chains/chains.md) and the chapters after it.

- [Getting Started](0__Getting_Started/getting_started.md) - the path from designing a chain in the UI, through a
  snapshot, to a deployment on an engine, then one minimal chain built end-to-end.
- [Apache Camel Context Concept](1__Apache_Camel_Context_Concept/apache_camel_context_concept.md) - the Exchange
  object that carries properties, headers, and body between the elements of a chain.
- [General Functions](2__General_Functions/general_functions.md) - the UI chrome shared by every screen:
  notifications, filters, table settings, and theme.
- [Architecture](3__Architecture/cip_architecture.md) - the platform components, the data stores behind them, and
  where each kind of data lives.
- [Glossary](4__Glossary/glossary.md) - every term the documentation uses, each linking to the page that owns it.

## Where to Go Next

---

- **New to the platform?** Read [Getting Started](0__Getting_Started/getting_started.md), then keep
  [Glossary](4__Glossary/glossary.md) open beside it.
- **Working out how authentication reaches a chain?**
  [Token Processing](../08__Features/2__Token_Processing/token_processing.md) follows a token from the trigger through
  to the outgoing request.
- **Designing chains?** [Chains](../01__Chains/chains.md) documents the editor, the element library, snapshots,
  deployments, and sessions. [Services](../02__Services/services.md) covers the systems a chain calls.
  [How To](../05__How_To/how_to.md) collects the recurring tasks.
- **Administering the product?** [Admin Tools](../03__Admin_Tools/admin_tools.md) covers domains, variables, audit,
  import instructions, access control, and design templates.
  [Dev Tools](../04__Dev_Tools/dev_tools.md) holds the non-production helpers.
- **Running the platform?** [Observability](../06__Observability/observability.md) covers the logs and metrics the
  platform produces, [Troubleshooting](../07__Troubleshooting/troubleshooting.md) maps symptoms to causes, and
  [Features](../08__Features/features.md) documents the mechanisms behind them: system properties, token processing,
  retention, and multitenancy.
