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
      /* rabbitmq/kafka trigger */
      "connectionSourceType",
      "addresses",
      "username",
      "password",
      "vhostClassifierName",
      "topicsClassifierName",
      "maasClassifierNamespace",
      "maasClassifierTenantEnabled",
      "maasClassifierTenantId",
      "exchange",
      "queues",
      "routingKey",
      //SFTP Trigger
      "connectUrl",
      "scheduler.cron",

      /* kafka trigger - manual*/
      "brokers",
      "securityProtocol",
      "saslMechanism",
      "saslJaasConfig",
      "topics",
      "groupId",
      "reconnectBackoffMaxMs",
      "sslProtocol",
      "sslEndpointAlgorithm",
      "autoOffsetReset",
      "consumersCount",
      /* kafka trigger - maas*/
      "maxPollRecords",
      "maxPollIntervalMs",
      /* http trigger */
      "accessControlType",
      "roles",
      "abacParameters",
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
      elementId: {
        "ui:field": "chainTriggerElementIdField",
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
      "ui:widget": "singleColumnTableWidget",
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
      "ui:order": ["*", "actionOnDuplicate", "chainTriggerParameters"],
      keyExpiry: {
        "ui:fieldReplacesAnyOrOneOf": true,
        "ui:field": "oneOfAsSingleInputField",
      },
      chainTriggerParameters: {
        triggerElementId: {
          "ui:field": "chainTriggerElementIdField",
        },
        chainCallTimeout: {
          "ui:fieldReplacesAnyOrOneOf": true,
          "ui:field": "oneOfAsSingleInputField",
        },
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
    },
    valueSerializer: {
      "ui:fieldReplacesAnyOrOneOf": true,
    },
    keyDeserializer: {
      "ui:fieldReplacesAnyOrOneOf": true,
    },
    valueDeserializer: {
      "ui:fieldReplacesAnyOrOneOf": true,
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
      "ui:title": "API Specification",
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
    integrationOperationSkipEmptyQueryParameters: {
      "ui:widget": "hidden",
    },
    elementId: {
      "ui:field": "chainTriggerElementIdField",
    },
    reuseElementId: {
      "ui:field": "singleSelectField",
    },
    abacParameters: {
      "ui:order": [
        "*",
        "resourceType",
        "operation",
        "resourceDataType",
        "resourceString",
      ],
      resourceMap: {
        "ui:fieldReplacesAnyOrOneOf": true,
        "ui:field": "enhancedPatternPropertiesField",
        "ui:title": "Resource Map",
      },
    },
    queryHeader: {
      "ui:widget": "hidden",
    },
    variablesHeader: {
      "ui:widget": "hidden",
    },
    query: {
      "ui:widget": "debouncedTextareaWidget",
      "ui:options": {
        rows: 10,
      },
    },
    variablesJSON: {
      "ui:widget": "debouncedTextareaWidget",
      "ui:options": {
        rows: 6,
      },
    },
    exchangePattern: {
      "ui:widget": "hidden",
    },

    //Circuit Breaker
    failureRateThreshold: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    minimumNumberOfCalls: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    permittedNumberOfCallsInHalfOpenState: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    slidingWindowSize: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    slowCallDurationThreshold: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    slowCallRateThreshold: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },
    waitDurationInOpenState: {
      "ui:fieldReplacesAnyOrOneOf": true,
      "ui:field": "oneOfAsSingleInputField",
    },

    //Split
    aggregationStrategy: {
      "ui:widget": "hidden",
    },

    //SFTP Upload and SFTP Download
    allowNullBody: {
      "ui:widget": "hidden",
    },
    binary: {
      "ui:widget": "hidden",
    },
    throwExceptionOnConnectFailed: {
      "ui:widget": "hidden",
    },
    useUserKnownHostsFile: {
      "ui:widget": "hidden",
    },
    streamDownload: {
      "ui:widget": "hidden",
    },
    autoCreate: {
      "ui:widget": "hidden",
    },
    sendEmptyMessageWhenIdle: {
      "ui:widget": "hidden",
    },

    //Scheduler
    deleteJob: {
      "ui:widget": "hidden",
    },

    //SFTP Trigger
    ignoreFileNotFoundOrPermissionError: {
      "ui:widget": "hidden",
    },
    jschLoggingLevel: {
      "ui:widget": "hidden",
    },
    readLockLoggingLevel: {
      "ui:widget": "hidden",
    },
    runLoggingLevel: {
      "ui:widget": "hidden",
    },
    scheduler: {
      "ui:widget": "hidden",
    },
    "scheduler.deleteJob": {
      "ui:widget": "hidden",
    },
    "scheduler.triggerGroup": {
      "ui:widget": "hidden",
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

type Path2TabMapping = {
  paths: string[];
  mapping: Record<string, string>;
};

const pathToTabExceptions: Path2TabMapping[] = [
  {
    paths: ["properties.after"],
    mapping: {
      "async-api-trigger": "Validate Request",
    },
  },
  {
    paths: ["properties.key"],
    mapping: {
      "kafka-sender-2": "Parameters",
    },
  },
  {
    paths: ["properties.contextPath", "properties.httpMethodRestrict"],
    mapping: {
      checkpoint: "Parameters",
    },
  },
];

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
  "properties.afterValidation": "Validations",
  "properties.requestFilterHeaderAllowlist": "Filter Request",
  "properties.logLevel": "Logging",
  "properties.sender": "Logging",
  "properties.receiver": "Logging",
  "properties.businessIdentifiers": "Logging",
  "properties.message": "Logging",
  "properties.script": "Script",
  "properties.mappingDescription": "Mapping",
  "properties.headerModificationToAdd": "Header Modification",
  "properties.headerModificationToRemove": "Header Modification",
};

export const desiredTabOrder = [
  "Operation",
  "Endpoint",
  "Prepare Request",
  "Authorization",
  "Validations",
  "Validate Request",
  "Handle Validation Failure",
  "Handle Response",
  "Failure Response Mapping",
  "Filter Request",
  "Logging",
  "Script",
  "Mapping",
  "Header Modification",
  "Parameters",
  "Idempotency",
];

export function getTabForPath(
  path: string,
  elementType?: string,
): string | undefined {
  if (elementType) {
    for (const mapping of pathToTabExceptions) {
      if (mapping.paths.includes(path)) {
        const customTab: string = mapping.mapping[elementType];
        if (customTab) {
          return customTab;
        }
      }
    }
  }

  return pathToTabMap[path];
}
