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

package org.qubership.integration.platform.util;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.constants.CamelNames;

import java.util.*;
import java.util.stream.Collectors;

public class ElementUtils {

    public static Map<String, Object> extractOperationAsyncProperties(Map<String, Object> properties) {
        return (Map<String, Object>) properties.getOrDefault(
            CamelNames.OPERATION_ASYNC_PROPERTIES, Collections.emptyMap());
    }

    public static Map<String, Object> extractGrpcProperties(Map<String, Object> properties) {
        return (Map<String, Object>) properties.getOrDefault(CamelNames.GRPC_PROPERTIES, Collections.emptyMap());
    }

    public static Map<String, Object> extractServiceCallProperties(Map<String, Object> properties) {
        return (Map<String, Object>) properties.getOrDefault(CamelNames.SERVICE_CALL_ADDITIONAL_PARAMETERS, Collections.emptyMap());
    }


    /**
     * @param primary - overrides secondary parameters in case of conflict and if value non-empty
     * @param secondary
     * @return merged properties
     */
    public static Map<String, Object> mergeProperties(Map<String, Object> primary, Map<String, Object> secondary) {
        Map<String, Object> merged = new HashMap<>(secondary);
        merged.putAll(primary);
        return merged.entrySet().stream()
                .filter(prop -> StringUtils.isNotEmpty((CharSequence) prop.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static String getPropertyAsString(Element element, String name) {
        return Optional.ofNullable(element.getProperties().get(name))
            .map(String::valueOf)
            .orElse(null);
    }

    public static String buildRouteVariableName(Element element) {
        return "route-" + element.getOriginalId().orElseGet(element::getId);
    }
}
