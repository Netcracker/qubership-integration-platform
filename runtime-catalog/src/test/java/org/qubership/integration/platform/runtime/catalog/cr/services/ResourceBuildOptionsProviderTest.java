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

package org.qubership.integration.platform.runtime.catalog.cr.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.cr.model.ResourceBuildOptionsCustomizer;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceDeployRequest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers {@link ResourceBuildOptionsProvider#getOptions}: the configured scalar values reach the
 * built options, the environment map is merged with the monitoring and default-secret flags, and
 * every registered customizer runs against the request and the built options.
 */
class ResourceBuildOptionsProviderTest {

    private ResourceBuildOptionsProvider provider(List<ResourceBuildOptionsCustomizer> customizers) {
        ResourceBuildOptionsProvider provider = new ResourceBuildOptionsProvider(new MockEnvironment(), customizers);
        ReflectionTestUtils.setField(provider, "namespace", "team-namespace");
        ReflectionTestUtils.setField(provider, "replicas", 3);
        ReflectionTestUtils.setField(provider, "serviceAccount", "build-runner");
        ReflectionTestUtils.setField(provider, "monitoringEnabled", true);
        ReflectionTestUtils.setField(provider, "defaultSecretEnabled", true);
        ReflectionTestUtils.setField(provider, "environment", Map.of("LOG_LEVEL", "DEBUG"));
        return provider;
    }

    @Test
    void getOptionsCopiesConfiguredValuesAndMergesTheEnvironment() {
        ResourceDeployRequest request = ResourceDeployRequest.builder().name("orders-service").build();

        ResourceBuildOptions options = provider(List.of()).getOptions(request);

        assertThat(options.getName()).isEqualTo("orders-service");
        assertThat(options.getNamespace()).isEqualTo("team-namespace");
        assertThat(options.getReplicas()).isEqualTo(3);
        assertThat(options.getServiceAccount()).isEqualTo("build-runner");
        assertThat(options.getIntegrations().isCamelKSourcesUtilized()).isFalse();
        assertThat(options.getContainer()).isNotNull();
        assertThat(options.getJvm()).isNotNull();
        assertThat(options.getMount()).isNotNull();
        assertThat(options.getEnvironment())
                .containsEntry("LOG_LEVEL", "DEBUG")
                .containsEntry("MONITORING_ENABLED", "true")
                .containsEntry("DEFAULT_SECRET_ENABLED", "true");
    }

    @Test
    void getOptionsRunsEveryCustomizerAgainstTheRequestAndOptions() {
        ResourceBuildOptionsCustomizer customizer = mock(ResourceBuildOptionsCustomizer.class);
        ResourceDeployRequest request = ResourceDeployRequest.builder().name("orders-service").build();

        ResourceBuildOptions options = provider(List.of(customizer)).getOptions(request);

        verify(customizer).customize(eq(request), any(ResourceBuildOptions.class));
        assertThat(options).isNotNull();
    }
}
