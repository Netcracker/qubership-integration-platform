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

package org.qubership.integration.platform.runtime.catalog.service.resolvers.async;

public final class AsyncConstants {

    public static final String KAFKA_BINDING_CLASS = "kafka";
    public static final String AMQP_BINDING_CLASS = "amqp";

    // Keys the resolvers write into Operation.specification — one contract, read back by the UI and the extension.
    // Names the resolvers read out of an AsyncAPI document stay with their resolver: those belong to AsyncAPI's
    // binding vocabulary, not to this node. Same reason CamelOptions.EXCHANGE is not this "exchangeName".
    public static final String SPEC_PROPERTY_TOPIC = "topic";
    public static final String SPEC_PROPERTY_QUEUE_NAME = "queue";
    public static final String SPEC_PROPERTY_EXCHANGE_NAME = "exchangeName";
    public static final String SPEC_PROPERTY_USERNAME = "username";
    // The element/env-facing canonical key for this one is CamelNames.MAAS_CLASSIFIER_NAME_PROP.
    public static final String SPEC_PROPERTY_MAAS_CLASSIFIER_NAME = "maasClassifierName";

}
