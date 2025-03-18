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

package org.qubership.integration.platform.engine.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.events.ConsulSessionCreatedEvent;
import org.qubership.integration.platform.engine.model.consul.KeyResponse;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineState;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;
import org.qubership.integration.platform.engine.service.debugger.RuntimePropertiesException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;
import javax.annotation.Nullable;

@Slf4j
@Component
public class ConsulService {

    private static final String SESSION_PREFIX = "qip-engine-session-";
    public static final long SESSION_RENEW_DELAY = 30 * 1000;
    public static final String SESSION_TTL_STRING = "60s";
    private static final String WAIT_TIMEOUT_STRING = "20s";
    public static final String SESSION_BEHAVIOR = "delete";
    public static final String LOCALDEV_NODE_ID = "-" + UUID.randomUUID();

    public static final String DEFAULT_CONSUL_SETTING_KEY = "default-settings";

    @Value("${consul.keys.prefix}")
    private String keyPrefix;

    @Value("${consul.keys.engine-config-root}")
    private String keyEngineConfigRoot;

    @Value("${consul.keys.deployments-update}")
    private String keyDeploymentsUpdate;

    @Value("${consul.keys.libraries-update}")
    private String keyLibrariesUpdate;

    @Value("${consul.keys.engines-state}")
    private String keyEnginesState;

    @Value("${consul.keys.runtime-configurations}")
    private String keyRuntimeConfigurations;

    @Value("${consul.keys.chains}")
    private String keyChains;

    @Value("${consul.keys.common-variables-v2}")
    private String keyCommonVariablesV2;

    @Value("${consul.dynamic-state-keys.enabled:false}")
    private boolean dynamicStateKeys;

    private final String keyEngineName;

    private long deploymentsStatePreviousIndex = 0;
    private long deploymentsStateLastIndex = 0;


    private long librariesPreviousIndex = 0;
    private long librariesStateLastIndex = 0;

    private long chainsRuntimePropertiesPreviousIndex = 0;
    private long chainsRuntimePropertiesLastIndex = 0;

    private long commonVariablesPreviousIndex = 0;
    private long commonVariablesLastIndex = 0;


    @Getter
    @Nullable
    private volatile String activeSessionId = null;

    private String previousSessionId = null;

    private final ConsulClient client;
    private final ObjectMapper objectMapper;

    private final ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    public ConsulService(ConsulClient client, ServerConfiguration serverConfiguration,
        @Qualifier("jsonMapper") ObjectMapper objectMapper, ApplicationEventPublisher applicationEventPublisher) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;

