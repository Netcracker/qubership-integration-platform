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

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.service.debugger.tracing.MicrometerObservationTaggedTracer;
import org.qubership.integration.platform.engine.util.InjectUtil;

@Slf4j
@ApplicationScoped
public class TracingConfiguration {
    @Getter
    @ConfigProperty(name = "quarkus.otel.traces.enabled")
    boolean tracingEnabled;

    @Produces
    @Dependent
    MicrometerObservationTracer camelObservationTracer(
        Instance<Tracer> tracer,
        Instance<ObservationRegistry> observationRegistry
    ) {
        MicrometerObservationTaggedTracer micrometerObservationTracer = new MicrometerObservationTaggedTracer();
        InjectUtil.injectOptional(tracer).ifPresent(micrometerObservationTracer::setTracer);
        InjectUtil.injectOptional(observationRegistry).ifPresent(micrometerObservationTracer::setObservationRegistry);
        return micrometerObservationTracer;
    }
}
