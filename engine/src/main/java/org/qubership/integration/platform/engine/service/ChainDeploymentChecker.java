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

import lombok.RequiredArgsConstructor;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static java.util.Objects.isNull;

@Component
@RequiredArgsConstructor
public class ChainDeploymentChecker {

    private final IntegrationRuntimeService integrationRuntimeService;

    public boolean isChainDeployed(String chainId) {
        if (isNull(chainId)) {
            return false;
        }
        return integrationRuntimeService.getCache().getDeployments().values().stream()
                .map(EngineDeployment::getDeploymentInfo)
                .filter(Objects::nonNull)
                .map(DeploymentInfo::getChainId)
                .anyMatch(chainId::equals);
    }
}
