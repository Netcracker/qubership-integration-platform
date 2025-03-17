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

package org.qubership.integration.platform.variables.management.consul;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.variables.management.model.consul.txn.KVResponse;
import org.qubership.integration.platform.variables.management.model.consul.txn.KeyResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@Slf4j
@Component
public class ConsulService {
    private final ConsulClient client;

    @Value("${consul.keys.prefix}")
    private String keyPrefix;

    @Value("${consul.keys.engine-config-root}")
    private String keyEngineConfigRoot;

    /**
     * Use keyCommonVariablesV2
     */
    @Deprecated(since = "24.1")
    @Value("${consul.keys.common-variables-v1}")
    public String keyCommonVariables;

    // No multitenancy
    @Value("${consul.keys.common-variables-v2}")
    private String keyCommonVariablesV2;

    @Autowired
    public ConsulService(ConsulClient client) {
        this.client = client;
    }

    public @Nullable Pair<String, String> getCommonVariable(String key) {
        try {
            List<KeyResponse> response = client.getKV(buildCommonVariableKey(key), false);
            return response.isEmpty() ? null : parseCommonVariable(response.get(0));
        } catch (KVNotFoundException kvnfe) {
            return null;
        }
    }

    /**
     * No error handling in case of empty KV
     */
    public Map<String, String> getCommonVariables(List<String> variablesNames) {
        List<KVResponse> response = client.getKVsInTransaction(variablesNames.stream()
                .map(this::buildCommonVariableKeyForTxn)
                .toList());
        return response.stream().map(this::parseCommonVariable)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Pair::getKey, nullValueRemapping()));
    }

    /**
     * Use {@link ConsulService#getAllCommonVariables()}
     */
    @Deprecated(since = "24.1")
    public Map<String, String> getTenantCommonVariablesLegacy(String tenantId) {
        return getStringStringMapLegacy(keyCommonVariables + "/" + tenantId);
    }

    public Map<String, String> getAllCommonVariables() {
        return getStringStringMap(keyCommonVariablesV2);
    }

    public boolean commonVariablesKvExists() {
        try {
            client.getKV(keyPrefix + keyEngineConfigRoot + keyCommonVariablesV2, true);
            return true;
        } catch (KVNotFoundException kvnfe) {
            return false;
        }
    }

    /**
     * Use {@link ConsulService#getStringStringMap(String key)}
     */
    @Deprecated(since = "24.1")
    @NotNull
    private Map<String, String> getStringStringMapLegacy(String key) {
        return getStringStringMap(key, this::parseCommonVariable);
    }

    @NotNull
    private Map<String, String> getStringStringMap(String key) {
        return getStringStringMap(key, this::parseCommonVariable);
    }

    @NotNull
    private Map<String, String> getStringStringMap(String key, Function<KeyResponse, Pair<String, String>> responseParser) {
        try {
            final String keyPrefix = this.keyPrefix + keyEngineConfigRoot + key;
            List<KeyResponse> responses =
                    client.getKV(keyPrefix, true);

            return responses.stream()
                    .filter(keyResponse -> filterL1NonEmptyPaths(keyPrefix, keyResponse.getKey()))
                    .map(responseParser)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(Pair::getKey, nullValueRemapping()));
        } catch (KVNotFoundException kvnfe) {
            return Collections.emptyMap();
        }
    }

    @NotNull
    private static Function<Pair<String, String>, String> nullValueRemapping() {
        return pair -> pair.getValue() == null ? "" : pair.getValue();
    }

    public void deleteLegacyVariablesKV() {
        client.deleteKV(keyPrefix + keyEngineConfigRoot + keyCommonVariables, true);
    }

    public void deleteCommonVariable(String key) {
        client.deleteKV(buildCommonVariableKey(key), false);
    }

    public void deleteCommonVariables(List<String> variablesNames) {
        client.deleteKVsInTransaction(variablesNames.stream()
                .map(this::buildCommonVariableKeyForTxn)
                .toList());
    }

    public void updateCommonVariable(String key, String value) {
        client.createOrUpdateKV(buildCommonVariableKey(key), value);
    }

    public void updateCommonVariables(Map<String, String> variables) {
        client.createOrUpdateKVsInTransaction(variables.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> buildCommonVariableKeyForTxn(entry.getKey()),
                        Map.Entry::getValue)));
    }

    @NotNull
    private String buildCommonVariableKey(String key) {
        return keyPrefix + keyEngineConfigRoot + keyCommonVariablesV2 + "/" + key;
    }

    @NotNull
    private String buildCommonVariableKeyForTxn(String key) {
        return buildCommonVariableKey(key).replaceFirst("^/", "");
    }

    /**
     * Get last path word as a key and decode value
     *
     * @return key and value
     */
    private Pair<String, String> parseCommonVariable(KVResponse k) {
        String[] split = k.getKey().split("/");
        return split.length > 0 ? Pair.of(split[split.length - 1], k.getDecodedValue()) : null;
    }

    private static boolean filterL1NonEmptyPaths(String pathPrefix, String path) {
        String[] split = path.substring(pathPrefix.length()).split("/");
        return split.length == 1 && StringUtils.isNotEmpty(split[0]);
    }
}
