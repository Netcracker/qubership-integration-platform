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

package org.qubership.integration.platform.engine.configuration.camel.quartz;


import lombok.extern.slf4j.Slf4j;
import org.apache.camel.component.quartz.QuartzComponent;
import org.apache.camel.component.quartz.springboot.QuartzComponentConfiguration;
import org.apache.camel.spi.ComponentCustomizer;
import org.apache.camel.spring.boot.ComponentConfigurationProperties;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ComponentConfigurationProperties.class,
    QuartzComponentConfiguration.class})
public class CamelQuartzComponentCustomConfiguration {

    public static final String THREAD_POOL_COUNT_PROP = "org.quartz.threadPool.threadCount";

    private final QuartzSchedulerService quartzSchedulerService;

    @Value("${qip.camel.component.quartz.thread-pool-count}")
    private String threadPoolCount;

    @Autowired
    public CamelQuartzComponentCustomConfiguration(QuartzSchedulerService quartzSchedulerService) {
        this.quartzSchedulerService = quartzSchedulerService;
    }

    @Bean
    public ComponentCustomizer quartzComponentCustomizer() {
        return ComponentCustomizer.builder(QuartzComponent.class)
            .build((component) -> {
                component.setSchedulerFactory(quartzSchedulerService.getFactory());
                component.setScheduler(quartzSchedulerService.getFactory().getScheduler());
                component.setPrefixInstanceName(false);
                component.setEnableJmx(false);
                component.setProperties(Map.of(THREAD_POOL_COUNT_PROP, threadPoolCount));
                log.debug("Configure quartz component via component customizer: {}", component);
            });
    }
}
