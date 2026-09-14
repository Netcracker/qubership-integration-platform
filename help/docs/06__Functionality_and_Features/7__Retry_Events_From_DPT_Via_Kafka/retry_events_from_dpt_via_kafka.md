# Retry events from DPT (via Kafka)


## Description

---

To be able to process large amount of retry events from DPT, it is decided to implement Kafka-based connection between DPT and Cloud Integration Platform.Cloud Integration platform will expose Kafka topic for DPT, so it could publish event for each retry operation initiated from DPT.

### Kafka event message structure

**Header**

| Parameter         | Mandatory   | Data Type   | Description                                                                                                                                                                                                                                                                                                                       |
|:------------------|:------------|:------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| chainId           | M           | String      | Chain identifier in UUID format.. Contains id of the chain, session related to.                                                                                                                                                                                                                                                   |
| tenant            | O           | String      | Tenant identifier.                                                                                                                                                                                                                                                                                                                |
| sessionId         | M           | String      | Unique chain identifier in UUID format.. Contains id of the chain to be retried.                                                                                                                                                                                                                                                  |
| elementId         | O           | String      | Chain's element identifier in UUID format.                                                                                                                                                                                                                                                                                        |
| actionId          | M           | String      | Always has to be "**Retry**" for event, sent to CIP.                                                                                                                                                                                                                                                                              |
| category          | O           | String      | Category, available on DPT side. Passed when exists and logged by CIP to Graylog when error happens during retry.                                                                                                                                                                                                                 |
| eventDate         | M           | Number      | Event generation date.                                                                                                                                                                                                                                                                                                            |
| x-version         | O           | String      | X-Version value, required for blue-green deployment.                                                                                                                                                                                                                                                                              |
| x-version-name    | O           | String      | X-version name of application that produces the event in blue-green deployment scenario.                                                                                                                                                                                                                                          |
| x-idempotency-key | O           | String      | Allows to support idempotency for the DPT initiated retry requests.  <br/>⚠️ The Time-to-Live (TTL) for the x-idempotency-key is determined through the environment parameter **`SESSIONS_CHECKPOINTS_CLEANUP_INTERVAL`**, i.e., calculated by adding the parameter value *(default is set to 1 month)* to the current timestamp. |

  
> ℹ️
> In case **x-idempotency-key** is not provided, the functionality would work As-is.

**Body**

| Parameter   |    | Mandatory   | Data Type   | Description                                                                                                                                                                                            |
|:------------|:---|:------------|:------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| headers     |    | O           | Object      | Container for headers. CIP will prioritize the headers from this object while building retry request, which means that it will override the value of "preserved" header if new value is passed by DPT. |

**Retry message sample:**

Headers
```json
{
"chainId": "fca94b81-2ecc-466c-af1e-432901e90661",
"tenant": "fca94b81-2ecc-466c-af1e-432901e90664",
"sessionId": "fca94b81-2ecc-466c-af1e-432901e90662",
"elementId": "fca94b81-2ecc-466c-af1e-432901e90663",
"actionId": "Retry",
"category": "Service Activation Request failed",
"eventDate": 1623392004283,
"x-version": "v2",
"x-version-name": "candidate",
"x-idempotency-key": "<key_value>"
}
```
Body
```json
{
  "headers": {
    "sample_header": "sample_value"
  }
}
```

> ℹ️
> Auth. token in the body is being analyzed by Security team.

## Process Initialization

---

Process is being initialized by DPT. Appropriate sessions could be selected in DPT and passed to CIP via Kafka to be retried from last checkpoint.

## User Interface

---

No specific user interface available.

## Data Storage

---

During event processing, some specific data could be logged, according to the main [Logging] article.

## Configuration

---

For correct processing, next items shall be considered:

- retry topic: **cip-dpt-retry-\<namespace\>**
- **X-Idempotency-Key** header from CIP-dpt-retry topic is utilized to identify that the session retry requests are idempotent.
- Value for parameter **actionId** in retry event shall be **"Retry"** (every message with different action will result in SESSION\_HANDLING\_FAILURE event, that is going to be sent to DPT).
- errorMessage in SESSION\_HANDLING\_FAILURE event is hardcoded to **"Session retry failed during event handling"** and shall be sent to DPT as well.

## API Details

---

No specific API details available.
