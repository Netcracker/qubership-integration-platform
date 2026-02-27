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

package org.qubership.integration.platform.engine.service;

import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.catalog.RuntimeCatalogService;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeploymentsDTO;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentsUpdate;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DeploymentsUpdateServiceTest {

    @Mock
    IntegrationRuntimeService integrationRuntimeService;

    @Mock
    EngineInfo engineInfo;

    @RestClient
    @Inject
    @Mock
    RuntimeCatalogService runtimeCatalogService;

    @InjectMocks
    DeploymentsUpdateService service;

    @Test
    void shouldCallCatalogWithDomainAndExcludedWhenGetAndProcess() throws Exception {
        List<DeploymentInfo> excludedDeployments = List.of(mock(DeploymentInfo.class), mock(DeploymentInfo.class));
        DeploymentsUpdate update = mock(DeploymentsUpdate.class);

        when(integrationRuntimeService.buildExcludeDeploymentsMap()).thenReturn(excludedDeployments);
        when(engineInfo.getDomain()).thenReturn("test-domain");
        when(runtimeCatalogService.getDeploymentsUpdate(anyString(), any(EngineDeploymentsDTO.class))).thenReturn(update);

        ArgumentCaptor<EngineDeploymentsDTO> dtoCaptor = ArgumentCaptor.forClass(EngineDeploymentsDTO.class);

        service.getAndProcess();

        verify(runtimeCatalogService).getDeploymentsUpdate(eq("test-domain"), dtoCaptor.capture());
        EngineDeploymentsDTO captured = dtoCaptor.getValue();
        assertEquals(excludedDeployments, captured.getExcludeDeployments());

        verify(integrationRuntimeService).processAndUpdateState(same(update), eq(false));
    }

    @Test
    void shouldCallDependenciesInOrderWhenGetAndProcess() throws Exception {
        List<DeploymentInfo> excludedDeployments = List.of(mock(DeploymentInfo.class));
        DeploymentsUpdate update = mock(DeploymentsUpdate.class);

        when(integrationRuntimeService.buildExcludeDeploymentsMap()).thenReturn(excludedDeployments);
        when(engineInfo.getDomain()).thenReturn("domain");
        when(runtimeCatalogService.getDeploymentsUpdate(anyString(), any(EngineDeploymentsDTO.class))).thenReturn(update);

        InOrder inOrder = inOrder(integrationRuntimeService, engineInfo, runtimeCatalogService);

        service.getAndProcess();

        inOrder.verify(integrationRuntimeService).buildExcludeDeploymentsMap();
        inOrder.verify(engineInfo).getDomain();
        inOrder.verify(runtimeCatalogService).getDeploymentsUpdate(eq("domain"), any(EngineDeploymentsDTO.class));

        verify(integrationRuntimeService).processAndUpdateState(same(update), eq(false));
    }

    @Test
    void shouldPropagateExceptionWhenBuildExcludeDeploymentsMapThrows() throws Exception {
        Exception boom = new Exception("boom");
        when(integrationRuntimeService.buildExcludeDeploymentsMap()).thenThrow(boom);

        try {
            service.getAndProcess();
        } catch (Exception ex) {
            assertEquals(boom, ex);
        }

        verifyNoInteractions(engineInfo, runtimeCatalogService);
        verify(integrationRuntimeService, never()).processAndUpdateState(any(), anyBoolean());
    }

    @Test
    void shouldPropagateRuntimeExceptionWhenCatalogClientThrows() throws Exception {
        when(integrationRuntimeService.buildExcludeDeploymentsMap()).thenReturn(List.of(mock(DeploymentInfo.class)));
        when(engineInfo.getDomain()).thenReturn("domain");

        RuntimeException boom = new RuntimeException("catalog down");
        when(runtimeCatalogService.getDeploymentsUpdate(anyString(), any(EngineDeploymentsDTO.class))).thenThrow(boom);

        try {
            service.getAndProcess();
        } catch (RuntimeException ex) {
            assertEquals(boom, ex);
        }

        verify(integrationRuntimeService, never()).processAndUpdateState(any(), anyBoolean());
    }

    @Test
    void shouldPropagateExceptionWhenProcessAndUpdateStateThrows() throws Exception {
        when(integrationRuntimeService.buildExcludeDeploymentsMap()).thenReturn(List.of(mock(DeploymentInfo.class)));
        when(engineInfo.getDomain()).thenReturn("domain");

        DeploymentsUpdate update = mock(DeploymentsUpdate.class);
        when(runtimeCatalogService.getDeploymentsUpdate(anyString(), any(EngineDeploymentsDTO.class))).thenReturn(update);

        Exception boom = new Exception("processing failed");
        doThrow(boom).when(integrationRuntimeService).processAndUpdateState(same(update), eq(false));

        try {
            service.getAndProcess();
        } catch (Exception ex) {
            assertEquals(boom, ex);
        }
    }
}
