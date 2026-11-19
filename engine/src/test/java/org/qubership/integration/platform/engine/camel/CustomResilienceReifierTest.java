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

package org.qubership.integration.platform.engine.camel;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.TypeConverter;
import org.apache.camel.component.resilience4j.ResilienceProcessor;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.model.Resilience4jConfigurationDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomResilienceReifierTest {

    @Test
    void keepsTheProductSecondsDefaultsWhenDurationsAreOmitted() throws Exception {
        CircuitBreakerDefinition definition = definition();
        CustomResilienceReifier reifier = reifier(definition);
        CircuitBreakerConfig camelDefaults = CircuitBreakerConfig.custom()
                .waitDurationInOpenState(Duration.ofMillis(60_000))
                .slowCallDurationThreshold(Duration.ofMillis(60_000))
                .build();
        ResilienceProcessor processor = processor(camelDefaults);

        CircuitBreakerConfig actual = reifier.getCircuitBreakerConfig(processor);

        assertEquals(60_000L, waitDurationMillis(actual));
        assertEquals(Duration.ofSeconds(60), actual.getSlowCallDurationThreshold());
    }

    @Test
    void keepsConfiguredDurationsInSeconds() throws Exception {
        CircuitBreakerDefinition definition = definition();
        definition.getResilience4jConfiguration()
                .waitDurationInOpenState("60")
                .slowCallDurationThreshold("60");
        CustomResilienceReifier reifier = reifier(definition);
        CircuitBreakerConfig camelDurations = CircuitBreakerConfig.custom()
                .waitDurationInOpenState(Duration.ofMillis(60))
                .slowCallDurationThreshold(Duration.ofMillis(60))
                .build();
        ResilienceProcessor processor = processor(camelDurations);

        CircuitBreakerConfig actual = reifier.getCircuitBreakerConfig(processor);

        assertEquals(60_000L, waitDurationMillis(actual));
        assertEquals(Duration.ofSeconds(60), actual.getSlowCallDurationThreshold());
    }

    private static CircuitBreakerDefinition definition() {
        CircuitBreakerDefinition definition = new CircuitBreakerDefinition();
        definition.setResilience4jConfiguration(new Resilience4jConfigurationDefinition(definition));
        return definition;
    }

    private static CustomResilienceReifier reifier(CircuitBreakerDefinition definition) throws Exception {
        Route route = mock(Route.class);
        CamelContext camelContext = mock(CamelContext.class);
        TypeConverter typeConverter = mock(TypeConverter.class);
        when(route.getCamelContext()).thenReturn(camelContext);
        when(camelContext.resolvePropertyPlaceholders(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(camelContext.getTypeConverter()).thenReturn(typeConverter);
        when(typeConverter.mandatoryConvertTo(Long.class, "60")).thenReturn(60L);
        return new CustomResilienceReifier(route, definition);
    }

    private static ResilienceProcessor processor(CircuitBreakerConfig circuitBreakerConfig) {
        return new ResilienceProcessor(circuitBreakerConfig, null, null, null, null, false, null, null);
    }

    private static long waitDurationMillis(CircuitBreakerConfig circuitBreakerConfig) {
        return circuitBreakerConfig.getWaitIntervalFunctionInOpenState().apply(1);
    }
}
