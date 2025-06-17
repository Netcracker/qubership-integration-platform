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

package org.qubership.integration.platform.runtime.catalog.model.constant;

public final class CamelOptions {

    public static final String ID = "id";
    public static final String URI = "uri";
    public static final String NAME = "name";
    public static final String HTTP_URI = "httpUri";
    public static final String HTTP_METHOD = "httpMethod";
    public static final String VALIDATION_SCHEMA = "validationSchema";
    public static final String CONTEXT_PATH = "contextPath";
    public static final String IS_EXTERNAL_ROUTE = "externalRoute";
    public static final String IS_PRIVATE_ROUTE = "privateRoute";
    public static final String HTTP_METHOD_RESTRICT = "httpMethodRestrict";
    public static final String ALLOWED_CONTENT_TYPES = "allowedContentTypes";
    public static final String ACCESS_CONTROL_TYPE = "accessControlType";
    public static final String ABAC_RESOURCE = "abacResource";

    // kafka
    public static final String BROKERS = "brokers";
    public static final String TOPICS = "topics";
    public static final String GROUP_ID = "groupId";
    public static final String SECURITY_PROTOCOL = "securityProtocol";
    public static final String SASL_MECHANISM = "saslMechanism";
    public static final String SASL_JAAS_CONFIG = "saslJaasConfig";

    // amqp
    public static final String EXCHANGE = "exchange";
    public static final String QUEUES = "queues";
    public static final String VHOST = "vhost";
    public static final String ADDRESSES = "addresses";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String ROUTING_KEY = "routingKey";
    public static final String SSL = "sslProtocol";

    public static final String TRANSLATOR_TYPE = "translatorType";
    public static final String EXPRESSION = "expression";
    public static final String EXPRESSION_TYPE = "expressionType";
    public static final String EXCEPTIONS = "exceptions";
    public static final String MESSAGE = "message";
    public static final String HEADER_NAME = "headerName";
    public static final String SCRIPT = "script";
    public static final String CUSTOM_ID = "customId";
    public static final String BRIDGE_ENDPOINT = "bridgeEndpoint";
    public static final String PARALLEL_PROCESSING = "parallelProcessing";

    public static final String SYSTEM_TYPE = "systemType";
    public static final String SYSTEM_ID = "integrationSystemId";
    public static final String SPECIFICATION_ID = "integrationSpecificationId";
    public static final String SPECIFICATION_GROUP_ID = "integrationSpecificationGroupId";
    public static final String MODEL_ID = "integrationSpecificationId";
    public static final String OPERATION_ID = "integrationOperationId";
    public static final String OPERATION_PATH = "integrationOperationPath";
    public static final String OPERATION_METHOD = "integrationOperationMethod";
    public static final String OPERATION_PATH_PARAMETERS = "integrationOperationPathParameters";
    public static final String OPERATION_QUERY_PARAMETERS = "integrationOperationQueryParameters";
    public static final String AUTHORIZATION_CONFIGURATION = "authorizationConfiguration";

    public static final String SYSTEM_TYPE_INTERNAL = "INTERNAL";
    public static final String SYSTEM_TYPE_EXTERNAL = "EXTERNAL";
    public static final String SYSTEM_TYPE_IMPLEMENTED = "IMPLEMENTED";

    public static final String DIRECTORY_NAME = "directoryName";
    public static final String FILENAME = "fileName";
    public static final String NOOP = "noop";
    public static final String AGGREGATION_STRATEGY = "aggregationStrategy";

    public static final String BEFORE = "before";
    public static final String AFTER = "after";

    public static final String MAPPING_SOURCE = "mappingSource";
    public static final String MAPPING_TARGET = "mappingTarget";
    public static final String MAPPING = "mapping";

    public static final String RESPONSE_CODE = "code";
    public static final String TYPE = "type";
    public static final String LABEL = "label";
    public static final String ERROR_HANDLING = "errorHandling";
    public static final String PROCESSING_BEFORE = "processingBefore";
    public static final String MAPPING_BEFORE = "mappingBefore";
    public static final String MAPPING_AFTER = "mappingAfter";

    public static final String PROCESSING_AFTER = "processingAfter";
    public static final String SCRIPT_BEFORE = "scriptBefore";
    public static final String SCRIPT_AFTER = "scriptAfter";

    public static final String IS_EXTERNAL_CALL = "isExternalCall";
    public static final String CONNECT_TIMEOUT = "connectTimeout";

    public static final String MAAS_TOPICS_CLASSIFIER_NAME_PROP = "topicsClassifierName"; // used for kafka trigger/sender
    public static final String MAAS_VHOST_CLASSIFIER_NAME_PROP = "vhostClassifierName"; // used for rabbitmq trigger/sender
    public static final String MAAS_CLASSIFIER_NAMESPACE_PROP = "maasClassifierNamespace"; // used for kafka/rabbitmq trigger/sender

    public static final String CONNECTION_SOURCE_TYPE_PROP = "connectionSourceType";
    public static final String MAAS_DEPLOYMENT_CLASSIFIER_PROP = "maasClassifier";
    public static final String DEFAULT_VHOST_CLASSIFIER_NAME = "public";

    public static final String SDS_JOB_ID = "jobId";

    public static final String IDEMPOTENCY_PROP = "idempotency";
    public static final String EXPIRY_PROP = "keyExpiry";

    @Deprecated(since = "23.1", forRemoval = true)
    public static final String MAAS_ENV_PROP_PREFIX = "maas.";

    private CamelOptions() {
    }

}
