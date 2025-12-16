import { UiSchema } from "@rjsf/utils";

export const INITIAL_UI_SCHEMA: UiSchema = {
  "ui:submitButtonOptions": {
    norender: true,
  },
  "ui:order": ["*", "name", "description"],
  properties: {
    "ui:order": [
      "contextPath",
      "httpBinding",
      "rejectRequestIfNonNullBodyGetDelete",
      "enableIdempotency",
      "idempotencyKeyExpression",
      "idempotencyKeyExpiry",
      "integrationSystemId",
      "integrationSpecificationId",
      "integrationOperationId",
      "contextServiceId",
      "contextId",
      "*",
    ],
    contextPath: {
      "ui:widget": "uri",
      "ui:placeholder": "e.g. /api/v1/resource",
    },
    handlerContainer: {
      exportFileExtension: {
        "ui:widget": "hidden",
      },
      propertiesToExportInSeparateFile: {
        "ui:widget": "hidden",
      },
      propertiesFilename: {
        "ui:widget": "hidden",
      },
      script: {
        "ui:field": "scriptField",
      },
      mappingDescription: {
        "ui:field": "mappingField",
      },
    },
    invalidURI: {
      "ui:widget": "hidden",
    },
    httpBinding: {
      "ui:widget": "hidden",
    },
    externalRoute: {
      "ui:widget": "hidden",
    },
    checkpointElementId: {
      "ui:widget": "hidden",
    },
    chainFailureHandlerContainer: {
      exportFileExtension: {
        "ui:widget": "hidden",
      },
      propertiesToExportInSeparateFile: {
        "ui:widget": "hidden",
      },
      propertiesFilename: {
        "ui:widget": "hidden",
      },
      script: {
        "ui:field": "scriptField",
      },
      mappingDescription: {
        "ui:field": "mappingField",
      },
    },
    exportFileExtension: {
      "ui:widget": "hidden",
    },
    propertiesToExportInSeparateFile: {
      "ui:widget": "hidden",
    },
    propertiesFilename: {
      "ui:widget": "hidden",
    },
    errorThrowing: {
      "ui:widget": "hidden",
    },
    authorizationConfiguration: {
      type: {
        "ui:widget": "hidden",
      },
    },
    before: {
      "ui:fieldReplacesAnyOrOneOf": true,
      type: {
        "ui:widget": "hidden",
      },
      exportFileExtension: {
        "ui:widget": "hidden",
      },
      propertiesToExportInSeparateFile: {
        "ui:widget": "hidden",
      },
      propertiesFilename: {
        "ui:widget": "hidden",
      },
      script: {
        "ui:field": "scriptField",
      },
      mappingDescription: {
        "ui:field": "mappingField",
      },
    },
    after: {
      "ui:field": "customArrayField",
    },
    afterValidation: {
      "ui:field": "customArrayField",
    },
    validationSchema: {
      "ui:field": "jsonField",
    },
    roles: {
      "ui:widget": "multipleSelectWidget",
    },
    script: {
      "ui:field": "scriptField",
    },
    mappingDescription: {
      "ui:field": "mappingField",
    },
    allowedContentTypes: {
      "ui:widget": "multipleSelectWidget",
    },
    httpMethodRestrict: {
      "ui:widget": "stringAsMultipleSelectWidget",
    },
    replyTo: {
      "ui:widget": "multipleSelectWidget",
    },
    cc: {
      "ui:widget": "multipleSelectWidget",
    },
    bcc: {
      "ui:widget": "multipleSelectWidget",
    },
    to: {
      "ui:widget": "multipleSelectWidget",
    },
    headerModificationToRemove: {
      "ui:widget": "multipleSelectWidget",
    },
    synchronousPullRetryableCodes: {
      "ui:widget": "multipleSelectWidget",
    },
    overrideContextParams: {
      "ui:field": "patternPropertiesField",
    },
    headerModificationToAdd: {
      "ui:field": "patternPropertiesField",
    },
    businessIdentifiers: {
      "ui:field": "patternPropertiesField",
    },
    requestFilterHeaderAllowlist: {
      "ui:field": "patternPropertiesField",
    },
    integrationOperationAsyncProperties: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "enhancedPatternPropertiesField",
      "ui:title": "Operation Parameters",
    },
    integrationOperationPathParameters: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "enhancedPatternPropertiesField",
      "ui:title": "Path Parameters",
    },
    integrationOperationQueryParameters: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "enhancedPatternPropertiesField",
      "ui:title": "Query Parameters",
    },
    integrationAdditionalParameters: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "enhancedPatternPropertiesField",
      "ui:title": "Additional Parameters",
    },
    bodyMimeType: {
      "ui:field": "bodyMimeTypeField",
    },
    bodyFormData: {
      "ui:widget": "hidden",
    },
    integrationGqlQuery: {
      "ui:widget": "debouncedTextareaWidget",
      "ui:options": {
        rows: 10,
      },
    },
    integrationGqlOperationName: {
      "ui:title": "Operation Name",
      "ui:placeholder": "Optional if query contains single operation",
    },
    integrationGqlVariablesJSON: {
      "ui:widget": "debouncedTextareaWidget",
      "ui:options": {
        rows: 6,
      },
    },
    integrationGqlQueryHeader: {
      "ui:title": "Query Header",
    },
    integrationGqlVariablesHeader: {
      "ui:title": "Variables Header",
    },
    idempotency: {
      keyExpiry: {
        "ui:fieldReplacesAnyOrOneOf": true,
        "ui:field": "oneOfAsSingleInputField",
      },
    },
    connectTimeout: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    reconnectDelay: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    reconnectBackoffMaxMs: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    consumersCount: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    maxPollIntervalMs: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    maxPollRecords: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    timeout: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    connectionTimeout: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    maxMessagesPerPoll: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    keySerializer: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "anyOfAsSingleSelectField",
    },
    valueSerializer: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "anyOfAsSingleSelectField",
    },
    keyDeserializer: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "anyOfAsSingleSelectField",
    },
    valueDeserializer: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "anyOfAsSingleSelectField",
    },
    retryCount: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    retryDelay: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    maxLoopIteration: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    contextServiceId: {
      "ui:title": "Context Storage",
      "ui:field": "contextServiceField",
    },
    integrationSystemId: {
      "ui:title": "Service",
      "ui:field": "serviceField",
    },
    integrationSpecificationId: {
      "ui:title": "Specification",
      "ui:field": "specificationField",
    },
    integrationOperationId: {
      "ui:title": "Operation",
      "ui:field": "systemOperationField",
    },
    systemType: {
      "ui:widget": "hidden",
    },
    integrationOperationProtocolType: {
      "ui:widget": "hidden",
    },
    integrationOperationPath: {
      "ui:widget": "hidden",
    },
    integrationOperationMethod: {
      "ui:widget": "hidden",
    },
    integrationSpecificationGroupId: {
      "ui:widget": "hidden",
    },
    elementId: {
      "ui:field": "singleSelectField",
    },
    reuseElementId: {
      "ui:field": "singleSelectField",
    },
    abacParameters: {
      resourceMap: {
        "ui:fieldReplacesAnyOrOneOf": true,
        "ui:field": "enhancedPatternPropertiesField",
        "ui:title": "Resource Map",
      },
    },
  },
  id: { "ui:widget": "hidden" },
  elementType: { "ui:widget": "hidden" },
  type: { "ui:widget": "hidden" },
  folder: { "ui:widget": "hidden" },
  propertiesFilename: { "ui:widget": "hidden" },
  swimlaneId: { "ui:widget": "hidden" },
  children: { "ui:widget": "hidden" },
};

