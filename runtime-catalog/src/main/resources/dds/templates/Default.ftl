<#assign EMPTY_PLACEHOLDER = "N/A">
# Version History

| Version | Date | Author | Description of change |
|---------|:-----|--------|-----------------------|
|         |      |        |                       |

# Document References

| # | Name | Description  |
|---|:-----|--------------|
|   |      |              |

# Glossary of Terms

| Acronym | Interpretation |
|---------|:---------------|
|         |                |

# Distributions List

| Name | Organization | Role | Email |
|------|:-------------|------|-------|
|      |              |      |       |

# Introduction
## Document Purpose

This document is intended to define and describe integrations, interfaces and methods, developed to establish communication between Qubership Solution and other parties in the scope of the current project to support required functionality.

## Document Objectives

Interface Agreement Document is aimed to achieve the following objectives:

- Identify implementation scope
- Provide clear integration flow description and implementation scope
- Provide clear description of scenarios and methods, utilized as part of integration scope
- Provide a baseline for testing activities

## Intended Audience

This document is intended for nominated business and IT representatives to verify alignment of the proposed solution with the requirements in scope of current project. It also intended for further usage by system developers, testing and security teams.

## Assumptions

${chain.doc.assumptions!EMPTY_PLACEHOLDER}

## Out of Scope

${chain.doc.outOfScope!EMPTY_PLACEHOLDER}

# Integration Scenarios

This section contains integration specific data of the chain, implemented in order to comply with the requirements.

## ${chain.name}

${chain.doc.businessDescription!EMPTY_PLACEHOLDER}

![simpleSeqMermaidDiagram](./img/simple-sequence-diagram-mermaid.svg)

# Logging Functionality

Logging levels can be updated according to the environment and project requirements in run-time mode for each particular chain. Switching between different logging settings won't require chain redeploy.

# Basic Data Type Definition

| Data Type | Description  |
|-----------|:-------------|
|           |              |

# Integration Methods

<#-- ===================== Triggers (with implemented service) ===================== -->

<#list chain.elements.httpTriggersImplemented as trigger>
## ${trigger.name}

| Interface Details    | Value                                            |
|----------------------|:-------------------------------------------------|
| Trigger Type         | ${trigger.typeName}                              |
| Description          | ${trigger.description!EMPTY_PLACEHOLDER}         |
| URI                  | ${trigger.properties.endpointUri}                |
| Allowed Methods      | ${trigger.properties.endpointAllowedMethods}     |
| Allowed Content Type | ${trigger.properties.validateRequestContentType} |


### Request

Request parameters mapping is presented below:

    <#if trigger.properties.requestSchema?size = 0>
| Parameter                | Mandatory/Optional        | Type                     | Description                   |
|--------------------------|:--------------------------|--------------------------|-------------------------------|
|                          |                           |                          |                               |

Request Sample:

```
${EMPTY_PLACEHOLDER}
```
    </#if>
    <#list trigger.properties.requestSchema?keys as contentType>
-   **Content type: `${contentType}`**

    | Parameter                | Mandatory/Optional        | Type                     | Description                   |
    |--------------------------|:--------------------------|--------------------------|-------------------------------|
        <#list trigger.properties.requestSchema[contentType].properties as property>
    | ${property.name}         | ${property.optionality}   | ${property.type!EMPTY_PLACEHOLDER}   | ${property.description!EMPTY_PLACEHOLDER} |
        </#list>
        <#if trigger.properties.requestSchema[contentType].properties?size = 0>
    |                          |                           |                          |                               |
        </#if>

        <#list trigger.properties.requestSchema[contentType].definitions?keys as definitionName>
    -   **`${definitionName}`**

        | Parameter                | Mandatory/Optional        | Type                     | Description                   |
        |--------------------------|:--------------------------|--------------------------|-------------------------------|
            <#list trigger.properties.requestSchema[contentType].definitions[definitionName] as property>
        | ${property.name}         | ${property.optionality}   | ${property.type!EMPTY_PLACEHOLDER}   | ${property.description!EMPTY_PLACEHOLDER} |
            </#list>
            <#if trigger.properties.requestSchema[contentType].definitions?size = 0>
        |                          |                           |                          |                               |
            </#if>

        </#list>
    Request Sample:

    ```
    ${EMPTY_PLACEHOLDER}
    ```
    </#list>


### Response

Response parameters mapping is presented below:

    <#if trigger.properties.responseSchema?size = 0>
