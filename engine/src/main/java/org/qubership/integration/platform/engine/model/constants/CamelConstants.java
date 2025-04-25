/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.model.constants;

import org.apache.camel.Exchange;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class CamelConstants {

    private static final Set<String> INTERNAL_PROPERTIES_NAMES;
    private static final Set<String> INTERNAL_HEADERS_NAMES;

    public static final String INTERNAL_PROPERTY_PREFIX = "internalProperty_";
    public static final String SYSTEM_PROPERTY_PREFIX = "systemProperty_";

    public static final String LOG_TYPE_KEY = "logType";
    public static final String LOG_TYPE_VALUE = "int";
    public static final String MASKING_TEMPLATE = "******";
    public static final String UUID_REGEXP_STRING = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public static final Pattern UUID_STEP_REG_EXP_PATTERN = Pattern.compile("--" + UUID_REGEXP_STRING + "$", Pattern.CASE_INSENSITIVE);
    public static final String STEP_NAME_ID_PATTERN = "^[\\s\\S]{0,}--";
    public static final Pattern NAME_STEP_REG_EXP_PATTERN = Pattern.compile(STEP_NAME_ID_PATTERN, Pattern.CASE_INSENSITIVE);
    public static final Pattern CUSTOM_STEP_ID_PATTERN = Pattern.compile(STEP_NAME_ID_PATTERN + UUID_REGEXP_STRING + "$", Pattern.CASE_INSENSITIVE);

    // exchange headers
    public static final class Headers {
        public static final String CAMEL_HTTP_RESPONSE_CODE = "CamelHttpResponseCode";
        public static final String URI_TEMPLATE = "CamelServletContextPath";
        public static final String TRACE_ME = "TraceMe"; // SET_FULL_SESSION_LOGGING_LEVEL_HTTP
        public static final String ORIGINATING_BUSINESS_ID = "originating-bi-id";
        public static final String EXTERNAL_SESSION_CIP_ID = "external-session-cip-id";
        public static final String GQL_VARIABLES_HEADER = "CamelGraphQLVariables";
        public static final String GQL_QUERY_HEADER = "CamelGraphQLQuery";
        public static final String SCHEDULER = "scheduler";
        public static final String JOB_DETAIL = "jobDetail";
        public static final String TRIGGER = "trigger";
        public static final String JOB_INSTANCE = "jobInstance";
        public static final String HTTP_URI = "CamelHttpUri";

        private Headers() {
        }
    }

    // exchange properties
    public static final class Properties {
        public static final String ELEMENT_EXECUTION_MAP = INTERNAL_PROPERTY_PREFIX + "elementExecutionMap";
        public static final String SESSION_ID = INTERNAL_PROPERTY_PREFIX + "sessionId";
        public static final String SESSION_SHOULD_BE_LOGGED = INTERNAL_PROPERTY_PREFIX + "sessionShouldBeLogged";
        public static final String STEPS = INTERNAL_PROPERTY_PREFIX + "steps";
        public static final String EXCHANGES = INTERNAL_PROPERTY_PREFIX + "exchanges";
        public static final String START_TIME = INTERNAL_PROPERTY_PREFIX + "startTime";
        public static final String START_TIME_MS = INTERNAL_PROPERTY_PREFIX + "startTimeMs";
        public static final String EXCHANGE_START_TIME_MS = INTERNAL_PROPERTY_PREFIX + "exchangeStartTimeMs";
        public static final String IS_MAIN_EXCHANGE = INTERNAL_PROPERTY_PREFIX + "isMainExchange";
        public static final String ELEMENT_FAILED = INTERNAL_PROPERTY_PREFIX + "elementFailed";
        public static final String LAST_EXCEPTION = INTERNAL_PROPERTY_PREFIX + "lastException";
        public static final String LAST_EXCEPTION_ERROR_CODE = INTERNAL_PROPERTY_PREFIX + "laseExceptionErrorCode";
        public static final String TRACE_ME = "TraceMe";  // SET_FULL_SESSION_LOGGING_LEVEL_HTTP
        public static final String REQUEST_CONTEXT_PROPAGATION_SNAPSHOT = INTERNAL_PROPERTY_PREFIX + "requestContextPropagationSnapshot";
        public static final String TRACING_CUSTOM_TAGS = INTERNAL_PROPERTY_PREFIX + "tracingCustomTags";
        public static final String VARIABLES_PROPERTY_MAP_NAME = "variables";
        public static final String CIRCUIT_BREAKER_HAS_FALLBACK =
                INTERNAL_PROPERTY_PREFIX + "circuitBreaker_hasFallback";
        public static final String ELEMENT_WARNING = INTERNAL_PROPERTY_PREFIX + "element_warning";
        public static final String OVERALL_STATUS_WARNING = INTERNAL_PROPERTY_PREFIX + "overall_status_warning";

        public static final String CHECKPOINT_ORIGINAL_SESSION_ID = "originalSessionId";
        public static final String CHECKPOINT_PARENT_SESSION_ID = "parentSessionId";
        public static final String CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID =
                INTERNAL_PROPERTY_PREFIX + CHECKPOINT_ORIGINAL_SESSION_ID;
        public static final String CHECKPOINT_INTERNAL_PARENT_SESSION_ID =
                INTERNAL_PROPERTY_PREFIX + CHECKPOINT_PARENT_SESSION_ID;

        public static final String CHECKPOINT_ELEMENT_ID =
                INTERNAL_PROPERTY_PREFIX + "checkpointElementId";
        public static final String CHECKPOINT_IS_TRIGGER_STEP =
                INTERNAL_PROPERTY_PREFIX + "isCheckpointTriggerStep";

        public static final String CURRENT_REUSE_REFERENCE_PARENT_ID = INTERNAL_PROPERTY_PREFIX + "%s_currentReuseReferenceParentId";
        public static final String REUSE_HAS_INTERMEDIATE_PARENTS = INTERNAL_PROPERTY_PREFIX + "%s_" + ChainProperties.HAS_INTERMEDIATE_PARENTS;

        public static final String SPLIT_ID = INTERNAL_PROPERTY_PREFIX + "splitId";
        public static final String SPLIT_ID_CHAIN = INTERNAL_PROPERTY_PREFIX + "splitIdChain";
        public static final String SPLIT_BRANCH_TYPE = INTERNAL_PROPERTY_PREFIX + "splitBranchType";
        public static final String SPLIT_EXCHANGE_HEADER_PROCESSED = INTERNAL_PROPERTY_PREFIX + "splitExchangeHeaderProcessed";
        public static final String SPLIT_EXCHANGE_PROPERTIES_PROCESSED = INTERNAL_PROPERTY_PREFIX + "exchangePropertiesProcessed";
        public static final String SPLIT_PROPAGATE_HEADERS = INTERNAL_PROPERTY_PREFIX + "propagateHeaders";
        public static final String SPLIT_PROPAGATE_PROPERTIES = INTERNAL_PROPERTY_PREFIX + "propagateProperties";
        public static final String SPLIT_PROCESSED = INTERNAL_PROPERTY_PREFIX + "splitProcessed";

        public static final String CLASSIFIER = INTERNAL_PROPERTY_PREFIX + "classifier";
        public static final String SCRIPT = INTERNAL_PROPERTY_PREFIX + "script";
        public static final String DATA_RETURNING = INTERNAL_PROPERTY_PREFIX + "dataReturningQuery";
        public static final String BODY_MIME_TYPE = INTERNAL_PROPERTY_PREFIX + "bodyMimeType";
        public static final String BODY_FORM_DATA = INTERNAL_PROPERTY_PREFIX + "bodyFormData";
        public static final String GQL_VARIABLES_JSON = INTERNAL_PROPERTY_PREFIX + "graphQLVariablesJSON";
        public static final String VALIDATION_SCHEMA = INTERNAL_PROPERTY_PREFIX + "validationSchema";
        public static final String EXPECTED_CONTENT_TYPE = INTERNAL_PROPERTY_PREFIX + "expectedContentType";
        public static final String MATCHED_CONTENT_TYPES = INTERNAL_PROPERTY_PREFIX + "contentTypeMatched";
        public static final String ASYNC_VALIDATION_SCHEMA = INTERNAL_PROPERTY_PREFIX + "asyncValidationSchema";
        public static final String GRPC_SERVICE_NAME = INTERNAL_PROPERTY_PREFIX + "grpcServiceName";
        public static final String GRPC_METHOD_NAME = INTERNAL_PROPERTY_PREFIX + "grpcMethodName";

        public static final String MAPPING_THROW_EXCEPTION = INTERNAL_PROPERTY_PREFIX + "mappingThrowException";
        public static final String MAPPING_CONFIG = INTERNAL_PROPERTY_PREFIX + "mappingConfig";
        public static final String MAPPING_ID = INTERNAL_PROPERTY_PREFIX + "mappingId";
        public static final String RBAC_ACCESS_POLICY = INTERNAL_PROPERTY_PREFIX + "rbac_access_policy";
        public static final String HEADER_MODIFICATION_TO_ADD = INTERNAL_PROPERTY_PREFIX + "headerModificationToAdd";
        public static final String HEADER_MODIFICATION_TO_REMOVE = INTERNAL_PROPERTY_PREFIX + "headerModificationToRemove";
        public static final String SESSION_FAILED = INTERNAL_PROPERTY_PREFIX + "sessionFailed";
        public static final String SERVLET_REQUEST_URL = INTERNAL_PROPERTY_PREFIX + "servletRequestUrl";
        public static final String CONTEXT_INIT_MARKERS = INTERNAL_PROPERTY_PREFIX + "contextInitMarkers";
        public static final String OVERRIDE_CONTEXT_PARAMS = INTERNAL_PROPERTY_PREFIX + "overrideContextParams";
        public static final String ENABLE_AUTH_RESTORE_PROP = INTERNAL_PROPERTY_PREFIX + "enableAuthRestore";
        public static final String DEPLOYMENT_RUNTIME_PROPERTIES_MAP_PROP = INTERNAL_PROPERTY_PREFIX + "deploymentRuntimePropertiesMap";
        public static final String ABAC_RESOURCE_PROP = INTERNAL_PROPERTY_PREFIX + "abacResource";
        public static final String ACKNOWLEDGE_MODE_PROP = INTERNAL_PROPERTY_PREFIX + "acknowledgeMode";
        public static final String PRESERVED_AUTH_PROP = INTERNAL_PROPERTY_PREFIX + "preservedAuth";
        public static final String INTERRUPT_EXCHANGE_HTTP_CODE_PROP = INTERNAL_PROPERTY_PREFIX + "interruptExchangeHttpCode";
        public static final String REJECT_REQUEST_IF_NULL_BODY_GET_DELETE_PROP = INTERNAL_PROPERTY_PREFIX + "rejectRequestIfNonNullBodyGetDelete";
        public static final String ALLOWED_CONTENT_TYPES_PROP = INTERNAL_PROPERTY_PREFIX + "allowedContentTypes";
        public static final String SDS_EXECUTION_ID_PROP = INTERNAL_PROPERTY_PREFIX + "sdsExecutionId";

        public static final String SESSION_ACTIVE_THREAD_COUNTER = INTERNAL_PROPERTY_PREFIX + "sessionActiveThreadCounter";
        public static final String THREAD_SESSION_STATUSES = INTERNAL_PROPERTY_PREFIX + "threadSessionStatuses";
        public static final String RESPONSE_FILTER = INTERNAL_PROPERTY_PREFIX + "responseFilter";
        public static final String RESPONSE_FILTER_EXCLUDE_FIELDS = INTERNAL_PROPERTY_PREFIX + "responseFilterExclude";
        public static final String RESPONSE_FILTER_INCLUDE_FIELDS = INTERNAL_PROPERTY_PREFIX + "responseFilterInclude";
        public static final String HTTP_TRIGGER_CHAIN_FAILED = INTERNAL_PROPERTY_PREFIX + "httpThreadFailed";
        public static final String HTTP_TRIGGER_EXTERNAL_ERROR_CODE = INTERNAL_PROPERTY_PREFIX + "httpTriggerErrorCode";
        public static final String CORRELATION_ID_POSITION = "correlationIdPosition";
        public static final String CORRELATION_ID_NAME =  "correlationIdName";
        public static final String IS_CHECKPOINT_TRIGGER_STEP =  INTERNAL_PROPERTY_PREFIX + "isCheckpointTriggerStep";
        public static final String CHAIN_TIMED_OUT = INTERNAL_PROPERTY_PREFIX + "chainSessionTimedOut";
        public static final String CHAIN_TIME_OUT_AFTER = INTERNAL_PROPERTY_PREFIX + "chainSessionTimeoutAfter";

        public static final String HTTP_TRIGGER_STEP_ID =  "httpTriggerStepId";

        public static final String SERVICE_CALL_RETRY_COUNT = "retryCount";
        public static final String SERVICE_CALL_RETRY_DELAY = "retryDelay";

        public static final int SERVICE_CALL_DEFAULT_RETRY_DELAY = 5000;

        public static final String SYSTEM_PROPERTY_BLUEGREEN_STATE = SYSTEM_PROPERTY_PREFIX + "bluegreenState";

        private Properties() {
        }
    }

    public static final class ChainProperties {
        public static final String CHAIN_ID = "chainId";
        public static final String CHAIN_NAME = "chainName";
        public static final String SNAPSHOT_NAME = "snapshotName";
        public static final String ELEMENT_NAME = "elementName";
        public static final String ELEMENT_TYPE = "elementType";
        public static final String ELEMENT_ID = "elementId";
        public static final String PARENT_ELEMENT_ID = "parentElementId";
        public static final String PARENT_ELEMENT_ORIGINAL_ID = "parentElementOriginalId";
        public static final String PARENT_ELEMENT_NAME = "parentElementName";
        public static final String ACTUAL_ELEMENT_CHAIN_ID = "actualElementChainId";
        public static final String ACTUAL_CHAIN_OVERRIDE_STEP_NAME_FIELD = "actualElementChainIdOverrideForStep";
        public static final String HAS_INTERMEDIATE_PARENTS = "hasIntermediateParents";
        public static final String REUSE_ORIGINAL_ID = "reuseOriginalId";

        public static final String CONTAINS_CHECKPOINT_ELEMENTS = "containsCheckpointElements";
        public static final String WIRE_TAP_ID = "wireTapId";
        public static final String EXECUTION_STATUS = "executionStatus";

        public static final String OPERATION_SPECIFICATION_ID = "integrationSpecificationId";
        public static final String OPERATION_PROTOCOL_TYPE_PROP = "integrationOperationProtocolType";
        public static final String OPERATION_PROTOCOL_TYPE_KAFKA = "kafka";
        public static final String OPERATION_PROTOCOL_TYPE_AMQP = "amqp";
        public static final String OPERATION_PROTOCOL_TYPE_HTTP = "http";
        public static final String OPERATION_PROTOCOL_TYPE_GRAPHQL = "graphql";
        public static final String OPERATION_PROTOCOL_TYPE_GRPC = "grpc";
        public static final String OPERATION_PATH_TOPIC = "integrationOperationPath";
        public static final String OPERATION_PATH_EXCHANGE = "integrationOperationPath";
        public static final String OPERATION_PATH = "integrationOperationPath";

        public static final String JMS_INITIAL_CONTEXT_FACTORY = "initialContextFactory";
        public static final String JMS_PROVIDER_URL = "providerUrl";
        public static final String JMS_CONNECTION_FACTORY_NAME = "connectionFactoryName";
        public static final String JMS_USERNAME = "username";
        public static final String JMS_PASSWORD = "password";
        public static final String JMS_DESTINATION_TYPE = "destinationType";
        public static final String JMS_DESTINATION_NAME = "destinationName";
        public static final String SDS_JOB_ID = "jobId";
        public static final String SDS_CRON_EXPRESSION = "cron";
        public static final String SDS_FEATURE_PROHIBIT_PARALLEL_RUN = "prohibitParallelRun";
        public static final String SDS_PARALLEL_RUN_TIMEOUT = "parallelRunTimeout";

        public static final String SERVICE_CALL_ELEMENT = "service-call";

        public static final String REUSE_ESTABLISHED_CONN = "reuseEstablishedConnection";

        public static final String FAILED_ELEMENT_NAME = "failed-element-name";
        public static final String FAILED_ELEMENT_ID = "failed-element-id";

        //Error handling extra params
        public static final String EXCEPTION_EXTRA_SESSION_ID = "sessionId";
        public static final String EXCEPTION_EXTRA_FAILED_ELEMENT = "failedElementId";
        public static final String EXCEPTION_EXTRA_VALIDATION_RESULT = "validationResult";

        public static final String EXTERNAL_SERVICE_NAME = "externalServiceName";
        public static final String EXTERNAL_SERVICE_NAME_PROP = "external-service-name";
        public static final String EXTERNAL_SERVICE_ENV_NAME = "externalServiceEnvName";
        public static final String EXTERNAL_SERVICE_ENV_NAME_PROP = "external-service-environment-name";

        public static final String IDEMPOTENCY_ENABLED = "idempotencyEnabled";
        public static final String EXPIRY = "expiry";
        public static final String PATH = "path";
        public static final String METHOD = "method";

        private ChainProperties() {
        }
    }

    public static boolean isInternalProperty(String key) {
        return INTERNAL_PROPERTIES_NAMES.contains(key)
                || (key != null && key.startsWith(INTERNAL_PROPERTY_PREFIX));
    }

    public static boolean isInternalHeader(String header) {
        return INTERNAL_HEADERS_NAMES.contains(header);
    }

    static {
        Set<String> exchangeProperties = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> internalProperties = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> internalHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        try {
            extractPublicStringFieldsToMap(internalProperties, Properties.class);
            extractPublicStringFieldsToMap(exchangeProperties, Exchange.class);

            // component specific exclusions
            extractPublicStringFieldsToMap(exchangeProperties, SpringRabbitMQConstants.class);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize internal properties/headers map in static block", e);
        }

        internalProperties.addAll(exchangeProperties);
        internalHeaders.addAll(exchangeProperties);

        internalProperties.remove(Properties.CHECKPOINT_ORIGINAL_SESSION_ID);
        internalProperties.remove(Properties.CHECKPOINT_PARENT_SESSION_ID);

        internalProperties.add("OpenTracing.activeSpan");

        INTERNAL_PROPERTIES_NAMES = Collections.unmodifiableSet(internalProperties);

        internalHeaders.addAll(Set.of(
            Headers.EXTERNAL_SESSION_CIP_ID,
            Headers.GQL_VARIABLES_HEADER,
            Headers.SCHEDULER,
            Headers.JOB_DETAIL,
            Headers.TRIGGER,
            Headers.JOB_INSTANCE
        ));

        INTERNAL_HEADERS_NAMES = Collections.unmodifiableSet(internalHeaders);
    }

    private static void extractPublicStringFieldsToMap(Set<String> internalProperties, Class<?> aClass)
            throws IllegalAccessException {
        for (Field field : aClass.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && field.getType().getSimpleName().equals("String")) {
                internalProperties.add((String) field.get(aClass));
            }
        }
    }

    private CamelConstants() {
    }
}
