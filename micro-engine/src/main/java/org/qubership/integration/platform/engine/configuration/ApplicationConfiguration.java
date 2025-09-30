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

package org.qubership.integration.platform.engine.configuration;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
@Getter
@ApplicationScoped
public class ApplicationConfiguration {
    private final String microserviceName;
    private final String cloudServiceName;

    public ApplicationConfiguration(
            @ConfigProperty(name = "application.cloud_service_name") String cloudServiceName,
            @ConfigProperty(name = "application.name") String microserviceName
    ) {
        this.microserviceName = microserviceName;
        this.cloudServiceName = cloudServiceName;
    }

    public String getDeploymentName() {
        return cloudServiceName;
    }
}
