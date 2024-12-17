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

package org.qubership.integration.platform.designtime.catalog.configuration;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.designtime.catalog.service.ddsgenerator.elements.converter.RoutePrefixProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@Getter
@Configuration
@NoArgsConstructor
@EnableScheduling
@EnableJpaAuditing
@ComponentScan(basePackages = {
        "org.qubership.integration.platform.designtime.catalog.*",
        "org.qubership.integration.platform.catalog.*"
})
public class ApplicationConfiguration {

    @Value("${spring.application.cloud_service_name}")
    private String cloudServiceName;

    public String getDeploymentName() {
        return cloudServiceName;
    }

    @Bean
    @ConditionalOnMissingBean(RoutePrefixProvider.class)
    public RoutePrefixProvider routePrefixProvider() {
        return external -> RoutePrefixProvider.INTERNAL_ROUTE_PREFIX;
    }
}
