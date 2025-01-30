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

package org.qubership.integration.platform.runtime.catalog.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ImportV2RedirectPathResolver;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;

@Slf4j
@Getter
@AutoConfiguration
@EnableScheduling
@EnableJpaAuditing
@EnableRetry
@EnableAsync
@ComponentScan(basePackages = {
        "org.qubership.integration.platform.catalog.*",
        "org.qubership.integration.platform.runtime.catalog.*"
})
public class ApplicationAutoConfiguration {
    private final String cloudServiceName;

    private final ApplicationContext context;

    @Value("${qip.gateway.egress.url}")
    private String gatewayUrl;

    @Value("${spring.profiles.active}")
    private String activeProfiles;

    @Autowired
    public ApplicationAutoConfiguration(@Value("${spring.application.cloud_service_name}") String cloudServiceName,
                                        ApplicationContext context) {
        this.cloudServiceName = cloudServiceName;
        this.context = context;
    }

    @PostConstruct
    private void showValues() {
        log.info("GATEWAY_URI: {}", gatewayUrl);
    }

    @Bean
    public DocumentBuilder documentBuilder() {
        try {
            return DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder();
        } catch (Exception e) {
            throw new BeanInitializationException("Can't provide document builder for " +
                    "building xml configurations");
        }
    }

    @Bean
    @ConditionalOnMissingBean(ImportV2RedirectPathResolver.class)
    public ImportV2RedirectPathResolver importV2RedirectPathResolver() {
        return (headers, internalPath) -> URI.create(internalPath);
    }


    public String getDeploymentName() {
        return cloudServiceName;
    }

    public void closeApplicationWithError() {
        SpringApplication.exit(context, () -> 1);
        System.exit(1);
    }
}
