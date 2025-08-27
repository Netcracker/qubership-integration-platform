import { UiSchema } from "@rjsf/utils";
import styles from "./ChainElementModification.module.css";

export const INITIAL_UI_SCHEMA: UiSchema = {
  "ui:submitButtonOptions": {
    norender: true,
  },
  "ui:order": ["*", "name", "description"],
  properties: {
    "ui:fieldReplacesAnyOrOneOf": true,
    "ui:field": "hidden",
    "ui:order": [
      "contextPath",
      "httpBinding",
      "rejectRequestIfNonNullBodyGetDelete",
      "enableIdempotency",
      "idempotencyKeyExpression",
      "idempotencyKeyExpiry",
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
        "ui:widget": "textarea",
        "ui:classNames": styles["custom-textarea"],
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
        "ui:widget": "textarea",
        "ui:classNames": styles["custom-textarea"],
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
    validationSchema: {
      "ui:widget": "textarea",
      "ui:classNames": styles["custom-textarea"],
    },
    roles: {
      "ui:widget": "customSelectWidget",
    },
    script: {
      "ui:widget": "textarea",
      "ui:classNames": styles["custom-textarea"],
    },
    allowedContentTypes: {
      "ui:widget": "customSelectWidget",
    },
    httpMethodRestrict: {
      "ui:widget": "stringAsMultipleSelectWidget",
    },
    abacResource: {
      "ui:tab": "Access Control",
    },
    replyTo: {
      "ui:widget": "customSelectWidget",
    },
    cc: {
      "ui:widget": "customSelectWidget",
    },
    bcc: {
      "ui:widget": "customSelectWidget",
    },
    to: {
      "ui:widget": "customSelectWidget",
    },
    headerModificationToRemove: {
      "ui:widget": "customSelectWidget",
    },
    synchronousPullRetryableCodes: {
      "ui:widget": "customSelectWidget",
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
  },
  id: { "ui:widget": "hidden" },
  elementType: { "ui:widget": "hidden" },
  type: { "ui:widget": "hidden" },
  folder: { "ui:widget": "hidden" },
  propertiesFilename: { "ui:widget": "hidden" },
  swimlaneId: { "ui:widget": "hidden" },
};

export const pathToTabMap: Record<string, string> = {
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
  "properties.handleValidationAction": "Handle Validation Failure",
  "properties.handlerContainer": "Handle Validation Failure",
  "properties.handlerContainer.script": "Handle Validation Failure",
  "properties.handleChainFailureAction": "Failure Response Mapping",
  "properties.chainFailureHandlerContainer": "Failure Response Mapping",
  "properties.chainFailureHandlerContainer.elementId":
    "Failure Response Mapping",
  "properties.chainFailureHandlerContainer.script": "Failure Response Mapping",
  "properties.validationSchema": "Validate Request",
  "properties.roles": "Access Control",
  "properties.abacResource": "Access Control",
  "properties.accessControlType": "Access Control",
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
};

export const desiredTabOrder = [
  "Endpoint",
  "Authorization",
  "Validate Request",
  "Handle Validation Failure",
  "Failure Response Mapping",
  "Access Control",
  "Parameters",
  "Idempotency",
];
