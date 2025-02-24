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

import com.google.common.collect.MinMaxPriorityQueue;
import jakarta.persistence.EntityNotFoundException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.spi.InflightRepository;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTerminatedException;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.rest.v1.dto.LiveExchangeDTO;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LiveExchangesService {

    Comparator<InflightExchangeHolder> EXCHANGE_COMPARATOR =
            Comparator.comparingLong(holder -> holder.getInflightExchange().getDuration() * -1);

    @RequiredArgsConstructor
    @Getter
    private static class InflightExchangeHolder {
        private final InflightRepository.InflightExchange inflightExchange;
        private final String deploymentId;
    }

    private final IntegrationRuntimeService integrationRuntimeService;
    private final CamelDebuggerPropertiesService propertiesService;

    public LiveExchangesService(IntegrationRuntimeService integrationRuntimeService, CamelDebuggerPropertiesService propertiesService) {
        this.integrationRuntimeService = integrationRuntimeService;
        this.propertiesService = propertiesService;
    }

    public List<LiveExchangeDTO> getTopLiveExchanges(int amount) {
        List<LiveExchangeDTO> result = new ArrayList<>();
        MinMaxPriorityQueue<InflightExchangeHolder> inflightExchanges = MinMaxPriorityQueue
                .orderedBy(EXCHANGE_COMPARATOR).maximumSize(amount).create();

        for (Map.Entry<String, SpringCamelContext> entry : integrationRuntimeService.getCache().getContexts().entrySet()) {
            String deploymentId = entry.getKey();
            SpringCamelContext context = entry.getValue();
            List<InflightExchangeHolder> exchangeHolders = context.getInflightRepository().browse(amount, true).stream()
                    .map(ex -> new InflightExchangeHolder(ex, deploymentId)).toList();
            inflightExchanges.addAll(exchangeHolders);
        }

        for (InflightExchangeHolder exchangeHolder : inflightExchanges) {
            Exchange exchange = exchangeHolder.getInflightExchange().getExchange();
            Long sessionStartTime = exchange.getProperty(CamelConstants.Properties.START_TIME_MS, Long.class);
            Long sessionDuration = sessionStartTime == null ? null : System.currentTimeMillis() - sessionStartTime;
            Long exchangeStartTime = exchange.getProperty(CamelConstants.Properties.EXCHANGE_START_TIME_MS, Long.class);
            Long exchangeDuration = exchangeStartTime == null ? null : System.currentTimeMillis() - exchangeStartTime;
            CamelDebuggerProperties properties = propertiesService.getProperties(exchange, exchangeHolder.getDeploymentId());
            String chainId = properties.getDeploymentInfo().getChainId();
            result.add(LiveExchangeDTO.builder()
                        .exchangeId(exchange.getExchangeId())
                        .deploymentId(exchangeHolder.getDeploymentId())
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
        SpringCamelContext context = integrationRuntimeService.getCache().getContexts().get(deploymentId);
        if (context == null) {
            throw new EntityNotFoundException("No deployment found for id " + deploymentId);
        }

        Exchange exchange = context.getInflightRepository().browse().stream()
                .filter(inflightExchange -> exchangeId.equals(inflightExchange.getExchange().getExchangeId()))
                .findAny().orElseThrow(() -> new EntityNotFoundException("No live exchange found for deployment id " + deploymentId))
                .getExchange();

        exchange.setException(new ChainExecutionTerminatedException("Chain was interrupted manually"));
    }


}