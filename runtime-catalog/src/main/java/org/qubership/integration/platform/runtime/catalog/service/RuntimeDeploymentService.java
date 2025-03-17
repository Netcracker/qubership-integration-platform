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

package org.qubership.integration.platform.runtime.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.catalog.consul.exception.KVNotFoundException;
import org.qubership.integration.platform.catalog.model.deployment.engine.*;
import org.qubership.integration.platform.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.catalog.persistence.configs.entity.User;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.events.EngineStateUpdateEvent;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RuntimeDeployment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.DeploymentRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RuntimeDeploymentService {

    private final TransactionHandler transactionHandler;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentMapper deploymentMapper;

    // <engine_host, state>
    private final AtomicReference<Map<String, EngineState>> enginesStateCache = new AtomicReference<>(new HashMap<>());

    @Autowired
    public RuntimeDeploymentService(TransactionHandler transactionHandler,
                                    ApplicationEventPublisher applicationEventPublisher,
                                    DeploymentRepository deploymentRepository,
                                    @Lazy DeploymentMapper deploymentMapper) {
        this.transactionHandler = transactionHandler;
        this.applicationEventPublisher = applicationEventPublisher;
        this.deploymentRepository = deploymentRepository;
        this.deploymentMapper = deploymentMapper;
    }

    // <chainId, List<ChainRuntimeDeployment>>
    public Map<String, Collection<ChainRuntimeDeployment>> getChainRuntimeDeployments() {
        Map<String, Collection<ChainRuntimeDeployment>> result = new HashMap<>();
        Collection<EngineState> states = enginesStateCache.get().values();
        for (EngineState engineState : states) {
            Map<String, EngineDeployment> deployments = engineState.getDeployments();
            if (deployments != null) {
                for (EngineDeployment engineDeployment : deployments.values()) {
                    result.computeIfAbsent(engineDeployment.getDeploymentInfo().getChainId(), k -> new ArrayList<>())
                            .add(deploymentMapper.toChainRuntimeDeployment(engineDeployment, engineState.getEngine().getHost()));
                }
            }
        }
        return result;
    }

    public Collection<EngineDeployment> findRuntimeDeployments(String engineHost) {
        try {
            Map<String, EngineState> states = enginesStateCache.get();
            if (states.containsKey(engineHost)) {
                return states.get(engineHost).getDeployments().values();
            }
        } catch (KVNotFoundException kvnfe) {
            log.warn("Engines state KV is empty. {}", kvnfe.getMessage());
        }
        return Collections.emptyList();
    }

    public Map<String, List<String>> getEngineHosts() {
        List<EngineState> engineStates = enginesStateCache.get().values().stream().toList();
        Map<String, List<String>> hosts = new HashMap<>();

        for (EngineState engineState : engineStates) {
            if (hosts.get(engineState.getEngine().getDomain()) != null) {
                hosts.get(engineState.getEngine().getDomain()).add(engineState.getEngine().getHost());
            } else {
                hosts.put(engineState.getEngine().getDomain(), new ArrayList<>(Collections.singletonList(engineState.getEngine().getHost())));
            }
        }

        return hosts;
    }

    public RuntimeDeployment getRuntimeDeployment(String deploymentId) {
        RuntimeDeployment runtimeDeployment = new RuntimeDeployment(deploymentId);
        Collection<EngineState> states = enginesStateCache.get().values();
        for (EngineState engineState : states) {
            runtimeDeployment.setServiceName(engineState.getEngine().getEngineDeploymentName());
            runtimeDeployment.getStates().put(
                    engineState.getEngine().getHost(),
                    Optional.ofNullable(engineState.getDeployments()).map(deployments -> deployments.get(deploymentId)).orElse(null)
            );
        }

        return runtimeDeployment;
    }

    public void provideEnginesStateUpdate(Collection<EngineState> newStateList) {

        Map<String, EngineState> stateMap = remapEngineStatesForCache(newStateList);
        Map<String, Pair<EngineInfo, EngineDeployment>> newState = remapEngineStatesForCompare(stateMap);
        Map<String, Pair<EngineInfo, EngineDeployment>> oldState = remapEngineStatesForCompare(enginesStateCache.getAndSet(stateMap));

        // calculate state delta
        List<Pair<EngineInfo, EngineDeployment>> deploymentsDelta = new ArrayList<>();
        Set<String> newStateKeys = newState.keySet();
        Set<String> oldStateKeys = oldState.keySet();

        Set<String> removed = new HashSet<>(oldStateKeys);
        removed.removeAll(newStateKeys);
        for (String key : removed) {
            Pair<EngineInfo, EngineDeployment> pair = oldState.get(key);
            pair.getRight().setStatus(DeploymentStatus.REMOVED);
            deploymentsDelta.add(pair);
        }

        Set<String> added = new HashSet<>(newStateKeys);
        added.removeAll(oldStateKeys);
        for (String key : added) {
            deploymentsDelta.add(newState.get(key));
        }

        Set<String> intersection = new HashSet<>(newStateKeys);
        intersection.retainAll(oldStateKeys);
        for (String key : intersection) {
            Pair<EngineInfo, EngineDeployment> newPair = newState.get(key);
            Pair<EngineInfo, EngineDeployment> oldPair = oldState.get(key);

            if (oldPair.getRight().getStatus() != newPair.getRight().getStatus()) {
                deploymentsDelta.add(newPair);
            }
        }

        // send events for UI
        for (Pair<EngineInfo, EngineDeployment> deploymentPair : deploymentsDelta) {
            String userId = null;
            Optional<Deployment> deploymentOptional =
                    deploymentRepository.findById(deploymentPair.getRight().getDeploymentInfo().getDeploymentId());
            if (deploymentOptional.isPresent()) {
                User createdBy = deploymentOptional.get().getCreatedBy();
                userId = createdBy == null ? null : createdBy.getId();
            }

            applicationEventPublisher.publishEvent(
                    new EngineStateUpdateEvent(
                            this,
                            deploymentPair.getLeft(),
                            deploymentPair.getRight(),
                            new DeploymentService.LoggingInfo(deploymentOptional),
                            userId,
                            null));
        }

        // merge states
        Map<String, EngineDeployment> mergedDeployments = new HashMap<>(); // <deploymentId, deployment>
        for (EngineState report : newStateList) {
            mergedDeployments.putAll(report.getDeployments());
        }

        // remove obsolete deployments
        Set<String> deployed = new HashSet<>();
        Set<String> notDeployed = new HashSet<>();
        for (Map.Entry<String, EngineDeployment> entry : mergedDeployments.entrySet()) {
            switch (entry.getValue().getStatus()) {
                case DEPLOYED -> deployed.add(entry.getKey());
                case PROCESSING, FAILED -> notDeployed.add(entry.getKey());
            }
        }

        transactionHandler.runInTransaction(() -> {
            deploymentRepository.deleteObsoleteDeployments(deployed, notDeployed);
        });
    }

    // key = engine_host + deployment_id
    private Map<String, Pair<EngineInfo, EngineDeployment>> remapEngineStatesForCompare(Map<String, EngineState> stateMap) {
        Map<String, Pair<EngineInfo, EngineDeployment>> result = new HashMap<>();
        for (EngineState state : stateMap.values()) {
            EngineInfo engine = state.getEngine();
            String host = engine.getHost();
            for (Map.Entry<String, EngineDeployment> deploymentEntry : state.getDeployments().entrySet()) {
                EngineDeployment deployment = deploymentEntry.getValue();
                String deploymentId = deployment.getDeploymentInfo().getDeploymentId();
                result.put(host + deploymentId, Pair.of(engine, deployment));
            }
        }
        return result;
    }

    @NotNull
    private static Map<String, EngineState> remapEngineStatesForCache(Collection<EngineState> newState) {
        return newState.stream()
                .collect(Collectors.toMap(state -> state.getEngine().getHost(), Function.identity(), (s1, s2) -> s1));
    }
}
