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

import org.qubership.integration.platform.engine.service.debugger.tracing.MicrometerObservationTaggedTracer;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.apache.camel.observation.starter.ObservationAutoConfiguration;
import org.apache.camel.observation.starter.ObservationConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Slf4j
@Configuration
@EnableConfigurationProperties(ObservationConfigurationProperties.class)
public class TracingConfiguration {

    @Getter
    @Value("${management.tracing.enabled}")
    private boolean tracingEnabled;

    /**
     * Based on {@link ObservationAutoConfiguration}
     */
    @Bean(initMethod = "", destroyMethod = "")
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    MicrometerObservationTracer camelObservationTracer(
        ObservationConfigurationProperties config,
        ObjectProvider<Tracer> tracer,
        ObjectProvider<ObservationRegistry> observationRegistry
    ) {
        MicrometerObservationTaggedTracer micrometerObservationTracer = new MicrometerObservationTaggedTracer();
        tracer.ifAvailable(micrometerObservationTracer::setTracer);
        observationRegistry.ifAvailable(micrometerObservationTracer::setObservationRegistry);

        if (config.getExcludePatterns() != null) {
            micrometerObservationTracer.setExcludePatterns(config.getExcludePatterns());
        }
        if (config.getEncoding() != null) {
            micrometerObservationTracer.setEncoding(config.getEncoding());
        }

        return micrometerObservationTracer;
    }
}
