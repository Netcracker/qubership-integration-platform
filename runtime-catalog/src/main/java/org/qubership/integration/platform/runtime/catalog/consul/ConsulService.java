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

package org.qubership.integration.platform.runtime.catalog.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.qubership.integration.platform.runtime.catalog.consul.exception.KVNotFoundException;
import org.qubership.integration.platform.runtime.catalog.consul.exception.RuntimePropertiesException;
import org.qubership.integration.platform.runtime.catalog.model.compiledlibrary.CompiledLibraryUpdate;
import org.qubership.integration.platform.runtime.catalog.model.consul.KeyResponse;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineState;
import org.qubership.integration.platform.runtime.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ConsulService {
    private static final String WAIT_TIMEOUT_STRING = "20s";

    @Deprecated(since = "24.2")
    public static final String CHAINS_RUNTIME_CONFIGURATIONS_LEGACY = "/chains-runtime-configurations";

    @Value("${consul.keys.prefix}")
    private String keyPrefix;

    @Value("${consul.keys.engine-config-root}")
    private String keyEngineConfigRoot;


    @Value("${consul.keys.deployments-update}")
    private String keyDeploymentsUpdate;

    @Value("${consul.keys.engines-state}")
    private String keyEnginesState;

    @Value("${consul.keys.libraries-update}")
    private String keyLibrariesUpdate;


    @Value("${consul.keys.runtime-configurations}")
    private String keyRuntimeConfigurations;

    @Value("${consul.keys.chains}")
    private String keyChains;

    private long chainsRuntimePropertiesPreviousIndex = 0;
    private long chainsRuntimePropertiesLastIndex = 0;
    private long deploymentsStateLastIndex = 0;

    private long enginesStateLastIndex = 0;

    private final ConsulClient client;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConsulService(@Qualifier("primaryObjectMapper") ObjectMapper jsonMapper, ConsulClient client) {
        this.client = client;
        this.objectMapper = jsonMapper;
    }


    /**
     * Use @DeploymentModification annotation to call this method after target method invocation.
     * Should only be called after the transaction is closed.
     */
    public void updateDeploymentsTimestamp() {
        log.debug("Update deployments modification timestamp");
        client.createOrUpdateKV(keyPrefix + keyEngineConfigRoot + keyDeploymentsUpdate, new Date().getTime());
    }

    /**
     * All exceptions will be muted
     */
    public List<EngineState> getEnginesStateSafe() {
        try {
            Pair<Long, List<KeyResponse>> pair =
                    client.waitForKVChanges(keyPrefix + keyEngineConfigRoot + keyEnginesState, true, 0, "0s");

            return parseEnginesReports(pair.getRight());
        } catch (Exception e) {
            log.error("Failed to get engines state: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public Pair<Boolean, List<EngineState>> waitForEnginesStateUpdate() throws KVNotFoundException {
        final String keyPrefix = this.keyPrefix + keyEngineConfigRoot + keyEnginesState;

        Pair<Long, List<KeyResponse>> pair =
                client.waitForKVChanges(keyPrefix, true, enginesStateLastIndex, WAIT_TIMEOUT_STRING);
        boolean changesDetected = pair.getLeft() != enginesStateLastIndex;
        enginesStateLastIndex = pair.getLeft();

        return Pair.of(changesDetected,
                parseEnginesReports(
                        pair.getRight().stream()
                                .filter(keyResponse -> filterL1NonEmptyPaths(keyPrefix, keyResponse.getKey()))
                                .toList()));
    }

    // return <index, timestamp>
    public Pair<Boolean, Long> waitForDeploymentsUpdate() throws KVNotFoundException {
        Pair<Long, List<KeyResponse>> pair =
                client.waitForKVChanges(keyPrefix + keyEngineConfigRoot + keyDeploymentsUpdate,
                        false, deploymentsStateLastIndex, WAIT_TIMEOUT_STRING);
        boolean changesDetected = pair.getLeft() != deploymentsStateLastIndex;
        deploymentsStateLastIndex = pair.getLeft();

        return Pair.of(changesDetected, parseDeploymentsUpdate(pair));
    }

    private Long parseDeploymentsUpdate(Pair<Long, List<KeyResponse>> pair) {
        List<KeyResponse> response = pair.getRight();
        return switch (response.size()) {
            case 0 -> 0L;
            case 1 -> {
                String value = response.get(0).getDecodedValue();
                yield value == null ? 0L : Long.parseLong(value);
            }
            default ->
                    throw new RuntimeException("Failed to parse response, target key in consul has invalid format/size: " + response);
        };
    }

    public void updateLibraries(List<CompiledLibraryUpdate> libs) {
        log.debug("Update deployments modification timestamp");
        client.createOrUpdateKV(keyPrefix + keyEngineConfigRoot + keyLibrariesUpdate, libs);
    }

    public void deleteChainRuntimeConfig(String chainId) {
        client.deleteKey(buildChainRuntimeConfigKey(chainId));
    }

    public void updateChainRuntimeConfig(String chainId, DeploymentRuntimeProperties props) {
        client.createOrUpdateKV(buildChainRuntimeConfigKey(chainId), props);
    }

    public void updateChainsRuntimeConfig(Map<String, DeploymentRuntimeProperties> propsMapping) {
        client.createOrUpdateKVsInTransaction(propsMapping.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> buildChainRuntimeConfigKeyForTxn(entry.getKey()),
                        entry -> {
                            try {
                                return objectMapper.writeValueAsString(entry.getValue());
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })));
    }

    public boolean chainsRuntimeConfigurationKvExists() {
        try {
            client.getKV(keyPrefix + keyEngineConfigRoot + keyRuntimeConfigurations + keyChains, true);
            return true;
        } catch (KVNotFoundException kvnfe) {
            return false;
        }
    }

    public void deleteLegacyChainsRuntimeConfigKV() {
        client.deleteKV(keyPrefix + keyEngineConfigRoot + CHAINS_RUNTIME_CONFIGURATIONS_LEGACY, true);
    }

    /**
     * @return [chainId, properties] map
     */
    public Map<String, DeploymentRuntimeProperties> getChainRuntimeConfig() throws KVNotFoundException {
        List<KeyResponse> response =
                client.getKV(keyPrefix + keyEngineConfigRoot + keyRuntimeConfigurations + keyChains,
                        false);

        return parseChainsRuntimeConfig(response);
    }

    /**
     * @return [changes_detected, [chainId, properties]] map
     */
    public Pair<Boolean, Map<String, DeploymentRuntimeProperties>> waitForChainRuntimeConfig() throws KVNotFoundException {
        Pair<Long, List<KeyResponse>> pair =
                client.waitForKVChanges(keyPrefix + keyEngineConfigRoot + keyRuntimeConfigurations + keyChains,
                        false, chainsRuntimePropertiesLastIndex, WAIT_TIMEOUT_STRING);

        boolean changesDetected = pair.getLeft() != chainsRuntimePropertiesLastIndex;
        chainsRuntimePropertiesPreviousIndex = chainsRuntimePropertiesLastIndex;
        chainsRuntimePropertiesLastIndex = pair.getLeft();

        return Pair.of(changesDetected, parseChainsRuntimeConfig(pair.getRight()));
    }

    public void rollbackChainsRuntimeConfigLastIndex() {
        chainsRuntimePropertiesLastIndex = chainsRuntimePropertiesPreviousIndex;
    }

    private static boolean filterL1NonEmptyPaths(String pathPrefix, String path) {
        String[] split = path.substring(pathPrefix.length()).split("/");
        return split.length == 1 && StringUtils.isNotEmpty(split[0]);
    }

    private List<EngineState> parseEnginesReports(List<KeyResponse> responses) {
        List<EngineState> reports = new ArrayList<>();
        for (var response : responses) {
            try {
                String decodedValue = response.getDecodedValue();
                if (decodedValue != null) {
                    reports.add(objectMapper.readValue(decodedValue, EngineState.class));
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return reports;
    }

    @NotNull
    private String buildChainRuntimeConfigKey(String chainId) {
        return keyPrefix + keyEngineConfigRoot + keyRuntimeConfigurations + keyChains + "/" + chainId;
    }

    @NotNull
    private String buildChainRuntimeConfigKeyForTxn(String key) {
        return buildChainRuntimeConfigKey(key).replaceFirst("^/", "");
    }

    // return Map<chainId, props>
    private Map<String, DeploymentRuntimeProperties> parseChainsRuntimeConfig(List<KeyResponse> response)
            throws RuntimePropertiesException {
        if (response.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, DeploymentRuntimeProperties> result = new HashMap<>();
        boolean exception = false;
        for (KeyResponse keyResponse : response) {
            String chainId = parseChainId(keyResponse);
            if (chainId == null) {
                log.debug("Skip unknown KV (key={}) during parse chains runtime config: ", keyResponse.getKey());
                continue;
            }

            String value = keyResponse.getDecodedValue();
            try {
                result.put(chainId, objectMapper.readValue(value, DeploymentRuntimeProperties.class));
            } catch (Exception e) {
                log.warn("Failed to deserialize runtime properties update for chain: {}, error: {}", chainId, e.getMessage());
                exception = true;
            }
        }

        if (exception) {
            throw new RuntimePropertiesException("Failed to deserialize consul response"
                                                 + " for one or more chains");
        }

        return result;
    }

    private @Nullable String parseChainId(KeyResponse k) {
        String[] keys = k.getKey().split("/");
        int keyIndex = getKeyIndex(keys, keyRuntimeConfigurations);
        int chainIdTargetIndex = keyIndex + 2;
        boolean keyIsValid = keyIndex != -1 && keys.length > chainIdTargetIndex && StringUtils.isNotEmpty(keys[chainIdTargetIndex]);
        return keyIsValid ? keys[chainIdTargetIndex] : null;
    }

    private int getKeyIndex(String[] keys, String targetKey) {
        int startIndex = -1;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (("/" + key).equals(targetKey)) {
                startIndex = i;
                break;
            }
        }
        return startIndex;
    }
}
