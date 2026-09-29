# Retry Session from the Middle

## Description

---

### Overview

Cloud integration Platform provides an ability to restart integration flow from predefined point (point should be defined in design time). Restart is available only if chain session was not completed normally. Process details are illustrated in the diagram below.

Diagram - TBD

### Normal Flow

The 1st diagram shows the case when the session of CIP chain *xyz* was completed without errors. There are 2 configured places of restart, or [Checkpoint]s (*Checkpoint 123* and *Checkpoint 456*) from which chain session can be restarted. When the chain session passing any checkpoint:

- session context (headers, body, properties) will be saved to Engine domain database - **Persistent layer.**
- In case of payload logging is enabled, the event about passing the safe point will be logged to Graylog - **Logging layer**.
- In case of producing DPT event is enabled for chain, the "checkpoint passed" event will be sent to **DPT.**

### Error Happened

The 2nd diagram shows the failed chain processing in the middle. For this case except the mentioned actions from the 1st diagram CIP perform:

- logging of error with the last passed checkpoint URL
- In case of enabled producing DPT - sending the **"error happened"** event to DPT.

### Restart Flow

The 3rd diagram shows process of session retry by support team from *Checkpoint 123*. The session context will be restored and used for the further steps of retry flow.

> ℹ️
> Constraints
>
> - While retrying failed synchronous calls, resulted chain response won't go to the system that initially made a request, as the system has already received synchronous response when initial session failed.
> - Checkpoint data for completed sessions is not needed anymore and will be removed.
> - Checkpoint data for failed sessions should be stored according to configurable policy.
> - Retry session in case of successful completion will remove original session checkpoint data to minimize storage size.
> - Checkpoint shouldn't be placed in alternative branches (routing) because CIP is not able to store full context in this case.
> - In case checkpoint have more than one input flow only the session context from lastly added one will be stored.
> - Header ***TraceMe*** (allows full logging of session data regardless of deployment configuration)is available for checkpoint triggering endpoint.
> - Triggering of checkpoints is available only for consumers with **`ROLE_CIP_SESSION_RETRY`** accessrole.
> - When the retry will be initiated, new exchange property ***checkpointOriginalSessionId*** with id of failed session will be created.
> - Checkpoint cannot be processed within the sub-chain (chain with start element [Chain Trigger]).
> - Checkpoint cannot be processed within the [Loop] element.
> - Retry mechanism restores objects of the original classes if these classes implement **serializable interface**, for example: public class **ObjectNode** implements **java.io.Serializable** {...}.
> - Properties of object type won't be restored as part of Retry process, if the classes for such properties were defined via scripts.

## Process Initialization

---

Retry session from the middle can be triggered by event to dedicated Kafka topic (see [Retry events from DPT (via Kafka)](../7__Retry_Events_From_DPT_Via_Kafka/retry_events_from_dpt_via_kafka.md)) the particular endpoint or via session tab (under the [chain], and [Sessions](../../03__Admin_Tools/5__Sessions/session_log.md).

> ℹ️
> When retrying a failed session (via  [Retry events from DPT (via Kafka)](../7__Retry_Events_From_DPT_Via_Kafka/retry_events_from_dpt_via_kafka.md)) or a [specific endpoint]), you can ensure idempotent behavior by using the `"x-idempotency-key"` header. A retry request with a unique header value will be logged (processed) under the original session in the UI. Any duplicate retry request that carries an identical `"x-idempotency-key"` will not be processes.

## User Interface

---

In the chain [Configuration Graph] user can configure safe point by adding to the chain graph [Checkpoint] element.

## Data Storage

---

CIP operates with the next data types:

- session context of the last element for checkpoint (headers, body, properties) is stored to PostgreSQL.

## Configuration

---

To configure the retry session in the middle, the next steps should be done:

1. Set environment variables in CMDB before CIP installation to configure cleanup policy for checkpoints and error session. Details are available in Installation Notes.
2. After CIP installation add [Checkpoint](../../01__Chains/1__Graph/1__Elements_Library/3__Composite_Triggers/1__Checkpoint/checkpoint.md) to the chain graph and deploy chain.
3. Set access role **ROLE\_CIP\_SESSION\_RETRY** for the API consumer**.**