| Parameter                | Mandatory/Optional        | Type                     | Description                   |
|--------------------------|:--------------------------|--------------------------|-------------------------------|
|                          |                           |                          |                               |

Response Sample:

```
${EMPTY_PLACEHOLDER}
```
    </#if>
    <#list trigger.properties.responseSchema?keys as responseCode>
-   **Response code: `${responseCode}`**
        <#list trigger.properties.responseSchema[responseCode]?keys as contentType>
    -   **Content type: `${contentType}`**

        | Parameter                | Mandatory/Optional        | Type                     | Description                   |
        |--------------------------|:--------------------------|--------------------------|-------------------------------|
            <#list trigger.properties.responseSchema[responseCode][contentType].properties as property>
        | ${property.name}           | ${property.optionality} | ${property.type!EMPTY_PLACEHOLDER}   | ${property.description!EMPTY_PLACEHOLDER} |
            </#list>
            <#if trigger.properties.responseSchema[responseCode][contentType].properties?size = 0>
        |                            |                         |                          |                               |
            </#if>

            <#list trigger.properties.responseSchema[responseCode][contentType].definitions?keys as definitionName>
        -   **`${definitionName}`**

            | Parameter                | Mandatory/Optional        | Type                     | Description                   |
            |--------------------------|:--------------------------|--------------------------|-------------------------------|
                <#list trigger.properties.responseSchema[responseCode][contentType].definitions[definitionName] as property>
            | ${property.name}         | ${property.optionality}   | ${property.type!EMPTY_PLACEHOLDER}   | ${property.description!EMPTY_PLACEHOLDER} |
                </#list>
                <#if trigger.properties.responseSchema[responseCode][contentType].definitions?size = 0>
            |                          |                           |                          |                               |
                </#if>

            </#list>

        Response Sample:

        ```
        ${EMPTY_PLACEHOLDER}
        ```
        </#list>
    </#list>

</#list>

<#-- ===================== HTTP Service Calls ===================== -->

<#list chain.elements.httpServiceCalls as sc>
## ${sc.name}

| Interface Details    | Value                                                          |
|----------------------|:---------------------------------------------------------------|
| Sender Type          | ${sc.typeName}                                                 |
| Service              | ${sc.properties.serviceName}                                   |
| Specification        | ${sc.properties.specificationName}                             |
| Operation            | ${sc.properties.operationName!EMPTY_PLACEHOLDER}               |
| Operation Type       | ${sc.properties.operationType}                                 |

### Request

Request parameters mapping is presented below:

    <#if sc.properties.requestSchema?size = 0>
| Parameter                | Mandatory/Optional        | Type                     | Description                   |
|--------------------------|:--------------------------|--------------------------|-------------------------------|
|                          |                           |                          |                               |

Request Sample:

```
${EMPTY_PLACEHOLDER}
```
    </#if>
    <#list sc.properties.requestSchema?keys as contentType>
-   **Content type: `${contentType}`**

    | Parameter                | Mandatory/Optional        | Type                                 | Description                               |
    |--------------------------|:--------------------------|--------------------------------------|-------------------------------------------|
        <#list sc.properties.requestSchema[contentType].properties as property>
    | ${property.name}         | ${property.optionality}   | ${property.type!EMPTY_PLACEHOLDER}   | ${property.description!EMPTY_PLACEHOLDER} |
        </#list>
        <#if sc.properties.requestSchema[contentType].properties?size = 0>
    |                          |                           |                                      |                                           |
        </#if>

        <#list sc.properties.requestSchema[contentType].definitions?keys as definitionName>
    -   **`${definitionName}`**

        | Parameter                | Mandatory/Optional        | Type                                 | Description                               |
        |--------------------------|:--------------------------|--------------------------------------|-------------------------------------------|
            <#list sc.properties.requestSchema[contentType].definitions[definitionName] as property>
        | ${property.name}         | ${property.optionality}   | ${property.type!EMPTY_PLACEHOLDER}   | ${property.description!EMPTY_PLACEHOLDER} |
            </#list>
            <#if sc.properties.requestSchema[contentType].definitions?size = 0>
        |                          |                           |                                      |                                           |
            </#if>

    </#list>
    Request Sample:

    ```
    ${EMPTY_PLACEHOLDER}
    ```
    </#list>


### Response

Response parameters mapping is presented below:

    <#if sc.properties.responseSchema?size = 0>
| Parameter                | Mandatory/Optional        | Type                     | Description                   |
|--------------------------|:--------------------------|--------------------------|-------------------------------|
|                          |                           |                          |                               |

