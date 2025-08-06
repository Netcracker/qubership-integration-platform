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

package org.qubership.integration.platform.engine.model;

import java.util.*;
import java.util.stream.Collectors;

public enum ChainElementType {
    SERVICE_CALL("service-call"),
    CONTEXT_STORAGE("context-storage"),
    HTTP_TRIGGER("http-trigger"),
    KAFKA_TRIGGER("kafka"),
    RABBITMQ_TRIGGER("rabbitmq"),
    ASYNCAPI_TRIGGER("async-api-trigger"),
    KAFKA_SENDER("kafka-sender"),
    RABBITMQ_SENDER("rabbitmq-sender"),
    HTTP_SENDER("http-sender"),
    CIRCUIT_BREAKER("circuit-breaker"),
    CIRCUIT_BREAKER_MAIN_ELEMENT("circuit-breaker-configuration"),
    CIRCUIT_BREAKER_FALLBACK("on-fallback"),
    CIRCUIT_BREAKER_2("circuit-breaker-2"),
    CIRCUIT_BREAKER_MAIN_ELEMENT_2("circuit-breaker-configuration-2"),
    CIRCUIT_BREAKER_FALLBACK_2("on-fallback-2"),
    CHECKPOINT("checkpoint"),
    KAFKA_TRIGGER_2("kafka-trigger-2"),
    RABBITMQ_TRIGGER_2("rabbitmq-trigger-2"),
    KAFKA_SENDER_2("kafka-sender-2"),
    RABBITMQ_SENDER_2("rabbitmq-sender-2"),
    GRAPHQL_SENDER("graphql-sender"),
    JMS_SENDER("jms-sender"),
    JMS_TRIGGER("jms-trigger"),
    MAIL_SENDER("mail-sender"),
    SCHEDULER("scheduler"),
    QUARTZ_SCHEDULER("quartz-scheduler"),
    SFTP_TRIGGER("sftp-trigger"),
    SFTP_TRIGGER_2("sftp-trigger-2"),
    TRY("try"),
    TRY_2("try-2"),
    TRY_CATCH_FINALLY("try-catch-finally"),
    TRY_CATCH_FINALLY_2("try-catch-finally-2"),
    PUBSUB_SENDER("pubsub-sender"),
    PUBSUB_TRIGGER("pubsub-trigger"),
    SDS_TRIGGER("sds-trigger"),
    CHAIN_CALL("chain-call-2"),
    LOOP_2("loop-2"),
    SPLIT_2("split-2"),
    MAIN_SPLIT_ELEMENT_2("main-split-element-2"),
    SPLIT_ELEMENT_2("split-element-2"),
    CHAIN_TRIGGER("chain-trigger"),
    CHAIN_TRIGGER_2("chain-trigger-2"),
    CONDITION("condition"),
    IF("if"),
    SPLIT_ASYNC_2("split-async-2"),
    ASYNC_SPLIT_ELEMENT_2("async-split-element-2"),
    FINALLY_2("finally-2"),
    UNKNOWN("");
    // add more elements as needed

    private static final Map<String, ChainElementType> ELEMENTS;
    private static final Set<ChainElementType> KAFKA_ASYNC_ELEMENTS = Collections.unmodifiableSet(
            EnumSet.of(KAFKA_SENDER, KAFKA_TRIGGER, KAFKA_SENDER_2, KAFKA_TRIGGER_2, ASYNCAPI_TRIGGER,
                    SERVICE_CALL));
    private static final Set<ChainElementType> AMQP_ASYNC_ELEMENTS = Collections.unmodifiableSet(
            EnumSet.of(RABBITMQ_SENDER, RABBITMQ_TRIGGER, RABBITMQ_SENDER_2, RABBITMQ_TRIGGER_2,
                    ASYNCAPI_TRIGGER, SERVICE_CALL));
    private static final Set<ChainElementType> AMQP_PRODUCER_ELEMENTS = Collections.unmodifiableSet(
            EnumSet.of(RABBITMQ_SENDER, RABBITMQ_SENDER_2, SERVICE_CALL));

