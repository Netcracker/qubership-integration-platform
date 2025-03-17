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

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeploymentsDTO;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentsUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Component
public class DeploymentsUpdateService {

    public static final String DEPLOYMENTS_UPDATE_PATH = "/v1/catalog/domains/{domain}/deployments/update";

    private final IntegrationRuntimeService integrationRuntimeService;
    private final ServerConfiguration serverConfiguration;
    private final RestTemplate restTemplate;

    @Value("${qip.internal-services.runtime-catalog.url}")
    private String runtimeCatalogUrl;

    @Autowired
    public DeploymentsUpdateService(IntegrationRuntimeService integrationRuntimeService,
                                    ServerConfiguration serverConfiguration,
                                    @Qualifier("restTemplateMS") RestTemplate restTemplate) {
        this.integrationRuntimeService = integrationRuntimeService;
        this.serverConfiguration = serverConfiguration;
        this.restTemplate = restTemplate;
    }


    public void getAndProcess() throws ExecutionException, InterruptedException {
        // pull updates from runtime catalog
        List<DeploymentInfo> excludeDeploymentsMap = integrationRuntimeService.buildExcludeDeploymentsMap();
        EngineDeploymentsDTO excluded = EngineDeploymentsDTO.builder()
            .excludeDeployments(excludeDeploymentsMap).build();
        DeploymentsUpdate update = getDeploymentsUpdate(excluded);

        log.info("Processing of new deployments has started");
        // process deployments and update state
        integrationRuntimeService.processAndUpdateState(update, false);
        log.info("Processing of new deployments completed");
    }

    private DeploymentsUpdate getDeploymentsUpdate(EngineDeploymentsDTO excluded) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<DeploymentsUpdate> response = restTemplate.exchange(
            runtimeCatalogUrl + DEPLOYMENTS_UPDATE_PATH,
            HttpMethod.POST, new HttpEntity<>(excluded, headers), DeploymentsUpdate.class,
            Map.of("domain", serverConfiguration.getDomain()));

        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("Failed to get deployments update from runtime catalog, code: {}, body: {}",
                response.getStatusCode(), response.getBody());
            throw new RuntimeException(
                "Failed to get deployments update from runtime catalog, response with non 2xx code");
        }

        return response.getBody();
    }
}
