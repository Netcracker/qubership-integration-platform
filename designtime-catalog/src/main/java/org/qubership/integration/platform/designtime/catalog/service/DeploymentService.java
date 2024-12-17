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

package org.qubership.integration.platform.designtime.catalog.service;

import org.qubership.integration.platform.catalog.model.deployment.engine.ChainRuntimeDeployment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.RequestEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.Map;

@Service
public class DeploymentService {

    private final RestTemplate restTemplateMS;

    private String runtimeCatalogUrl;

    @Autowired
    public DeploymentService(RestTemplate restTemplateMS,
                             @Value("${qip.internal-services.runtime-catalog}") String runtimeCatalogUrl) {
        this.restTemplateMS = restTemplateMS;
        this.runtimeCatalogUrl = "http://" + runtimeCatalogUrl + ":8080";
    }

    public void deleteAllByChainId(String id) {
        restTemplateMS.delete(String.format("%s/v1/catalog/chains/%s/deployments", runtimeCatalogUrl, id));
    }

    /*
    * Get all runtime deployments
    *
    * Format: <chainId, List<ChainRuntimeDeployment>>
    * */
    public Map<String, Collection<ChainRuntimeDeployment>> getAllRuntimeDeployments() {
        ParameterizedTypeReference<Map<String, Collection<ChainRuntimeDeployment>>> responseType =
                new ParameterizedTypeReference<>() {};
        RequestEntity<Void> request = RequestEntity.get(
                String.format("%s/v1/catalog/runtime-deployments", runtimeCatalogUrl)).build();
        return restTemplateMS.exchange(request, responseType).getBody();
    }
}
