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

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.model.ElementRoute;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.http.HttpMethod;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class TriggerUtils {

    private static final Set<String> ASYNC_TRIGGER_TYPE_NAMES = Set.of(
        CamelNames.ASYNC_API_TRIGGER_COMPONENT,
        CamelNames.RABBITMQ_TRIGGER_COMPONENT,
        CamelNames.RABBITMQ_TRIGGER_2_COMPONENT,
        CamelNames.KAFKA_TRIGGER_COMPONENT,
        CamelNames.KAFKA_TRIGGER_2_COMPONENT
    );

    public static ElementRoute getHttpTriggerRoute(ChainElement element) {
        return ElementRoute.builder()
                .path(getHttpTriggerPath(element))
                .methods(getHttpTriggerMethods(element))
                .isExternal(isExternalHttpTrigger(element))
                .isPrivate(isPrivateHttpTrigger(element))
                .connectionTimeout(getHttpConnectionTimeout(element))
                .build();
    }

    public static String getSdsTriggerJobId(ChainElement element) {
        Map<String, Object> properties = element.getProperties();
        return (String) properties.get(CamelOptions.SDS_JOB_ID);
    }

    public static Long getHttpConnectionTimeout(ChainElement element) {
        Object timeout = element.getProperties().getOrDefault(CamelOptions.CONNECT_TIMEOUT, -1L);
        if (timeout instanceof Long longTimeout) {
            return longTimeout;
        }
        if (timeout instanceof Integer integerTimeout) {
            return Long.valueOf(integerTimeout);
        }
        if (timeout instanceof String stringTimeout) {
            return Long.valueOf(stringTimeout);
        }
        return -1L;
    }

    @NotNull
    public static String getHttpTriggerPathWithoutBase(ChainElement element) {
        Map<String, Object> properties = element.getProperties();

        String contextPath = (String) properties.get(CamelOptions.CONTEXT_PATH);
        if (nonNull(contextPath)) {
            return contextPath;
        }

        String operationPath = (String) properties.getOrDefault(CamelOptions.OPERATION_PATH, "");
        if (isNull(operationPath)) {
            operationPath = "";
        }
        return operationPath;
    }

    public static String getHttpTriggerBasePath(ChainElement element) {
        ServiceEnvironment environment = element.getEnvironment();
        String base = isNull(environment) ? "" : environment.getAddress();
        if (isNull(base)) {
            base = "";
        }
        return StringUtils.strip(base, "/");
    }

    @NotNull
    public static String getHttpTriggerPath(ChainElement element) {
        String path = getHttpTriggerPathWithoutBase(element);
        String base = getHttpTriggerBasePath(element);
        return StringUtils.strip(Paths.get(base, path).toString(), "/");
    }

    public static boolean areHttpTriggerMethodsSpecified(ChainElement element) {
        String httpMethodsString = (String) element.getProperties().getOrDefault(CamelOptions.HTTP_METHOD_RESTRICT, "");
        return !StringUtils.isBlank(httpMethodsString);
    }

    @NotNull
    public static Set<HttpMethod> getHttpTriggerMethods(ChainElement element) {
        String httpMethodsString = (String) element.getProperties().getOrDefault(CamelOptions.HTTP_METHOD_RESTRICT, "");
        Stream<HttpMethod> elementHttpMethods = StringUtils.isBlank(httpMethodsString)
                ? Stream.of(HttpMethod.values())
                : Stream.of(httpMethodsString.split(",")).map(HttpMethod::valueOf);
        return elementHttpMethods.collect(Collectors.toSet());
    }

    public static boolean isExternalHttpTrigger(ChainElement element) {
        return (boolean) element.getProperties().getOrDefault(CamelOptions.IS_EXTERNAL_ROUTE, true);
    }

    public static boolean isPrivateHttpTrigger(ChainElement element) {
        return (boolean) element.getProperties().getOrDefault(CamelOptions.IS_PRIVATE_ROUTE, false);
    }

    public static String getHttpTriggerTypeName() {
        return CamelNames.HTTP_TRIGGER_COMPONENT;
    }

    public static Set<String> getAsyncTriggerTypeNames() {
        return ASYNC_TRIGGER_TYPE_NAMES;
    }

    public static boolean isHttpTrigger(ChainElement element) {
        return CamelNames.HTTP_TRIGGER_COMPONENT.equals(element.getType());
    }

    public static boolean isAsyncTrigger(ChainElement element) {
        return ASYNC_TRIGGER_TYPE_NAMES.contains(element.getType());
    }

    public static boolean isImplementedServiceTrigger(ChainElement element) {
        return StringUtils.isNotBlank((String) element.getProperties().get(CamelOptions.OPERATION_PATH));
    }

    public static boolean isCustomUriHttpTrigger(ChainElement element) {
        return StringUtils.isNotBlank((String) element.getProperties().get(CamelOptions.CONTEXT_PATH));
    }

    public static String getImplementedServiceTriggerSystemId(ChainElement element) {
        return (String) element.getProperties().get(CamelOptions.SYSTEM_ID);
    }

    public static String getImplementedServiceTriggerSpecificationId(ChainElement element) {
        return (String) element.getProperties().get(CamelOptions.MODEL_ID);
    }

    public static String getImplementedServiceTriggerOperationId(ChainElement element) {
        return (String) element.getProperties().get(CamelOptions.OPERATION_ID);
    }

    public static String getHttpTriggerValidationSchema(ChainElement element) {
        return (String) element.getProperties().get(CamelOptions.VALIDATION_SCHEMA);
    }
}
