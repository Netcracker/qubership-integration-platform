# Logging (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>
## Description

---
This tab provides consolidated access to the logging capabilities, that could be applied to the chain, when it is deployed.
## User Interface

----
### View Logging Settings

User is able to navigate to the **"Logging Settings"** tab under the chain and view/correct logging settings for the chain. It shall be noted that Qubership Integration Platform will always attempt to use the settings that are available in the Consul, which plays the role of the main source of such data. When no settings available in the Consul for a given chain and there are no even default settings available there, Qubership Integration Platform will apply its own default hard-coded option and notify the user via message on top of the screen. Please view detailed description for each particular setting below:

- **Override default properties** switcher - allows to enable other fields and specify own settings for current chain.
- **Logging settings source** - label, that shows the source of the settings. Possible values:
	- **Default (Consul)** - when default settings from Consul are applied.
	 - **Custom** - when chain's custom settings from Consul are applied.
	- **Default (Fallback)** - when Consul has no settings available, and system applied its own default values. In this case, system will also show a proper warning message.
- **Sessions Level** - level of logs for chain [sessions](docs/01__Chains/4__Sessions/sessions.md). Possible values:
    - **Off** _(Default value)_ - logging is fully turned off.
    - **Error** -  only sessions failed with errors are going to be logged. If failed elements are part of sub-chain(s), session will also show Chain Call(s) to maintain proper structure. This level of logging has low affect on performance, as it handles only failed sessions and considers writing the data, related to the failed element only. Memory and storage capacity consumption is also considered to be on low level.
    - **Info** - only completed inbound/outbound communications, as well as failed sessions are going to be logged. When mentioned elements are part of sub-chain(s), session will also contain Chain Call(s) to maintain proper structure. This level of logging has medium affect on performance in general, as well as on memory and storage capacity consumption.
    - **Debug** - all available parameters will be logged: session status, status of each chain element, headers, body (request and response). This level of logging _drastically_ increases the consumption of memory and storage capacity, as well as negatively affects the performance in general, hence shall be used carefully.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
       <b>Note:</b><br>
         You can also log a particular transaction with a <b>Debug</b> session level by sending <b>"TraceMe"</b> header with "true" value in the HTTP request, which will make all available session parameters logged at <b>Debug level</b>, regardless of the current state of chain's logging settings.
</div>


- **Log Level** - supported log levels. For proper logging, it should not be higher than logging level, globally specified for microservice. Possible values:
    - **Error** _(Default value)_ - logging of Integrations with Exceptions. Log levels - ERROR, FATAL.
    - **Warning** - logging of Integrations with Warnings. Log level - WARN, ERROR, FATAL.
    - **Info** - log only external communications (sending and receiving external messages by any protocol, incoming and outgoing). Used for investigation of production incidents.
* **Log Payload** - controls payload parts, which must be logged: Headers, Properties, Body. If nothing is selected - no payload data will be logged.
- **Produce DPT Events** - based on selected option, system either sends DPT events or not:
    - **true** - deployed chain will be publishing events to DPT (Distributed Process Tracing and Monitoring system).
    - **false** _(Default value)_ - no events will be published to DPT.
- **Enable Logging Masking** - based on selected option, system decides if masking must be applied to configured set of parameters:
    - **true** _(Default value)_ - masking settings, configured under "Masked Fields" expand will be applied.
    - **false** - no masking applied.
- **Apply** button - when custom settings are specified, they MUST be applied via this button. As the result of this operation, settings will be published to the Consul.
