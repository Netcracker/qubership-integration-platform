# OM to Salesforce filling fixture

Synthetic requirements for offline component tests. Catalog contracts come from the test catalog.

```text
Model: <effective-model-label>. Run: <run-id>.
Receive the OM onTaskStart event, call Salesforce createTask, and publish OM onTaskResult.
The Salesforce result is synchronous and does not introduce another receive interaction.
Set commandType to the constant completeTask in the result message.
Subject = name. When name is empty, use subRequestType and orderId as a formatted fallback.
Map priority values high, urgent, or critical to High, low to Low, and other values to Normal.
Set Status to Not Started.
Set ActivityDate from parameters.orderCreationDate, or use today's date when it is absent.
Set Description to a serialized string containing the original task information.
Retain executionId, orderId, processInstanceId, executionNumber, and taskId before the Salesforce call.
Preserve woOrderType, orderType, parameters, and the original request fields needed by the result.
On success, publish the created task result. On failure, publish error code SALESFORCE_TASK_CREATE_ERROR.
The result requires processId. Ask how it relates to the incoming processInstanceId before mapping it.
```
