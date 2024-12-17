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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@Getter
@Configuration
@EnableScheduling
@EnableRetry
@EnableAsync
@ComponentScan(value = {"org.qubership.integration.platform.engine"})
public class ApplicationConfiguration {
    private final ApplicationContext context;

    private final String microserviceName;
    private final String cloudServiceName;

    public ApplicationConfiguration(ApplicationContext context, @Value("${spring.application.cloud_service_name}") String cloudServiceName,
                                    @Value("${spring.application.name}") String microserviceName) {
        this.context = context;
        this.microserviceName = microserviceName;
        this.cloudServiceName = cloudServiceName;
    }

    public String getDeploymentName() {
        return cloudServiceName;
    }

    public void closeApplicationWithError() {
        SpringApplication.exit(context, () -> 1);
        System.exit(1);
    }
}
