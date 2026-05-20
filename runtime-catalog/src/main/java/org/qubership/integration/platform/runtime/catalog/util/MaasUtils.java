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

package org.qubership.integration.platform.runtime.catalog.util;

import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.ConnectionSourceType;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.*;

public class MaasUtils {
    private static final String[] KAFKA_MAAS_PARAM_ARRAY = {BROKERS, TOPICS, SECURITY_PROTOCOL, SASL_MECHANISM, SASL_JAAS_CONFIG};

    public static ArrayList<String> KAFKA_MAAS_PARAM_LIST = new ArrayList<>(Arrays.asList(KAFKA_MAAS_PARAM_ARRAY));
    private static final String[] AMQP_MAAS_PARAM_ARRAY = {ADDRESSES, VHOST, USERNAME, PASSWORD, SSL};
    public static ArrayList<String> AMQP_MAAS_PARAM_LIST = new ArrayList<>(Arrays.asList(AMQP_MAAS_PARAM_ARRAY));

    public static String getMaasParamPlaceholder(String elementId, String paramName) {
        return String.format("%%%%{%s_%s}", elementId, paramName);
    }

    public static ArrayList<String> getMaasParams(ChainElement element) {
        try {
            ServiceEnvironment environment = element.getEnvironment();
            Map<String, Object> elementProperties = element.getProperties();
            if (element.getType() == null) {
                return new ArrayList<>();
            }
                    switch (element.getType()) {
                        case CamelNames.SERVICE_CALL_COMPONENT,
                             CamelNames.ASYNC_API_TRIGGER_COMPONENT -> {
                            if (environment != null
                                    && environment.getSourceType() == EnvironmentSourceType.MAAS_BY_CLASSIFIER
                                    && elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP) != null) {
                                switch ((String) elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP)) {
                                    case OPERATION_PROTOCOL_TYPE_KAFKA -> {
                                        return KAFKA_MAAS_PARAM_LIST;
                                    } case OPERATION_PROTOCOL_TYPE_AMQP -> {
                                        return AMQP_MAAS_PARAM_LIST;
                                    }
                                }
                            }
                        }
                        case CamelNames.KAFKA_SENDER_2_COMPONENT, CamelNames.KAFKA_TRIGGER_2_COMPONENT -> {
                            if (ConnectionSourceType.MAAS.toString().equalsIgnoreCase((String) elementProperties.get(CONNECTION_SOURCE_TYPE_PROP))) {
                                return KAFKA_MAAS_PARAM_LIST;
                            }
                        }
                        case CamelNames.RABBITMQ_SENDER_2_COMPONENT, CamelNames.RABBITMQ_TRIGGER_2_COMPONENT  -> {
                            if (ConnectionSourceType.MAAS.toString().equalsIgnoreCase((String) elementProperties.get(CONNECTION_SOURCE_TYPE_PROP))) {
                                return AMQP_MAAS_PARAM_LIST;
                            }
                        }
                    }
        } catch (Exception e) {
            throw new SnapshotCreationException("Failed to get MaaS state.", element, e);
        }
        return new ArrayList<>();
    }
}
