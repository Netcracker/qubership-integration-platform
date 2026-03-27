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
import org.qubership.integration.platform.runtime.catalog.model.ElementRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ElementValidationService {

    private final DeploymentService deploymentService;

    @Autowired
    public ElementValidationService(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    public boolean checkRouteExists(ElementRoute route, String excludeChainId) {
        return deploymentService.checkRouteExists(route, excludeChainId);
    }

    public List<Pair<String, Deployment>> findRouteDeployments(ElementRoute route, String excludeChainId) {
        return deploymentService.findRouteDeployments(route, excludeChainId);
    }
}
