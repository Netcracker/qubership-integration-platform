# System Properties

## Description

---

### Overview

CIP calculates in runtime the data that might be useful for Users and Developers. These data are exposed via predefined exchange properties, which are called System Properties. Any of these properties has the following naming pattern:

```groovy
systemProperty_<nameInCamelCase>
```

### List of supported system properties

| Name                               | Data Type | Description                                                                                                                                                                                                                                                                                                                 |
|:-----------------------------------|:----------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| systemProperty\_bluegreenState     | String    | Shows the current state of environment in a Blue-Green deployment. Possible values <br/>- active<br>- candidate<br>- legacy                                                                                                                                                                                                 |
| systemProperty\_idempotencyContext | String    | Context expression value which is the 1st part of full idempotency key. <br/>⚠️ Accessible only in case the idempotency is enabled both via environment parameter and chain configuration (for the details, please, check the [HTTP Trigger] and Installation Notes pages). <br/> Configuration details are available here: |
| systemProperty\_idempotencyKey     | String    | Value of key expression which is the 2nd part of full idempotency key. <br/>⚠️ Accessible only in case the idempotency is enabled both via environment parameter and chain configuration (for the details, please, check the [HTTP Trigger] and Installation Notes pages).                                                  |
| systemProperty\_keyExpiry          | String    | Time (in seconds) until the provided idempotency key is active. <br/>⚠️ Accessible only in case the idempotency is enabled both via environment parameter and chain configuration (for the details, please, check the [HTTP Trigger] and Installation Notes pages).                                                         |

## Process Initialization

---

- System will automatically create **systemProperty\_bluegreenState** if Blue-Green deployment is supported on current environment.
- System will automatically create **systemProperty\_idempotencyContext**, **systemProperty\_idempotencyKey** and **systemProperty\_keyExpiry** in case the idempotency is enabled both via environment parameter and chain configuration

## User Interface

---

Routing elements, such as [Condition] and [Try-Catch-Finally] receive most of the benefits from having mentioned properties, as it is now possible to refer to property values building an advanced logic (e.g. via [Script] or built-in expression fields).

As an example, user may need to prohibit the request processing for any environment state except of "Active", so [Condition] element containing the **"IF"** sub-element with the code below will identify the applicable state and then route to the next steps:

```groovy
${exchangeProperty.systemProperty_bluegreenState} == 'active'
```

System properties are available in the sessions, when viewing **"Exchange** **properties**" tab (both for **[Configuration Graph]** and **[Admin Tools]** windows, under respective tab/section **"Sessions"**). This is only applicable for cases, when a chain is deployed with an option to produce logs, otherwise sessions won't be visible at all.

## Data Storage

---

Values for System Properties are stored in Camel context.

## Configuration

---

No specific configuration available.

## API Details

---

No specific API available.
