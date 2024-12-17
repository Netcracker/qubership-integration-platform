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

package org.qubership.integration.platform.engine.service;

import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import lombok.Getter;
import org.apache.camel.Exchange;

import java.util.*;
import java.util.stream.Collectors;

@Getter
public final class ServiceVariableUtils {

    private static final ServiceVariableUtils INSTANCE = new ServiceVariableUtils();

    private List<String> serviceVariablesName;

    private ServiceVariableUtils() {
        this.serviceVariablesName = new ArrayList<>();
        initServiceVariablesNames();
    }

    private void initServiceVariablesNames() {
        serviceVariablesName.addAll(getServiceVariablesName(Exchange.class));
        serviceVariablesName.addAll(getServiceVariablesName(CamelDebugger.class));
    }

    private List<String> getServiceVariablesName(Class<?> clazz) {
        var fields = clazz.getDeclaredFields();
        return Arrays.stream(fields)
                .filter(field -> {
                    var type = field.getType();
                    return type.equals(String.class);
                })
                .map(field -> {
                    field.setAccessible(true);
                    try {
                        return (String) field.get(clazz);
                    } catch (IllegalAccessException e) {}
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getCustomProperties(Exchange exchange) {
        var allProperties = exchange.getProperties();
        var mapCopy = new HashMap<>(allProperties);
        serviceVariablesName.forEach(mapCopy::remove);
        return mapCopy;
    }

}
