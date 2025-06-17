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

import java.util.Set;

public final class CamelNames {

    public static final String ROUTE = "route";
    public static final String ROUTES = "routes";

    public static final String AGGREGATE = "aggregate";
    public static final String COMPONENT = "component";
    public static final String JETTY = "jetty";
    public static final String SERVLET = "servlet";
    public static final String HTTP = "http";
    public static final String RABBITMQ_TRIGGER_COMPONENT = "rabbitmq";
    public static final String RABBITMQ_SENDER_COMPONENT = "rabbitmq-sender";
    public static final String RABBITMQ_TRIGGER_2_COMPONENT = "rabbitmq-trigger-2";
    public static final String RABBITMQ_SENDER_2_COMPONENT = "rabbitmq-sender-2";
    public static final String KAFKA_TRIGGER_COMPONENT = "kafka";
    public static final String KAFKA_SENDER_COMPONENT = "kafka-sender";
    public static final String KAFKA_TRIGGER_2_COMPONENT = "kafka-trigger-2";
    public static final String KAFKA_SENDER_2_COMPONENT = "kafka-sender-2";
    public static final String SPLIT_COMPONENT = "split";
    public static final String SPLIT_ELEMENT = "split-element";
    public static final String LOOP_EXPRESSION = "loop-expression";
    public static final String MAIN_SPLIT_ELEMENT = "main-split-element";
    public static final String JMS_SENDER_COMPONENT = "jms-sender";
    public static final String JMS_TRIGGER_COMPONENT = "jms-trigger";
    public static final String FILE = "file";
    public static final String DIRECT = "direct";
    public static final String CONTAINER = "container";
    public static final String SERVICE_CALL_COMPONENT = "service-call";
    public static final String HTTP_TRIGGER_COMPONENT = "http-trigger";
    public static final String HTTP_SENDER_COMPONENT = "http-sender";
    public static final String GRAPHQL_SENDER_COMPONENT = "graphql-sender";
    public static final String ASYNC_API_TRIGGER_COMPONENT = "async-api-trigger";
    public static final String SPLIT_ASYNC_2_COMPONENT = "split-async-2";
    public static final String SPLIT_ASYNC_COMPONENT = "split-async";
    public static final String LOOP_COMPONENT = "loop-2";
    public static final String CIRCUIT_BREAKER_CONFIGURATION_COMPONENT = "circuit-breaker-configuration-2";
    public static final String ON_FALLBACK_COMPONENT = "on-fallback-2";
    public static final String SDS_TRIGGER_COMPONENT = "sds-trigger";
    public static final String UNSUPPORTED_COMPONENT = "unsupported";
    public static final String CHAIN_CALL_2_COMPONENT = "chain-call-2";
    public static final String PUBSUB_TRIGGER_COMPONENT = "pubsub-trigger";
    public static final String PUBSUB_SENDER_COMPONENT = "pubsub-sender";

    public static final String SYSTEM = "System";
    public static final String EXTERNAL_SYSTEM = "externalSystem";

    public static final String CHOICE = "choice";
    public static final String WHEN = "when";
    public static final String OTHERWISE = "otherwise";

    public static final String MULTICAST = "multicast";
    public static final String TRANSLATOR = "translator";
    public static final String FILTER = "filter";
    public static final String CONTENT_FILTER = "contentFilter";

    public static final String TRY_CATCH_FINALLY = "tryCatchFinally";
    public static final String DO_TRY = "try";
    public static final String DO_CATCH = "catch";
    public static final String DO_FINALLY = "finally";
    public static final String EXCEPTION = "exception";
    public static final String ON_WHEN = "onWhen";
    public static final String LOG = "log";
    public static final String SET_BODY = "setBody";
    public static final String SET_HEADER = "setHeader";
    public static final String SCRIPT = "script";
    public static final String MAPPER = "mapper";
    public static final String MAPPER_2 = "mapper-2";
    public static final String HEADER_MODIFICATION = "header-modification";
    public static final String TO = "toD";
    public static final String FROM = "from";
    public static final String NONE = "none";
    public static final String CHECKPOINT = "checkpoint";
    public static final String SCHEDULER = "quartz-scheduler";

    public static final String OPERATION_PROTOCOL_TYPE_PROP = "integrationOperationProtocolType";
    public static final String OPERATION_PROTOCOL_TYPE_KAFKA = "kafka";
    public static final String OPERATION_PROTOCOL_TYPE_AMQP = "amqp";
    public static final String OPERATION_PROTOCOL_TYPE_HTTP = "http";
    public static final String OPERATION_PROTOCOL_TYPE_GRPC = "grpc";
    public static final String OPERATION_PROTOCOL_TYPE_GRAPHQL = "graphql";
    public static final String OPERATION_PATH_TOPIC = "integrationOperationPath";
    public static final String OPERATION_PATH_EXCHANGE = "integrationOperationPath";
    public static final String OPERATION_ASYNC_PROPERTIES = "integrationOperationAsyncProperties";
    public static final String SERVICE_CALL_ADDITIONAL_PARAMETERS = "integrationAdditionalParameters";

    public static final String GRPC_PROPERTIES = "grpcProperties";
    public static final Set<String> GRPC_PROPERTY_NAMES = Set.of(
            "flowControlWindow",
            "maxMessageSize",
            "autoDiscoverClientInterceptors",
            "userAgent",
            "lazyStartProducer",
            "authenticationType",
            "jwtAlgorithm",
            "jwtIssuer",
            "jwtSecret",
            "jwtSubject",
            "keyCertChainResource",
            "keyPassword",
            "keyResource",
            "negotiationType",
            "serviceAccountResource",
            "trustCertCollectionResource"
    );

    public static final String MAAS_CLASSIFIER_NAME_PROP = "maas.classifier.name"; // used for service call, async api trigger
    public static final String MAAS_CLASSIFIER_NAMESPACE_PROP = "maas.classifier.namespace"; // used for service call, async api trigger
    public static final String GQL_OPERATION_NAME_PROP = "integrationGqlOperationName";
    public static final String CHAIN_CALL_ELEMENT_ID = "elementId";
    public static final String REUSE_ESTABLISHED_CONN = "reuseEstablishedConnection";

    private CamelNames() {
    }

}
