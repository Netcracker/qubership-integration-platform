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
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.engine.catalog.RuntimeCatalogService;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeploymentsDTO;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentsUpdate;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ExecutionException;

@Slf4j
@ApplicationScoped
public class DeploymentsUpdateService {
    @Inject
    IntegrationRuntimeService integrationRuntimeService;

    @Inject
    ServerConfiguration serverConfiguration;

    @RestClient
    @Inject
    RuntimeCatalogService runtimeCatalogService;

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
        String domain = serverConfiguration.getDomain();
        return runtimeCatalogService.getDeploymentsUpdate(domain, excluded);
    }
}
