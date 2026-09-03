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

package org.qubership.integration.platform.runtime.catalog.cr;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.services.ResourceBuildService;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainResourceBuildContextFactory.BuildContextWithObservations;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.BuiltResources;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService.ResourceKey;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link MicroDomainResourceBuildService}: it builds a context from the request through the
 * factory and hands it to the resource build service, passing the append flag through unchanged.
 */
@ExtendWith(MockitoExtension.class)
class MicroDomainResourceBuildServiceTest {

    @Mock
    private ResourceBuildService resourceBuildService;
    @Mock
    private MicroDomainResourceBuildContextFactory buildContextFactory;

    @Test
    @SuppressWarnings("unchecked")
    void buildResourcesBuildsAContextAndDelegatesToTheBuildService() {
        ResourceBuildRequest request = ResourceBuildRequest.builder()
                .options(ResourceBuildOptions.builder().build())
                .build();
        ResourceBuildContext<List<Snapshot>> context = mock(ResourceBuildContext.class);
        Map<ResourceKey, Optional<V1ObjectMeta>> observations = Map.of();
        BuildContextWithObservations built = new BuildContextWithObservations(context, observations);
        when(buildContextFactory.createResourceBuildContext(request, true)).thenReturn(built);
        when(resourceBuildService.buildResources(context)).thenReturn("resource-yaml");

        MicroDomainResourceBuildService service =
                new MicroDomainResourceBuildService(resourceBuildService, buildContextFactory);

        BuiltResources result = service.buildResources(request, true);
        assertThat(result.yaml()).isEqualTo("resource-yaml");
        assertThat(result.observations()).isEqualTo(observations);
        verify(buildContextFactory).createResourceBuildContext(request, true);
    }
}
