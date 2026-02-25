# Circuit Breaker
## Description

---
**Circuit Breaker** element is intended to prevent the invocation of outbound service when service malfunction is detected, and failure threshold reached. This element is mainly used with [Service Call](../../7__Senders/6__Service_Call/service_call.md) or [HTTP Sender](../../7__Senders/4__HTTP_Sender/http_sender.md).

The Circuit Breaker switches between three states:

- **Closed** — default state, Circuit Breaker stays in this state while integration works normally. In this state, Circuit breaker allows invoking outbound service and don't interfere into integration flow.
- **Open** — Circuit Breaker switches to this state, when requests failures reach the configured threshold. In this state, the circuit breaker disrupts integration with malfunctioned outbound service, decreasing the load on the struggling service and integration route.
- **Half Open** — after configured amount of time, Circuit Breaker switched to this state and allows to invoke outbound service to insure whenever it is capable to accept the requests. Depending on the outcome, Circuit Breaker either switches to Open or Closed state.

## User Interface

---
### "Parameters" Tab (Circuit Breaker)
#### Metadata

| Parameter   | Mandatory | Data Type | Description                                    | Sample                         |
| ----------- | :-------- | :-------- | ---------------------------------------------- | ------------------------------ |
| Name        | M         | String    | Name of the Circuit Breaker container element. | Circuit Breaker #1             |
| Description | O         | String    | Free text field for element description.       | Check failures for operation A |

### "Parameters" Tab (Circuit Breaker Configuration)
#### Common Parameters
| Parameter                                         | Mandatory | Data Type | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | Sample      |
| ------------------------------------------------- | :-------- | :-------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| Automatic transition from open to half open state | O         | Boolean   | Checkbox, that enables automatic transition from OPEN to HALF_OPEN state once the time, configured in "Wait duration in open state" has passed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Unchecked   |
| Failure rate threshold                            | M         | Number    | Configures the failure rate threshold in percentage. If the failure rate is **equal** or **greater** than the threshold - the Circuit Breaker transitions to **Open State** and starts short-circuiting calls.<br>The threshold must be greater than 0 and not greater than 100.<br>**Default value:** 50                                                                                                                                                                                                                                                                                                             | 50          |
| Minimum number of calls                           | M         | Number    | Configures the minimum number of calls before the Circuit Breaker can calculate the failure rate. For example, if **"Minimum number of calls"** is 10, then at least 10 calls must be recorded, before the failure rate can be calculated. If only 9 calls have been recorded the Circuit Breaker will not transition to open even if all 9 calls have failed.  <br>"Minimum number of calls" must be greater than 0.  <br>**Default value:** 100                                                                                                                                                                     | 100         |
| Permitted number of calls in half open state      | M         | Number    | Configures the number of permitted calls when the Circuit Breaker is Half Open. When settled number of successful calls is being made in Half Open state, Circuit Breaker transitions to "Closed" state.   <br>The size must be greater than 0.  <br>**Default value:** 10.                                                                                                                                                                                                                                                                                                                                           | 10          |
| Sliding window size                               | M         | Number    | Configures the size of the sliding window which is used to record the outcome of calls when the Circuit Breaker is **Closed**. This is the range, within which the Circuit Breaker will be validating the failure rate. The value will be considered as **number of calls**, if the **"Sliding window type"** is COUNT_BASED, and as **seconds**, if the **"Sliding window type** is TIME_BASED.<br><ul><li>"Sliding window size" must be greater than 0</li><li>"Sliding window size" must be greater than value in "Minimum number of calls", if Sliding window type is COUNT_BASED</li></ul>**Default value:** 100 | 100         |
| Sliding window type                               | O         | List      | Configures the type of the sliding window, which is used to record the outcome of calls when the Circuit Breaker is closed.<ul><li>COUNT_BASED type means that Circuit Breaker will be sequentially aggregating the calls and control the window size by the number of calls.</li><li>TIME_BASED type means that Circuit Breaker will control the window size via time interval.</li></ul>**Default value:** COUNT_BASED.                                                                                                                                                                                             | COUNT_BASED |
| Slow call duration threshold (s)                  | M         | Number    | Configures the call duration in seconds, above which calls are considered as slow and increase the slow calls percentage.<br>**Default value:** 60                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | 60          |
| Slow call rate threshold (%)                      | M         | Number    | Configures a threshold in percentage. The Circuit Breaker considers a call as slow when the call duration is greater than value in **"Slow call duration threshold"**. When the percentage of slow calls is **equal** or **greater** the threshold, the Circuit Breaker transitions to **Open state** and starts short-circuiting calls.  <br>The threshold must be greater than 0 and not greater than 100.  <br>**Default value:** 100                                                                                                                                                                              | 100         |
| Wait duration in open state (s)                   | M         | Number    | Configures the wait duration (in seconds) which specifies how long the Circuit Breaker should stay open, before it switches to half open.  <br>**Default value:** 60                                                                                                                                                                                                                                                                                                                                                                                                                                                  | 60          |

#### Metadata

| Parameter   | Mandatory | Data Type | Description                                                  | Sample                        |
| ----------- | :-------- | :-------- | ------------------------------------------------------------ | ----------------------------- |
| Name        | M         | String    | Name of the Circuit Breaker Configuration container element. | Circuit Breaker Configuration |
| Description | O         | String    | Free text field for element description.                     | Detailed settings             |

### "Parameters" Tab (On Fallback)
#### Metadata
| Parameter   | Mandatory | Data Type | Description                              | Sample           |
| ----------- | :-------- | :-------- | ---------------------------------------- | ---------------- |
| Name        | M         | String    | Name of the Fallback container element.  | On fallback      |
| Description | O         | String    | Free text field for element description. | Fallback actions |

## Constraints

---
Please consider next constraints:
- Only one instance of "**Circuit Breaker Configuration**" can be added to Circuit Breaker module.
- Only one instance of "**On Fallback**" can be added to Circuit Breaker module.
- When circuit breaker fails, all processing data within circuit breaker configuration branch, including headers, properties and body will be **erased**. This means, that there won't be any ability to refer to this data outside the configuration element, even via "On fallback" element.