export const pathToTabMap: Record<string, string> = {
  "properties.contextServiceId": "Operation",
  "properties.useCorrelationId": "Operation",
  "properties.contextId": "Operation",
  "properties.operation": "Operation",
  "properties.ttl": "Operation",
  "properties.key": "Operation",
  "properties.value": "Operation",
  "properties.keys": "Operation",
  "properties.target": "Operation",
  "properties.targetName": "Operation",
  "properties.unwrap": "Operation",
  "properties.contextPath": "Endpoint",
  "properties.integrationOperationId": "Endpoint",
  "properties.integrationSpecificationGroupId": "Endpoint",
  "properties.integrationSpecificationId": "Endpoint",
  "properties.integrationSystemId": "Endpoint",
  "properties.integrationOperationMethod": "Endpoint",
  "properties.integrationOperationPath": "Endpoint",
  "properties.httpMethodRestrict": "Endpoint",
  "properties.systemType": "Endpoint",
  "properties.integrationOperationProtocolType": "Endpoint",
  "properties.integrationOperationAsyncProperties": "Endpoint",
  "properties.integrationOperationPathParameters": "Endpoint",
  "properties.integrationOperationQueryParameters": "Endpoint",
  "properties.integrationAdditionalParameters": "Endpoint",
  "properties.bodyMimeType": "Endpoint",
  "properties.bodyFormData": "Endpoint",
  "properties.integrationGqlQuery": "Endpoint",
  "properties.integrationGqlOperationName": "Endpoint",
  "properties.integrationGqlVariablesJSON": "Endpoint",
  "properties.integrationGqlQueryHeader": "Endpoint",
  "properties.integrationGqlVariablesHeader": "Endpoint",
  "properties.handleValidationAction": "Handle Validation Failure",
  "properties.handlerContainer": "Handle Validation Failure",
  "properties.handlerContainer.script": "Handle Validation Failure",
  "properties.handlerContainer.mappingDescription": "Handle Validation Failure",
  "properties.handleChainFailureAction": "Failure Response Mapping",
  "properties.chainFailureHandlerContainer": "Failure Response Mapping",
  "properties.chainFailureHandlerContainer.elementId":
    "Failure Response Mapping",
  "properties.chainFailureHandlerContainer.script": "Failure Response Mapping",
  "properties.chainFailureHandlerContainer.mappingDescription":
    "Failure Response Mapping",
  "properties.validationSchema": "Validate Request",
  "properties.roles": "Access Control",
  "properties.accessControlType": "Access Control",
  "properties.abacParameters": "Access Control",
  "properties.abacParameters.resourceType": "Access Control",
  "properties.abacParameters.operation": "Access Control",
  "properties.abacParameters.resourceDataType": "Access Control",
  "properties.abacParameters.resourceString": "Access Control",
  "properties.abacParameters.resourceMap": "Access Control",
  "properties.allowedContentTypes": "Validate Request",
  "properties.rejectRequestIfNonNullBodyGetDelete": "Validate Request",
  "properties.authorizationConfiguration": "Authorization",
  "properties.idempotency": "Idempotency",
  "properties.idempotency.enabled": "Idempotency",
  "properties.idempotency.keyExpiry": "Idempotency",
  "properties.idempotency.contextExpression": "Idempotency",
  "properties.idempotency.keyExpression": "Idempotency",
  "properties.idempotency.actionOnDuplicate": "Idempotency",
  "properties.idempotency.chainTriggerParameters": "Idempotency",
  "properties.idempotency.chainTriggerParameters.triggerElementId":
    "Idempotency",
  "properties.idempotency.chainTriggerParameters.chainCallTimeout":
    "Idempotency",
  "properties.before": "Prepare Request",
  "properties.after": "Handle Response",
  "properties.afterValidation": "Validation",
  "properties.requestFilterHeaderAllowlist": "Filer Request",
};

export const desiredTabOrder = [
  "Operation",
  "Endpoint",
  "Prepare Request",
  "Authorization",
  "Validation",
  "Validate Request",
  "Handle Validation Failure",
  "Handle Response",
  "Failure Response Mapping",
  "Access Control",
  "Filer Request",
  "Parameters",
  "Idempotency",
];
