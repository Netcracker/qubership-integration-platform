# How To

## Description

---
This section collects discrete, recurring tasks: each page takes one goal and walks it through from
configuration to result. It sits between the two other kinds of page in this documentation. The
[Getting Started](../00__Overview/0__Getting_Started/readme.md) walkthrough covers a single end-to-end
path for a reader who has never used the platform. The [Chains](../01__Chains/readme.md) section documents the editor screen by screen and every element in
the library. A page here assumes you already know the screens and want to know how to combine them for a
specific outcome.

## Tasks

---

- [Build Logic Around Failed Elements](1__Build_Logic_Around_Failed_Elements/readme.md) - identify the element that failed in a session and branch error-handling logic on it.
- [Set Up Chain Availability Via Access Control](2__Set_Up_Chain_Availability_Via_Access_Control/readme.md) - restrict who can trigger a chain endpoint by validating requests against Access Control policies.
- [Switch To MaaS](3__Switch_To_MaaS/readme.md) - keep Kafka, RabbitMQ, AsyncAPI, and Service Call connection settings in MaaS instead of in each element.
- [Set An Exact Environment Address For An External Service](4__Set_An_Exact_Environment_Address_For_An_External_Service/readme.md) - label service environments so an import deploys a service to the environment you intend.
- [Retry A Session From The Middle](5__Retry_A_Session_From_The_Middle/readme.md) - add a Checkpoint element so a failed session can be restarted from a safe point instead of from the beginning.
- [Retry Events From DPT Via Kafka](6__Retry_Events_From_DPT_Via_Kafka/readme.md) - accept high-volume retry requests from DPT over a dedicated Kafka topic.
