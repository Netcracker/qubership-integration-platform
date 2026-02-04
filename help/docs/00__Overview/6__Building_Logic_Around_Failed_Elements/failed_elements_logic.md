# Building Logic Around Failed Elements
## Description

---
In order to determine failed elements in the chains and be able to utilize them within handling logic (via Scripting, Mapping, etc.) Qubership Integration Platform has introduced two additional properties:
- failed-element-name
- failed-element-id

These properties are available in the sessions, when viewing **"Exchange properties"** tab for failed elements (both for [Graph](docs/01__Chains/1__Graph/graph.md) and [Admin Tools](docs/03__Admin_Tools/admin_tools.md) windows, under respective tab/section "Sessions"). This is only applicable for cases, when a chain is deployed with an option to produce logs, otherwise sessions won't be visible at all.

## User Interface

---
Routing elements, such as [Condition](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/5__Condition/condition.md) and [Try-Catch-Finally](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/9__Try-Catch-Finally/try-catch-finally.md) receive most of the benefits from having mentioned properties, as it is now possible to refer to failed elements when building an advanced logic (e.g. via [Script](docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/1__Script/script.md)) in **"If"** and **"Catch"** sub-elements.

As an example, **"IF"** (sub-element for [Condition](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/5__Condition/condition.md)) with the code below could be placed inside **"Catch"** element to not only catch the error, but also build a logical "fork", based on the failed element id to differ the flows:
<div><pre style="background-color: #F5F5F7;"><code style="color: #000000;">${exchangeProperty.failed-element-id} == 'b319372b-0003-4ba7-9e11-77bfa442f749'</code></pre></div>


Another example, mentioned below, returns a custom error (that could be configured within [Script](docs/01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/1__Script/script.md), placed inside **"Catch"** sub-element) that contains failed element id:

<div><pre style="background-color: #F5F5F7;"><code style="color: #000000;">exchange.getMessage().setBody("Element with id " + exchange.getProperty("failed-element-id") + " failed")</code></pre></div>