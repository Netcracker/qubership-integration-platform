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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.spi.InflightRepository;
import org.qubership.integration.platform.engine.camel.metadata.Metadata;
import org.qubership.integration.platform.engine.camel.metadata.MetadataService;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTerminatedException;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.rest.v1.dto.LiveExchangeDTO;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@ApplicationScoped
public class LiveExchangesService {
    private final CamelDebuggerPropertiesService propertiesService;
    private final CamelContext camelContext;
    private final MetadataService metadataService;

    @Inject
    public LiveExchangesService(
            CamelContext camelContext,
            MetadataService metadataService,
            CamelDebuggerPropertiesService propertiesService
    ) {
        this.camelContext = camelContext;
        this.metadataService = metadataService;
        this.propertiesService = propertiesService;
    }

    public List<LiveExchangeDTO> getTopLiveExchanges(int amount) {
        List<LiveExchangeDTO> result = new ArrayList<>();

        List<InflightRepository.InflightExchange> exchangeHolders = camelContext.getInflightRepository()
                .browse(amount, true).stream().toList();

        for (InflightRepository.InflightExchange exchangeHolder : exchangeHolders) {
            Exchange exchange = exchangeHolder.getExchange();
            String deploymentId = metadataService.getMetadata(exchange)
                    .map(Metadata::getDeploymentId)
                    .orElse(null);
            Long sessionStartTime = exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class);
            Long sessionDuration = sessionStartTime == null ? null : System.currentTimeMillis() - sessionStartTime;
            Long exchangeStartTime = exchange.getProperty(CamelConstants.Properties.EXCHANGE_START_TIME_MS, Long.class);
            Long exchangeDuration = exchangeStartTime == null ? null : System.currentTimeMillis() - exchangeStartTime;
            CamelDebuggerProperties properties = propertiesService.getProperties(exchange);
            String chainId = properties.getDeploymentInfo().getChainId();
            result.add(LiveExchangeDTO.builder()
                        .exchangeId(exchange.getExchangeId())
                        .deploymentId(deploymentId)
                        .sessionId(exchange.getProperty(CamelConstants.Properties.SESSION_ID, String.class))
                        .chainId(chainId)
                        .sessionStartTime(sessionStartTime)
                        .sessionDuration(sessionDuration)
                        .sessionLogLevel(properties.getActualRuntimeProperties().calculateSessionLevel(exchange))
                        .duration(exchangeDuration)
                        .main(exchange.getProperty(CamelConstants.Properties.IS_MAIN_EXCHANGE, Boolean.class))
                    .build());
        }

        return result;
    }

    public void killLiveExchangeById(String deploymentId, String exchangeId) {
        Exchange exchange = camelContext.getInflightRepository().browse().stream()
                // TODO filter by deployment ID
                .filter(inflightExchange -> exchangeId.equals(inflightExchange.getExchange().getExchangeId()))
                .findAny().orElseThrow(() -> new EntityNotFoundException("No live exchange found for deployment id " + deploymentId))
                .getExchange();

        exchange.setException(new ChainExecutionTerminatedException("Chain was interrupted manually"));
    }


}