    private static final Set<ChainElementType> HTTP_ELEMENTS = Collections.unmodifiableSet(
            EnumSet.of(SERVICE_CALL, HTTP_SENDER, HTTP_TRIGGER, GRAPHQL_SENDER));

    private static final Set<ChainElementType> INFO_SESSIONS_ELEMENTS = Collections.unmodifiableSet(
            EnumSet.of(
                    JMS_TRIGGER, JMS_SENDER, SFTP_TRIGGER, SFTP_TRIGGER_2, HTTP_TRIGGER, KAFKA_TRIGGER,
                    KAFKA_TRIGGER_2, RABBITMQ_TRIGGER, RABBITMQ_TRIGGER_2, ASYNCAPI_TRIGGER,
                    HTTP_SENDER, GRAPHQL_SENDER, MAIL_SENDER, KAFKA_SENDER, KAFKA_SENDER_2,
                    RABBITMQ_SENDER, RABBITMQ_SENDER_2, SERVICE_CALL, SCHEDULER, QUARTZ_SCHEDULER, CHECKPOINT, PUBSUB_SENDER,
                    PUBSUB_TRIGGER, SDS_TRIGGER, CHAIN_CALL));

    private static final Set<ChainElementType> EXCEPTION_HANDLE_ELEMENTS = Collections.unmodifiableSet(
            EnumSet.of(TRY, TRY_2, TRY_CATCH_FINALLY_2, TRY_CATCH_FINALLY, CIRCUIT_BREAKER_2, CIRCUIT_BREAKER)
    );

    private static final Set<ChainElementType> WRAPPED_IN_STEP_ELEMENTS = Collections.unmodifiableSet(
            EnumSet.of(
                    CIRCUIT_BREAKER_2, CIRCUIT_BREAKER_MAIN_ELEMENT_2, CIRCUIT_BREAKER_FALLBACK_2, LOOP_2, SPLIT_2,
                    MAIN_SPLIT_ELEMENT_2, SPLIT_ELEMENT_2, CONDITION, IF, TRY_2, TRY_CATCH_FINALLY_2, FINALLY_2,
                    SPLIT_ASYNC_2, ASYNC_SPLIT_ELEMENT_2
            )
    );

    private final String text;

    static {
        ELEMENTS = Arrays.stream(ChainElementType.values()).collect(Collectors.toMap(ChainElementType::getText, v -> v));
    }

    ChainElementType(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public static ChainElementType fromString(String text) {
        return ELEMENTS.getOrDefault(text, UNKNOWN);
    }

    public static boolean isElementForInfoSessionsLevel(ChainElementType chainElementType) {
        return INFO_SESSIONS_ELEMENTS.contains(chainElementType);
    }

    public static boolean isKafkaAsyncElement(ChainElementType chainElementType) {
        return KAFKA_ASYNC_ELEMENTS.contains(chainElementType);
    }

    public static boolean isAmqpAsyncElement(ChainElementType chainElementType) {
        return AMQP_ASYNC_ELEMENTS.contains(chainElementType);
    }

    public static boolean isAmqpProducerElement(ChainElementType chainElementType) {
        return AMQP_PRODUCER_ELEMENTS.contains(chainElementType);
    }

    public static boolean isHttpElement(ChainElementType chainElementType) {
        return HTTP_ELEMENTS.contains(chainElementType);
    }

    public static boolean isExceptionHandleElement(ChainElementType chainElementType) {
        return EXCEPTION_HANDLE_ELEMENTS.contains(chainElementType);
    }

    public static boolean isSdsTriggerElement(ChainElementType chainElementType) {
        return SDS_TRIGGER.equals(chainElementType);
    }

    public static boolean isWrappedInStepElement(ChainElementType chainElementType) {
        return WRAPPED_IN_STEP_ELEMENTS.contains(chainElementType);
    }
}