        EngineInfo engineInfo = serverConfiguration.getEngineInfo();
        this.keyEngineName = "/" + engineInfo.getEngineDeploymentName() + "-"
                + engineInfo.getDomain() + "-" + engineInfo.getHost();
    }

    public synchronized void createOrRenewSession() {
        try {
            if (activeSessionId == null) {
                log.debug("Create consul session");
                if (previousSessionId != null) {
                    client.deleteSession(previousSessionId);
                    previousSessionId = null;
                }
                activeSessionId = client.createSession(SESSION_PREFIX + UUID.randomUUID(),
                    SESSION_BEHAVIOR, SESSION_TTL_STRING);
                applicationEventPublisher.publishEvent(new ConsulSessionCreatedEvent(this));
            } else {
                log.debug("Renew consul session");
                client.renewSession(activeSessionId);
            }
        } catch (Exception e) {
            log.error("Failed to create/renew consul session", e);
            previousSessionId = activeSessionId;
            activeSessionId = null;
        }
    }

    public void updateEnginesState(EngineState state) {
        log.debug("Update engines state");
        String sessionId = activeSessionId;
        if (sessionId != null) {
            String name = keyEngineName + (dynamicStateKeys ? LOCALDEV_NODE_ID : "");
            client.createOrUpdateKVWithSession(
                keyPrefix + keyEngineConfigRoot + keyEnginesState + name, state, sessionId);
        } else {
            throw new RuntimeException("Active consul session is not present");
        }
    }

    // return <index, timestamp>
    public Pair<Boolean, Long> waitForDeploymentsUpdate() throws KVNotFoundException {
        Pair<Long, List<KeyResponse>> pair =
            client.waitForKVChanges(keyPrefix + keyEngineConfigRoot + keyDeploymentsUpdate,
                false, deploymentsStateLastIndex, WAIT_TIMEOUT_STRING);
        boolean changesDetected = pair.getLeft() != deploymentsStateLastIndex;
        deploymentsStatePreviousIndex = deploymentsStateLastIndex;
        deploymentsStateLastIndex = pair.getLeft();

        return Pair.of(changesDetected, parseDeploymentsUpdate(pair));
    }

    public void rollbackDeploymentsStateLastIndex() {
        deploymentsStateLastIndex = deploymentsStatePreviousIndex;
    }

    private Long parseDeploymentsUpdate(Pair<Long, List<KeyResponse>> pair) {
        List<KeyResponse> response = pair.getRight();
        switch (response.size()) {
            case 0:
                return 0L;
            case 1:
                String value = response.get(0).getDecodedValue();
                return value == null ? 0L : Long.parseLong(value);
        }
        throw new RuntimeException("Failed to parse response, target key in consul has invalid format/size: " + response);
    }


    // return <index, timestamp>
    public Pair<Boolean, List<CompiledLibraryUpdate>> waitForLibrariesUpdate()
        throws KVNotFoundException, JsonProcessingException {
        Pair<Long, List<KeyResponse>> pair =
            client.waitForKVChanges(keyPrefix + keyEngineConfigRoot + keyLibrariesUpdate,
                false, librariesStateLastIndex, WAIT_TIMEOUT_STRING);
        boolean changesDetected = pair.getLeft() != librariesStateLastIndex;
        librariesPreviousIndex = librariesStateLastIndex;
        librariesStateLastIndex = pair.getLeft();

        return Pair.of(changesDetected, parseLibrariesUpdate(pair));
    }

    public void rollbackLibrariesLastIndex() {
        librariesStateLastIndex = librariesPreviousIndex;
    }

    private List<CompiledLibraryUpdate> parseLibrariesUpdate(Pair<Long, List<KeyResponse>> pair)
        throws JsonProcessingException {
        List<KeyResponse> response = pair.getRight();
        switch (response.size()) {
            case 0:
                return Collections.emptyList();
            case 1:
                String json = response.get(0).getDecodedValue();
                return json == null
                        ? Collections.emptyList()
                        : objectMapper.readValue(json, new TypeReference<>() {});
        }
        throw new RuntimeException("Failed to parse response, target key in consul has invalid format/size: " + response);
    }

    /**
     * @return [changes_detected, [chainId, properties]] map
     */
    public Pair<Boolean, Map<String, DeploymentRuntimeProperties>> waitForChainRuntimeConfig()
        throws KVNotFoundException {
        Pair<Long, List<KeyResponse>> pair =
            client.waitForKVChanges(keyPrefix + keyEngineConfigRoot + keyRuntimeConfigurations + keyChains,
                false, chainsRuntimePropertiesLastIndex, WAIT_TIMEOUT_STRING);

        boolean changesDetected = pair.getLeft() != chainsRuntimePropertiesLastIndex;
        chainsRuntimePropertiesPreviousIndex = chainsRuntimePropertiesLastIndex;
        chainsRuntimePropertiesLastIndex = pair.getLeft();

        return Pair.of(changesDetected, parseChainsRuntimeConfig(pair));
    }

    // return Map<chainId, props>
    private Map<String, DeploymentRuntimeProperties> parseChainsRuntimeConfig(Pair<Long, List<KeyResponse>> pair)
        throws RuntimePropertiesException {
        List<KeyResponse> response = pair.getRight();
        if (response.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, DeploymentRuntimeProperties> result = new HashMap<>();
        boolean exception = false;
        for (KeyResponse keyResponse : response) {
           String chainId = parseChainId(keyResponse);
            if (chainId == null) {
                throw new RuntimePropertiesException("Failed to parse response, invalid 'key' field: "
                    + keyResponse.getKey());
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

    public void rollbackChainsRuntimeConfigLastIndex() {
        chainsRuntimePropertiesLastIndex = chainsRuntimePropertiesPreviousIndex;
    }

    /**
     * @return [changes_detected, [key, value]] map
     */
    public Pair<Boolean, Map<String, String>> waitForCommonVariables()
            throws KVNotFoundException {
        String keyPrefix = this.keyPrefix + keyEngineConfigRoot + keyCommonVariablesV2;
        Pair<Long, List<KeyResponse>> pair =
                client.waitForKVChanges(keyPrefix,
                        false, commonVariablesLastIndex, WAIT_TIMEOUT_STRING);


        boolean changesDetected = pair.getLeft() != commonVariablesLastIndex;
        commonVariablesPreviousIndex = commonVariablesLastIndex;
        commonVariablesLastIndex = pair.getLeft();

        return Pair.of(changesDetected, parseCommonVariables(
            pair.getRight().stream()
                .filter(keyResponse -> filterL1NonEmptyPaths(keyPrefix, keyResponse.getKey())).toList()));
    }

    public void rollbackCommonVariablesLastIndex() {
        commonVariablesLastIndex = commonVariablesPreviousIndex;
    }

    private Map<String, String> parseCommonVariables(List<KeyResponse> responses) {
        Map<String, String> variables = new HashMap<>();
        for (KeyResponse response : responses) {
            Pair<String, String> variable = parseCommonVariable(response);
            if (variable != null) {
                variables.put(variable.getKey(), variable.getValue() != null ? variable.getValue() : "");
            } else {
                log.warn("Can't parse common variable from response: {}", response);
            }
        }

        return variables;
    }

    /**
     * Get last path word as a key and decode value
     *
     * @return key and value
     */
    private Pair<String, String> parseCommonVariable(KeyResponse k) {
        String[] split = k.getKey().split("/");
        return split.length > 0 ? Pair.of(split[split.length - 1], k.getDecodedValue()) : null;
    }

    private String parseChainId(KeyResponse k) {
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

    private static boolean filterL1NonEmptyPaths(String pathPrefix, String path) {
        String[] split = path.substring(pathPrefix.length()).split("/");
        return split.length == 1 && StringUtils.isNotEmpty(split[0]);
    }
}
