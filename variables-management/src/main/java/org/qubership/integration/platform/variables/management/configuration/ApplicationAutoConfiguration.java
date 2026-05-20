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

package org.qubership.integration.platform.variables.management.configuration;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;


@Slf4j
@Getter
@EnableRetry
@EnableAsync
@AutoConfiguration
@EnableScheduling
@EnableJpaAuditing
@ComponentScan(value = "org.qubership.integration.platform.variables.management")
public class ApplicationAutoConfiguration {
    private final String cloudServiceName;
    private final String namespace;

    @Autowired
    public ApplicationAutoConfiguration(@Value("${spring.application.cloud_service_name}") String cloudServiceName,
                                        @Value("${cloud.microservice.namespace}") String namespace) {
        this.cloudServiceName = cloudServiceName;
        this.namespace = namespace;
    }

    public String getDeploymentName() {
        return cloudServiceName;
    }
}
