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

import jakarta.persistence.EntityNotFoundException;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.spi.InflightRepository;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTerminatedException;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.rest.v1.dto.LiveExchangeDTO;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class LiveExchangesServiceTest {

    @InjectMocks
    LiveExchangesService service;

    @Mock
    CamelContext camelContext;

    @Mock
    InflightRepository inflightRepository;

    @Mock
    ChainRuntimePropertiesService propertiesService;

    @Mock
    Exchange exchange;

    @Test
    void shouldBuildDtoWithDurationsWhenStartTimesPresent() {
        when(camelContext.getInflightRepository()).thenReturn(inflightRepository);

        InflightRepository.InflightExchange holder = mock(InflightRepository.InflightExchange.class);
        when(holder.getExchange()).thenReturn(exchange);

        when(inflightRepository.browse(eq(10), eq(true))).thenReturn(List.of(holder));

        when(exchange.getExchangeId()).thenReturn("ex-1");
        when(exchange.getProperty(CamelConstants.Properties.SESSION_ID, String.class)).thenReturn("sess-1");
        when(exchange.getProperty(CamelConstants.Properties.IS_MAIN_EXCHANGE, Boolean.class)).thenReturn(Boolean.TRUE);

        long startTime = System.currentTimeMillis() - 5_000;
        long exchangeStartTime = System.currentTimeMillis() - 2_000;

        when(exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class)).thenReturn(startTime);
        when(exchange.getProperty(CamelConstants.Properties.EXCHANGE_START_TIME_MS, Long.class)).thenReturn(exchangeStartTime);

        DeploymentInfo deploymentInfo = mock(DeploymentInfo.class);
        ChainInfo chainInfo = mock(ChainInfo.class);
        when(deploymentInfo.getId()).thenReturn("dep-1");
        when(chainInfo.getId()).thenReturn("chain-1");
        when(deploymentInfo.getChain()).thenReturn(chainInfo);

        ChainRuntimeProperties runtimeProperties = mock(ChainRuntimeProperties.class);
        when(runtimeProperties.calculateSessionLevel(exchange)).thenReturn(null);
        when(propertiesService.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            long before = System.currentTimeMillis();
            List<LiveExchangeDTO> result = service.getTopLiveExchanges(10);
            long after = System.currentTimeMillis();

            assertEquals(1, result.size());
            LiveExchangeDTO dto = result.get(0);

            assertEquals("ex-1", dto.getExchangeId());
            assertEquals("dep-1", dto.getDeploymentId());
            assertEquals("sess-1", dto.getSessionId());
            assertEquals("chain-1", dto.getChainId());
            assertEquals(startTime, dto.getSessionStartTime());
            assertEquals(Boolean.TRUE, dto.getMain());

            assertNotNull(dto.getSessionDuration());
            assertTrue(dto.getSessionDuration() >= (before - startTime));
            assertTrue(dto.getSessionDuration() <= (after - startTime));

            assertNotNull(dto.getDuration());
            assertTrue(dto.getDuration() >= (before - exchangeStartTime));
            assertTrue(dto.getDuration() <= (after - exchangeStartTime));
        }

        verify(inflightRepository).browse(eq(10), eq(true));
        verify(propertiesService).getRuntimeProperties(exchange);
    }

    @Test
    void shouldSetNullDurationsWhenStartTimesAbsent() {
        when(camelContext.getInflightRepository()).thenReturn(inflightRepository);

        InflightRepository.InflightExchange holder = mock(InflightRepository.InflightExchange.class);
        when(holder.getExchange()).thenReturn(exchange);

        when(inflightRepository.browse(eq(1), eq(true))).thenReturn(List.of(holder));

        when(exchange.getExchangeId()).thenReturn("ex-1");
        when(exchange.getProperty(CamelConstants.Properties.SESSION_ID, String.class)).thenReturn("sess-1");
        when(exchange.getProperty(CamelConstants.Properties.IS_MAIN_EXCHANGE, Boolean.class)).thenReturn(Boolean.FALSE);

        when(exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class)).thenReturn(null);
        when(exchange.getProperty(CamelConstants.Properties.EXCHANGE_START_TIME_MS, Long.class)).thenReturn(null);

        DeploymentInfo deploymentInfo = mock(DeploymentInfo.class);
        ChainInfo chainInfo = mock(ChainInfo.class);
        when(deploymentInfo.getId()).thenReturn("dep-1");
        when(chainInfo.getId()).thenReturn("chain-1");
        when(deploymentInfo.getChain()).thenReturn(chainInfo);

        ChainRuntimeProperties runtimeProperties = mock(ChainRuntimeProperties.class);
        when(runtimeProperties.calculateSessionLevel(exchange)).thenReturn(null);
        when(propertiesService.getRuntimeProperties(exchange)).thenReturn(runtimeProperties);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            List<LiveExchangeDTO> result = service.getTopLiveExchanges(1);

            assertEquals(1, result.size());
            LiveExchangeDTO dto = result.get(0);

            assertEquals("ex-1", dto.getExchangeId());
            assertEquals("dep-1", dto.getDeploymentId());
            assertEquals("sess-1", dto.getSessionId());
            assertEquals("chain-1", dto.getChainId());

            assertNull(dto.getSessionStartTime());
            assertNull(dto.getSessionDuration());
            assertNull(dto.getDuration());
            assertEquals(Boolean.FALSE, dto.getMain());
        }

        verify(inflightRepository).browse(eq(1), eq(true));
        verify(propertiesService).getRuntimeProperties(exchange);
    }

    @Test
    void shouldSetTerminatedExceptionWhenKillingExistingLiveExchange() {
        when(camelContext.getInflightRepository()).thenReturn(inflightRepository);

        when(exchange.getExchangeId()).thenReturn("ex-1");

        InflightRepository.InflightExchange holder = mock(InflightRepository.InflightExchange.class);
        when(holder.getExchange()).thenReturn(exchange);

        when(inflightRepository.browse()).thenReturn(List.of(holder));

        DeploymentInfo deploymentInfo = mock(DeploymentInfo.class);
        when(deploymentInfo.getId()).thenReturn("dep-1");

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            service.killLiveExchangeById("dep-1", "ex-1");
        }

        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(exchange).setException(captor.capture());

        Exception ex = captor.getValue();
        assertTrue(ex instanceof ChainExecutionTerminatedException);
        assertEquals("Chain was interrupted manually", ex.getMessage());
    }

    @Test
    void shouldThrowEntityNotFoundWhenNoLiveExchangeFound() {
        when(camelContext.getInflightRepository()).thenReturn(inflightRepository);

        InflightRepository.InflightExchange holder = mock(InflightRepository.InflightExchange.class);
        when(holder.getExchange()).thenReturn(exchange);

        when(inflightRepository.browse()).thenReturn(List.of(holder));

        DeploymentInfo deploymentInfo = mock(DeploymentInfo.class);
        when(deploymentInfo.getId()).thenReturn("dep-other");

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(exchange, DeploymentInfo.class)).thenReturn(deploymentInfo);

            EntityNotFoundException ex = assertThrows(
                    EntityNotFoundException.class,
                    () -> service.killLiveExchangeById("dep-777", "ex-1")
            );

            assertTrue(ex.getMessage().contains("dep-777"));
            verify(exchange, never()).setException(any());
        }
    }
}
