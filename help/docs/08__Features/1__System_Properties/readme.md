# System Properties

## Description

---

### Overview

CIP calculates in runtime the data that might be useful for Users and Developers. These data are exposed via predefined exchange properties, which are called System Properties. Any of these properties has the following naming pattern:

```groovy
systemProperty_<nameInCamelCase>
```

### List of supported system properties

| Name                               | Data Type | Description                                                                                                                 |
|:-----------------------------------|:----------|:----------------------------------------------------------------------------------------------------------------------------|
| systemProperty\_bluegreenState     | String    | Shows the current state of environment in a Blue-Green deployment. Possible values <br/>- active<br>- candidate<br>- legacy |
| systemProperty\_idempotencyContext | String    | Context expression value which is the 1st part of full idempotency key.                                                     |
| systemProperty\_idempotencyKey     | String    | Value of key expression which is the 2nd part of full idempotency key.                                                      |
| systemProperty\_keyExpiry          | String    | Time (in seconds) until the provided idempotency key is active.                                                             |

## Process Initialization

---

- System will automatically create **systemProperty\_bluegreenState** if Blue-Green deployment is supported on current environment.
- System will automatically create **systemProperty\_idempotencyContext**, **systemProperty\_idempotencyKey** and **systemProperty\_keyExpiry** in case the idempotency is enabled both via environment parameter and chain configuration

## User Interface

---

Routing elements, such as [Condition](../../01__Chains/1__Graph/1__Elements_Library/1__Routing/5__Condition/readme.md) and [Try-Catch-Finally](../../01__Chains/1__Graph/1__Elements_Library/1__Routing/9__Try-Catch-Finally/readme.md) receive most of the benefits from having mentioned properties, as it is now possible to refer to property values building an advanced logic (e.g. via [Script](../../01__Chains/1__Graph/1__Elements_Library/5__Transformation/1__Script/readme.md) or built-in expression fields).

As an example, user may need to prohibit the request processing for any environment state except of "Active", so [Condition] element containing the **"IF"** sub-element with the code below will identify the applicable state and then route to the next steps:

```groovy
${exchangeProperty.systemProperty_bluegreenState} == 'active'
```

System properties are available in the sessions, when viewing **"Exchange** **properties**" tab (both for **[Configuration Graph]** and **[Admin Tools]** windows, under respective tab/section **"Sessions"**). This is only applicable for cases, when a chain is deployed with an option to produce logs, otherwise sessions won't be visible at all.

## Data Storage

---

Values for System Properties are stored in Camel context.
