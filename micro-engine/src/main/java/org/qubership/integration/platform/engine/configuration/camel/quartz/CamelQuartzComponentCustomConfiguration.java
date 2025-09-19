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


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.component.quartz.QuartzComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.service.QuartzSchedulerService;

import java.util.Map;

@Slf4j
@ApplicationScoped
public class CamelQuartzComponentCustomConfiguration {
    public static final String THREAD_POOL_COUNT_PROP = "org.quartz.threadPool.threadCount";

    @Inject
    QuartzSchedulerService quartzSchedulerService;

    @ConfigProperty(name = "qip.camel.component.quartz.thread-pool-count")
    String threadPoolCount;

    @Produces
    public ComponentCustomizer quartzComponentCustomizer() {
        return ComponentCustomizer.builder(QuartzComponent.class)
            .build((component) -> {
                component.setSchedulerFactory(quartzSchedulerService.getFactory());
                component.setScheduler(quartzSchedulerService.getFactory().getScheduler());
                component.setPrefixInstanceName(false);
                component.setEnableJmx(false);
                // TODO [migration to quarkus] check thread pool configuration is set properly
                component.setProperties(Map.of(THREAD_POOL_COUNT_PROP, threadPoolCount));
                log.debug("Configure quartz component via component customizer: {}", component);
            });
    }
}
