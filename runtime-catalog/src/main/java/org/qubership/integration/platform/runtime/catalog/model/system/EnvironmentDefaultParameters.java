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

package org.qubership.integration.platform.runtime.catalog.model.system;

import java.util.AbstractMap;
import java.util.Map;

public class EnvironmentDefaultParameters {
    public static final Map<String, String> HTTP_ENVIRONMENT_PARAMETERS = Map.ofEntries(
            new AbstractMap.SimpleEntry<>("connectTimeout", "120000"),
            new AbstractMap.SimpleEntry<>("soTimeout", "120000"),
            new AbstractMap.SimpleEntry<>("connectionRequestTimeout", "120000"),
            new AbstractMap.SimpleEntry<>("responseTimeout", "120000"),
            new AbstractMap.SimpleEntry<>("getWithBody", "false"),
            new AbstractMap.SimpleEntry<>("deleteWithBody", "false")
    );

    public static final Map<String, String> KAFKA_ENVIRONMENT_PARAMETERS = Map.ofEntries(
            new AbstractMap.SimpleEntry<>("key", ""),
            new AbstractMap.SimpleEntry<>("sslProtocol", ""),
            new AbstractMap.SimpleEntry<>("saslMechanism", ""),
            new AbstractMap.SimpleEntry<>("saslJaasConfig", ""),
            new AbstractMap.SimpleEntry<>("securityProtocol", ""),
            new AbstractMap.SimpleEntry<>("sslEnabledProtocols", ""),
            new AbstractMap.SimpleEntry<>("sslEndpointAlgorithm", "")
    );

    public static final Map<String, String> MAAS_BY_CLASSIFIER_KAFKA_ENVIRONMENT_PARAMETERS = Map.ofEntries();

    public static final Map<String, String> RABBIT_ENVIRONMENT_PARAMETERS = Map.ofEntries(
            new AbstractMap.SimpleEntry<>("password", ""),
            new AbstractMap.SimpleEntry<>("username", ""),
            new AbstractMap.SimpleEntry<>("routingKey", ""),
            new AbstractMap.SimpleEntry<>("acknowledgeMode", "AUTO")
    );

    public static final Map<String, String> MAAS_BY_CLASSIFIER_RABBIT_ENVIRONMENT_PARAMETERS = Map.ofEntries(
            new AbstractMap.SimpleEntry<>("routingKey", ""),
            new AbstractMap.SimpleEntry<>("acknowledgeMode", "AUTO")
    );
}
