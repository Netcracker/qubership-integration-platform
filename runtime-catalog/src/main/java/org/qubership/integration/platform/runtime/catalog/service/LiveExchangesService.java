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
import org.qubership.integration.platform.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.LiveExchangeDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.engine.LiveExchangeExtDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LiveExchangesService {
    private static final String SESSION_GET_URL = "http://%s:8080/v1/engine/live-exchanges?limit=%d";
    private static final String SESSION_DELETE_URL = "http://%s:8080/v1/engine/live-exchanges/%s/%s";

    private final RuntimeDeploymentService runtimeDeploymentService;
    private final RestTemplate restTemplateMs;
    private final ActionsLogService actionLogger;
    private final DeploymentService deploymentService;
    private final ChainService chainService;

    @Autowired
    public LiveExchangesService(RuntimeDeploymentService runtimeDeploymentService,
                                RestTemplate restTemplateMs,
                                ActionsLogService actionLogger,
                                DeploymentService deploymentService,
                                ChainService chainService) {
        this.runtimeDeploymentService = runtimeDeploymentService;
        this.restTemplateMs = restTemplateMs;
        this.actionLogger = actionLogger;
        this.deploymentService = deploymentService;
        this.chainService = chainService;
    }

    public List<LiveExchangeExtDTO> getTopLongLiveExchanges(int limit) {
        List<String> engineIps = runtimeDeploymentService.getEngineHosts().values().stream().flatMap(Collection::stream).toList();
        List<LiveExchangeExtDTO> result = engineIps.stream().parallel().map(ip -> {
            LiveExchangeDTO[] requestResult = null;
            try {
                requestResult = restTemplateMs.getForObject(String.format(SESSION_GET_URL, ip, limit), LiveExchangeDTO[].class);
            } catch (Exception e) {
                log.warn("Unable to retrieve live sessions from engine ip {}", ip, e);
            }
            return Optional.ofNullable(requestResult).map(l -> Arrays.stream(l)
                    .map(le -> new LiveExchangeExtDTO(le, ip))).orElse(null);
        }).filter(Objects::nonNull).flatMap(l -> l).toList();

        enrichResultWithChainName(result);


        return result;
    }

    private void enrichResultWithChainName(List<LiveExchangeExtDTO> result) {
        Map<String, String> idNameChainMap = chainService.findAllById(result.stream().map(LiveExchangeExtDTO::getChainId).toList()).stream().collect(Collectors.toMap(AbstractEntity::getId, AbstractEntity::getName));
        result.forEach(r -> r.setChainName(idNameChainMap.get(r.getChainId())));
    }

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    public void sendKillExchangeRequest(String podIp, String deploymentId, String exchangeId) {
        String domainName = null;
        try {
            domainName = deploymentService.findById(deploymentId).getDomain();
        } catch (Exception ignored) { }
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.EXCHANGE)
                .entityId(exchangeId)
                .entityName("Exchange from " + podIp)
                .parentType(EntityType.DEPLOYMENT)
                .parentId(deploymentId)
                .parentName(domainName)
                .operation(LogOperation.DELETE)
                .build());
        restTemplateMs.delete(String.format(SESSION_DELETE_URL, podIp, deploymentId, exchangeId));
    }
}
