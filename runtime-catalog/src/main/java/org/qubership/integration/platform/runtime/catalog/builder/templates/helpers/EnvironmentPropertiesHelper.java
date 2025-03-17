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

package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.exception.SnapshotCreationException;
import org.qubership.integration.platform.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.util.ElementUtils;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;
import org.qubership.integration.platform.runtime.catalog.util.MaasUtils;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.*;

@TemplatesHelper
public class EnvironmentPropertiesHelper {

    private static final ObjectMapper OBJECT_MAPPER = initObjectMapper();

    private static final String[] KAFKA_MAAS_PARAM_ARRAY = {SECURITY_PROTOCOL, SASL_MECHANISM, SASL_JAAS_CONFIG};
    public static final ArrayList<String> KAFKA_MAAS_PARAM_LIST = new ArrayList<>(Arrays.asList(KAFKA_MAAS_PARAM_ARRAY));
    private static final String[] AMQP_MAAS_PARAM_ARRAY = {VHOST, USERNAME, PASSWORD, SSL};
    public static final ArrayList<String> AMQP_MAAS_PARAM_LIST = new ArrayList<>(Arrays.asList(AMQP_MAAS_PARAM_ARRAY));

    /**
     * Handlebars helper, that returns environment properties in json format
     */
    @SuppressWarnings("unused")
    public CharSequence environmentPropertiesJson(ChainElement element) {
        ServiceEnvironment environment = element.getEnvironment();
        if (environment == null)
            throw new SnapshotCreationException("Couldn't find service or active service environment.", element);
        Map<String, Object> environmentProperties = environment.getProperties();
        if (environmentProperties != null) {
            Map<String, Object> mergedProperties =
                    ElementUtils.mergeProperties(
                                    ElementUtils.extractServiceCallProperties(element.getProperties()), environmentProperties)
                            .entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            try {
                return OBJECT_MAPPER.writeValueAsString(mergedProperties);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(
                        "Error processing json in environmentPropertiesJson helper", e);
            }
        }
        return StringUtils.EMPTY;
    }

    /**
     * Handlebars helper, that returns merged [environment + element async] properties in json format
     */
    @SuppressWarnings("unused")
    public CharSequence asyncPropertiesJson(ChainElement element) {
        ServiceEnvironment environment = element.getEnvironment();
        if (environment == null)
            throw new SnapshotCreationException("Couldn't find service or active service environment.", element);
        ArrayList<String> maasParamList = MaasUtils.getMaasParams(element);
        if (environment.getProperties() != null) {
            if(!maasParamList.isEmpty() ) {
                putMassParams(element, environment);
            }
            Map<String, Object> filteredEnvProperties = environment.getProperties();
            Map<String, Object> mergedProperties =
                ElementUtils.mergeProperties(
                    ElementUtils.extractOperationAsyncProperties(element.getProperties()), filteredEnvProperties)
                .entrySet().stream()
                .filter(filterAsyncProperties())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            try {
                return OBJECT_MAPPER.writeValueAsString(mergedProperties);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(
                    "Error processing json in environmentPropertiesJson helper", e);
            }
        }
        return StringUtils.EMPTY;
    }

    private static void putMassParams(ChainElement element, ServiceEnvironment environment) {
        Map<String, Object> elementProperties = element.getProperties();
        ArrayList<String> maasParamList = new ArrayList<>();
        String operationProtocolType = "";
        if(elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP) != null){
            operationProtocolType = (String) elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP);
        }
        if(StringUtils.isNotEmpty(operationProtocolType)) {
            if (StringUtils.equalsIgnoreCase(OPERATION_PROTOCOL_TYPE_KAFKA, operationProtocolType)) {
                maasParamList = KAFKA_MAAS_PARAM_LIST;
            } else if (StringUtils.isNotEmpty(operationProtocolType) && StringUtils.equalsIgnoreCase(OPERATION_PROTOCOL_TYPE_AMQP, operationProtocolType)) {
                maasParamList = AMQP_MAAS_PARAM_LIST;
            }
            for (String param : maasParamList) {
                environment.getProperties().put(param, MaasUtils.getMaasParamPlaceholder(element.getOriginalId(), param));
            }
        }
    }


    /**
     * Handlebars helper, that returns merged [environment + element grpc] properties in json format
     */
    @SuppressWarnings("unused")
    public CharSequence grpcPropertiesJson(ChainElement element) {
        ServiceEnvironment environment = element.getEnvironment();
        if (isNull(environment)) {
            throw new SnapshotCreationException("Couldn't find service or active service environment.", element);
        }

        Map<String, Object> properties = Stream.of(
                Optional.ofNullable(environment.getProperties())
                .orElse(Collections.emptyMap()),
                ElementUtils.extractGrpcProperties(element.getProperties())
        )
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .filter(grpcProperties())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> newValue
                ));
        try {
            return OBJECT_MAPPER.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Error processing json in environmentPropertiesJson helper", e);
        }
    }

    private static Predicate<Entry<String, Object>> filterAsyncProperties() {
        return prop -> prop.getValue() != null && !(prop.getKey().startsWith(MAAS_ENV_PROP_PREFIX));
    }

    private static Predicate<Entry<String, Object>> grpcProperties() {
        return prop -> nonNull(prop.getValue()) && GRPC_PROPERTY_NAMES.contains(prop.getKey());
    }

    private static ObjectMapper initObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        return mapper;
    }
}
