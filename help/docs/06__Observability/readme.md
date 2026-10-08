# Observability

## Description

---
What Cloud Integration Platform writes down about its own work, and where to read it: the logs each service and
chain produces, and the metrics the engine exposes. This section is written for platform operators, SRE, and support
engineers.

Use it to answer "what happened, and where is the record of it". When you already have a symptom and want the likely
cause, start from [Troubleshooting](../07__Troubleshooting/readme.md) instead. The parameters that control
how long the records are kept live in [Features](../08__Features/readme.md).

## Topics

---

- [Platform Logging](1__Platform_Logging/readme.md) - the log types the platform produces - microservice, session,
  DPT, audit, and tracing - with their formats, levels, and destinations.
- [Metrics & Session Monitoring](2__Metrics_And_Session_Monitoring/readme.md) - the metrics
  exposed by the engine and the labels attached to them.
