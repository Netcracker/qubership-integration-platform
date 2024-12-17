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

package org.qubership.integration.platform.engine.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EngineDomainUtils {

    @Value("${spring.application.default_integration_domain_name}")
    private String engineDefaultDomain;

    @Value("${spring.application.default_integration_domain_microservice_name}")
    private String defaultEngineMicroserviceName;

    @Autowired
    public EngineDomainUtils() {
    }

    public String extractEngineDomain(String microserviceName) {
        boolean isDefault = defaultEngineMicroserviceName.equals(microserviceName);

        return isDefault ?
            engineDefaultDomain :
            microserviceName;
    }
}
