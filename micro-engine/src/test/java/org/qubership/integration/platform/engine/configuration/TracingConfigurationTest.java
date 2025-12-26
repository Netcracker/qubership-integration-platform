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
import jakarta.enterprise.inject.Instance;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TracingConfigurationTest {

    @Test
    void camelObservationTracerShouldWorkWhenOptionalsEmpty() {
        TracingConfiguration cfg = new TracingConfiguration();

        @SuppressWarnings("unchecked")
        Instance<Tracer> tracerInst = mock(Instance.class);
        @SuppressWarnings("unchecked")
        Instance<ObservationRegistry> regInst = mock(Instance.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class)) {
            inject.when(() -> InjectUtil.injectOptional(tracerInst)).thenReturn(Optional.empty());
            inject.when(() -> InjectUtil.injectOptional(regInst)).thenReturn(Optional.empty());

            MicrometerObservationTracer tracer = cfg.camelObservationTracer(tracerInst, regInst);
            assertNotNull(tracer);
        }
    }

    @Test
    void camelObservationTracerShouldWorkWhenOptionalsPresent() {
        TracingConfiguration cfg = new TracingConfiguration();

        @SuppressWarnings("unchecked")
        Instance<Tracer> tracerInst = mock(Instance.class);
        @SuppressWarnings("unchecked")
        Instance<ObservationRegistry> regInst = mock(Instance.class);

        Tracer tracer = mock(Tracer.class);
        ObservationRegistry reg = mock(ObservationRegistry.class);

        try (MockedStatic<InjectUtil> inject = mockStatic(InjectUtil.class)) {
            inject.when(() -> InjectUtil.injectOptional(tracerInst)).thenReturn(Optional.of(tracer));
            inject.when(() -> InjectUtil.injectOptional(regInst)).thenReturn(Optional.of(reg));

            MicrometerObservationTracer result = cfg.camelObservationTracer(tracerInst, regInst);
            assertNotNull(result);
        }
    }
}