Response Sample:

```
${EMPTY_PLACEHOLDER}
```
    </#if>
    <#list sc.properties.responseSchema?keys as responseCode>
-   **Response code: `${responseCode}`**
        <#list sc.properties.responseSchema[responseCode]?keys as contentType>
    -   **Content type: `${contentType}`**

        | Parameter                | Mandatory/Optional        | Type                                 | Description                               |
        |--------------------------|:--------------------------|--------------------------------------|-------------------------------------------|
            <#list sc.properties.responseSchema[responseCode][contentType].properties as property>
        | ${property.name}           | ${property.optionality} | ${property.type!EMPTY_PLACEHOLDER}   | ${property.description!EMPTY_PLACEHOLDER} |
            </#list>
            <#if sc.properties.responseSchema[responseCode][contentType].properties?size = 0>
        |                            |                         |                                      |                                           |
            </#if>

            <#list sc.properties.responseSchema[responseCode][contentType].definitions?keys as definitionName>
        -   **`${definitionName}`**

            | Parameter                | Mandatory/Optional        | Type                                 | Description                               |
            |--------------------------|:--------------------------|--------------------------------------|-------------------------------------------|
                <#list sc.properties.responseSchema[responseCode][contentType].definitions[definitionName] as property>
            | ${property.name}         | ${property.optionality}   | ${property.type!EMPTY_PLACEHOLDER}   | ${property.description!EMPTY_PLACEHOLDER} |
                </#list>
                <#if sc.properties.responseSchema[responseCode][contentType].definitions?size = 0>
            |                          |                           |                                      |                                           |
                </#if>

            </#list>

        Response Sample:

        ```
        ${EMPTY_PLACEHOLDER}
        ```
        </#list>
    </#list>

</#list>

<#-- ===================== Mappers ===================== -->

<#list chain.elements.mappers as mapper>
## ${mapper.name}

<#-- Used for table substitution on UI side -->
[//]:#(mapper-table-export-view-${mapper.id})
</#list>

# Error Handling

| Element         | Element Type       |  Response Code                 | Handling Type                |
|-----------------|:-------------------|--------------------------------|------------------------------|
<#list chain.elements.withErrorHandling as element>
    <#list element.properties.errorHandling as errorHandling>
| ${element.name} | ${element.typeName} | ${errorHandling.responseCode} | ${errorHandling.type!"None"} |
    </#list>
</#list>
<#if chain.elements.withErrorHandling?size = 0>
|                 |                     |                               |                              |
</#if>

# Non-Functional requirements

Gathering of functional requirements is supposed to be covered separately within other project's activities.

# Security

## Authentication

Authentication mechanism must be covered in specialized interface agreement.

## Authorization

| Service Call Name      | Authorization Type                                 |
|------------------------|----------------------------------------------------|
<#list chain.elements.withAuthorization as element>
| ${element.name}        | ${element.properties.authorization.type!"Inherit"} |
</#list>
<#if chain.elements.withAuthorization?size = 0>
|                        |                                                    |
</#if>

### Security Matrix

| Trigger Name    | Access Control Type                      | RBAC Roles                                                                                                   | ABAC Resource                                    |
|-----------------|------------------------------------------|--------------------------------------------------------------------------------------------------------------|--------------------------------------------------|
<#list chain.elements.httpTriggers as trigger>
| ${trigger.name} | ${trigger.properties.accessControl.type} | <#if trigger.properties.accessControl.type == "ABAC">-<#else>${trigger.properties.accessControl.roles}</#if> | ${trigger.properties.accessControl.resource!"-"} |
</#list>
<#if chain.elements.httpTriggers?size = 0>
|                 |                                          |                                                                                                              |                                                  |
</#if>

## Security Assumptions

Security topic is covered by a separate agreement.

### Sensitive Data Masking

This section contains a list of fields, values for which are going to be masked during chain processing, as well as in all kinds of logs.

| Masked Field  |
|:--------------|
<#list chain.masking.fields as field>
| ${field.name} |
</#list>
<#if chain.masking.fields?size = 0>
|               |
</#if>

## Encryption

Encryption must be defined in separate document.

# Non-Functional requirements

| # | Metrics | Value | Description |
|---|:--------|-------|-------------|
|   |         |       |             |

# Questions

| # | Question | Assigned | Status | Answer | Answer Source |
|---|:---------|----------|--------|--------|---------------|
|   |          |          |        |        |               |
