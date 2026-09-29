# Retention Settings

## Description

---

### Overview

To avoid extreme space consumptions and be able to follow customer-specific retention policies,Cloud Integration Platform provides instruments to properly configure cleanup actions for next data:

- Sessions
- Action Logs
- Checkpoints
- Snapshots
- Context Records
- Idempotency Records

Settings must be specified for configuring their respective parameters either in Consul or CMDB (exactly following the steps provided with Installation Notes during environment deployment). Please refer to the specialized section below for parameters details.

### Sessions Retention

---

**[Sessions]** data is managed via [Index State Management (ISM)](https://opensearch.org/docs/latest/im-plugin/ism/index/) policy (specifically via "[opensearch-index-management](https://opensearch.org/docs/latest/install-and-configure/plugins/)" plugin). Policy start is being controlled by [plugins.index\_state\_management.job\_interval](https://opensearch.org/docs/latest/im-plugin/ism/settings/) parameter, also provided as OOB OpenSearch functionality.

Please refer to the detailed description of parameters, involved in session index control:

| Consul Parameter                                       | Mandatory   | CIP Microservice                  | Default Value   | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Sample   |
|:-------------------------------------------------------|:------------|:----------------------------------|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:---------|
| qip.opensearch.rollover.min\_index\_age                | O           | cloud-integration-platform-engine | 1d              | Index will be rotated, when current age (period between index creation and current moment) of it meets specified value. <br/> Supported units: <br/>- d (days),<br>- h (hours),<br>- m (minutes),<br>- s (seconds),<br>- ms (milliseconds)<br>- micros (microseconds) When left blank or not specified - default value is going to be applied. <br/> OpenSearch parameter: [min\_index\_age](https://opensearch.org/docs/latest/im-plugin/ism/policies/#rollover) | 12h      |
| qip.opensearch.rollover.mix\_index\_size               | O           | cloud-integration-platform-engine |                 | Index will be rotated, when current total size of it meets specified value. <br/> Supported units: <br/>- kb (kilobytes),<br>- mb (megabytes),<br>- gb (gigabytes),<br>- tb (terabytes),<br>- pb (petabytes) OpenSearch parameter: [min\_size](https://opensearch.org/docs/latest/im-plugin/ism/policies/#rollover)                                                                                                                                               | 1gb      |
| qip.opensearch.rollover.min\_rollover\_age\_to\_delete | O           | cloud-integration-platform-engine | 14d             | Previous (rotated) indices will be cleared out, when their age (period between rotation and current moment) meets specified value. <br/> When left blank or not specified - default value is going to be applied. <br/> OpenSearch parameter: [min\_rollover\_age](https://opensearch.org/docs/latest/im-plugin/ism/policies/#rollover)                                                                                                                           | 7d       |

> ℹ️
> Please pay attention to the value, specified for "plugins.index\_state\_management.job\_interval" parameter, as it might collide with the settings above (e.g. if job interval value is higher than "OPENSEARCH\_ROLLOVER\_MIN\_INDEX\_AGE" or "OPENSEARCH\_ROLLOVER\_MIN\_AGE\_TO\_DELETE", then it will lead to the policy logic delay).

### Checkpoint Retention

---

Cloud integration platform allows to configure proper retention logic for **[Checkpoints]** via next parameters, that shall be specified during deployment:

| Consul Parameter                          | Mandatory   | CIP Microservice                  | Default Value   | Description                                                               | Sample                                                                                |
|:------------------------------------------|:------------|:----------------------------------|:----------------|:--------------------------------------------------------------------------|:--------------------------------------------------------------------------------------|
| qip.sessions.checkpoints.cleanup.interval | O           | cloud-integration-platform-engine | 14 days         | Checkpoints sessions and checkpoints older than interval will be deleted. | **Days**:  ```7 days``` <br/>  **Years, Months, Days**:  ```2 years 3 month 2 days``` |
| qip.sessions.checkpoints.cleanup.cron     | O           | cloud-integration-platform-engine | 0 0 0 ? \* SAT  | When to run the checkpoint cleanup task. Accepts quartz cron expression.  | **Run at 10:00 each Friday**:  ```java<br/>0 0 10 ? * SAT<br/>```                     |

### Action Logs Retention

---

Cloud integration platform allows to configure proper retention logic for **[Action Logs]** via next parameters, that shall be specified during deployment:

| Consul Parameter                 | Mandatory | CIP Microservice                   | Default Value  | Description                                                                                                   | Sample                                                                                |
|:---------------------------------|:----------|:-----------------------------------|:---------------|:--------------------------------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------|
| qip.actions-log.cleanup.interval | O         | cloud-integration-platform-catalog | 14 days        | Action logs (audit) older than interval will be deleted. Shall contain combination of years, months and days. | **Days**:  ```7 days``` <br/>  **Years, Months, Days**:  ```2 years 3 month 2 days``` |
| qip.actions-log.cleanup.cron     | O         | cloud-integration-platform-catalog | 0 0 0 ? \* SAT | When to run action logs (audit) cleanup task. Accepts quartz cron expression.                                 | **Run at 10:00 each Friday**: ```0 0 10 ? * SAT```                                    |

### DPT Kafka Topics Retention

Cloud integration platform allows to configure proper retention logic forKafka topics,related to **[DPT events]** and [Retry events from DPT (via Kafka)](../7__Retry_Events_From_DPT_Via_Kafka/retry_events_from_dpt_via_kafka.md). Retention is controlled via next parameters, that shall be specified during deployment:

| CMDB Parameter                              | Mandatory   |   Default Value | Description                                                                                                                                                                                                                                                                                                                         |    Sample |
|:--------------------------------------------|:------------|----------------:|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------:|
| CIP\_TOPIC\_CIP\_DPT\_EVENTS\_RETENTION\_MS | O           |       604800000 | Controls amount of time (in milliseconds), until data is deleted from Kafka topics, related to DPT events (from CIP to DPT). <br/> If parameter is missing - default value of 604800000ms will be utilized by system. <br/> If parameter specified, its value **must not** be empty, as it will lead to deployment failure.         | 604800000 |
| CIP\_TOPIC\_CIP\_DPT\_RETRY\_RETENTION\_MS  | O           |       604800000 | Controls amount of time (in milliseconds), until data is deleted from Kafka topics, related to DPT retry requests (from DPT to CIP). <br/> If parameter is missing - default value of 604800000ms will be utilized by system. <br/> If parameter specified, its value **must not** be empty, as it will lead to deployment failure. | 604800000 |

### Snapshots Retention

Cloud integration Platform allows to configure proper retention logic for **[Snapshots]** of chains. Retention is controlled via next parameters, that shall be specified at the time of deployment:

| CMDB Parameter               | Consul Parameter               | CIP Microservice                   | Mandatory | Default Value  | Description                                                      | Sample                                                                         |
|:-----------------------------|:-------------------------------|:-----------------------------------|:----------|:---------------|:-----------------------------------------------------------------|:-------------------------------------------------------------------------------|
| SNAPSHOTS\_CLEANUP\_INTERVAL | qip.snapshots.cleanup.interval | cloud-integration-platform-catalog | O         | 14 days        | Snapshots older than the interval will be deleted.               | **Days**:  ```7 days```  **Years, Months, Days**: ```2 years 3 month 2 days``` |
| SNAPSHOTS\_CLEANUP\_CRON     | qip.snapshots.cleanup.cron     | cloud-integration-platform-catalog | O         | 0 0 0 ? \* SAT | Cleanup task schedule for snapshots older than cleanup interval. | **Run at 10:00 each Friday**: ```0 0 10 ? * SAT```                             |

### Context Records Retention

Cloud integration Platform allows to configure proper retention logic for context records generated when **[Context Storage]** element is utilized within chains. Retention is controlled via next parameter, that shall be specified at the time of deployment:

| CMDB Parameter                  | Consul Parameter                 | CIP Microservice                  | Mandatory | Default Value  | Description                                                            | Sample                                              |
|:--------------------------------|:---------------------------------|:----------------------------------|:----------|:---------------|:-----------------------------------------------------------------------|:----------------------------------------------------|
| CONTEXT\_RECORDS\_CLEANUP\_CRON | qip.context-service.cleanup.cron | cloud-integration-platform-engine | O         | 0 0 0 ? \* SAT | Defines date and time of regular context storage records cleanup task. | **Run at 10:00 each Friday**:  ```0 0 10 ? * SAT``` |

### Idempotency Records Retention

Cloud integration Platform allows to configure proper retention logic for idempotency records created in cases where **"Idempotency"** feature is enabled in applicable triggers within the chain *(refer **[Triggers]** pages for more details)*. Retention is controlled via next parameter, that shall be specified at the time of deployment:

| CMDB Parameter                      | Consul Param                                 | CIP Microservice                  | Mandatory   | Default Value   | Description                                   | Sample                                             |
|:------------------------------------|:---------------------------------------------|:----------------------------------|:------------|:----------------|:----------------------------------------------|:---------------------------------------------------|
| IDEMPOTENCY\_RECORDS\_CLEANUP\_CRON | qip.idempotency.expired-records-cleanup-cron | cloud-integration-platform-engine | O           | 0 0 0 ? \* SAT  | When to run idempotency records cleanup task. | **Run at 10:00 each Friday**: ```0 0 10 ? * SAT``` |

## Process Initialization

---

Process of cleanup will start as per configuration, performed during deployment.

## User Interface

---

No specific UI available.

## Data Storage

---

No specific data is stored.

## Configuration

---

Configuration is being done during the deployment. Please refer to the Installation Notes and select proper version for more details.

## Constraints

---

No specific constraints.
