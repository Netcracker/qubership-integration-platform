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

package org.qubership.integration.platform.runtime.catalog.consul;

import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;

import java.util.List;

public class ConfigurationPropertiesConstants {

    public static final String CHAIN_ID = "chainId";
    public static final String ACTUAL_ELEMENT_CHAIN_ID = "actualElementChainId";
    public static final String ACTUAL_CHAIN_OVERRIDE_STEP_NAME_FIELD = "actualElementChainIdOverrideForStep";
    public static final String CHAIN_NAME = "chainName";
    public static final String ELEMENT_NAME = "elementName";
    public static final String ELEMENT_TYPE = "elementType";
    public static final String ELEMENT_ID = "elementId";
    public static final String PARENT_ELEMENT_ID = "parentElementId";
    public static final String PARENT_ELEMENT_ORIGINAL_ID = "parentElementOriginalId";
    public static final String PARENT_ELEMENT_NAME = "parentElementName";
    public static final String HAS_INTERMEDIATE_PARENTS = "hasIntermediateParents";
    public static final String REUSE_ORIGINAL_ID = "reuseOriginalId";
    public static final String EXTERNAL_SERVICE_NAME = "externalServiceName";
    public static final String EXTERNAL_SERVICE_ENV_NAME = "externalServiceEnvName";

    public static final String EXTERNAL_ROUTE = "externalRoute";
    public static final String WIRE_TAP_ID = "wireTapId";
    public static final String SNAPSHOT_NAME = "snapshotName";
    public static final String CONTAINS_CHECKPOINT_ELEMENTS = "containsCheckpointElements";

    public static final String ASYNC_SPLIT_ELEMENT = "async-split-element";
    public static final String ASYNC_SPLIT_ELEMENT_2 = "async-split-element-2";

    public static final String JMS_SENDER_ELEMENT = "jms-sender";
    public static final String JMS_TRIGGER_ELEMENT = "jms-trigger";
    public static final String GRPC_SENDER_ELEMENT = "grpc-sender";
    public static final String CHAIN_CALL_2_ELEMENT = "chain-call-2";
    public static final String HTTP_TRIGGER_ELEMENT = "http-trigger";
    public static final String SERVICE_CALL_ELEMENT = "service-call";
    public static final String CHAIN_CALL_PROPERTY_OPTION = "chain-call";
    public static final String HTTP_TRIGGER_FAILURE_HANDLER_ACTION = "handleChainFailureAction";
    public static final String HTTP_TRIGGER_FAILURE_HANDLER_CHAIN_CALL_CONTAINER = "chainFailureHandlerContainer";
    public static final String HTTP_TRIGGER_CHAIN_CALL_STEP_NAME = "Failure response mapping";

    public static final String SERVICE_CALL_RETRY_COUNT = "retryCount";
    public static final String SERVICE_CALL_RETRY_DELAY = "retryDelay";

    public static final String IDEMPOTENCY_ENABLED = "idempotencyEnabled";
    public static final String EXPIRY = "expiry";
    public static final String METHOD = "method";
    public static final String PATH = "path";

    /**
     * The list contains element types. These are container-type elements whose children must be
     * placed inside an intermediate step in the session tree. This is for steps in a template with
     * id format: {name}--{identifier}.
     */
    public static final List<String> ELEMENTS_WITH_INTERMEDIATE_CHILDREN = List.of(
            CamelNames.LOOP_COMPONENT,
            CamelNames.CIRCUIT_BREAKER_CONFIGURATION_COMPONENT,
            CamelNames.ON_FALLBACK_COMPONENT
    );

    private ConfigurationPropertiesConstants() {}
}
