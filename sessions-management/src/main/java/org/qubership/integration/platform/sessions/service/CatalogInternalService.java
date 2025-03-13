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

package org.qubership.integration.platform.sessions.service;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.sessions.properties.InternalServicesProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class CatalogInternalService {

    private static final String CATALOG_REST_PROTOCOL = "http";
    private static final String CATALOG_PORT = "8080";
    private static final String CATALOG_GET_CHAINS_NAMES = "/v1/chains/names?chainIds={chainIds}";
    private final String catalogUrl;

    private final RestTemplate restTemplateMS;

    public CatalogInternalService(
        RestTemplate restTemplateMS,
        InternalServicesProperties internalServicesProperties
    ) {
        this.restTemplateMS = restTemplateMS;
        this.catalogUrl = CATALOG_REST_PROTOCOL + "://" + internalServicesProperties.designtimeCatalog() + ":" + CATALOG_PORT;
    }


    public Map<String, String> getChainsNames(Set<String> chainIds) {
        String address = catalogUrl + CATALOG_GET_CHAINS_NAMES;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Set<String>> entity = new HttpEntity<>(null, headers);

        ResponseEntity<Map<String, String>> response =
            restTemplateMS.exchange(address, HttpMethod.GET, entity,
                new ParameterizedTypeReference<>() {
                }, String.join(",", chainIds));

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.error(
                "Failed to request chains names from catalog, response with non 2xx code: {}, {}",
                response.getStatusCode(), response.getBody());
            throw new RuntimeException(
                "Failed to request chains names from catalog, response with non 2xx code");
        }

        return response.getBody();
    }
}
