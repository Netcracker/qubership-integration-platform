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
import org.qubership.integration.platform.engine.errorhandling.ChainExecutionTerminatedException;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.rest.v1.dto.LiveExchangeDTO;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;
import org.qubership.integration.platform.engine.util.ExchangeUtil;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@ApplicationScoped
public class LiveExchangesService {
    private final ChainRuntimePropertiesService propertiesService;
    private final CamelContext camelContext;

    @Inject
    public LiveExchangesService(
            CamelContext camelContext,
            ChainRuntimePropertiesService propertiesService
    ) {
        this.camelContext = camelContext;
        this.propertiesService = propertiesService;
    }

    public List<LiveExchangeDTO> getTopLiveExchanges(int amount) {
        List<LiveExchangeDTO> result = new ArrayList<>();

        List<InflightRepository.InflightExchange> exchangeHolders = camelContext.getInflightRepository()
                .browse(amount, true).stream().toList();

        for (InflightRepository.InflightExchange exchangeHolder : exchangeHolders) {
            Exchange exchange = exchangeHolder.getExchange();
            DeploymentInfo deploymentInfo = MetadataUtil.getBean(exchange, DeploymentInfo.class);
            ChainRuntimeProperties properties = propertiesService.getRuntimeProperties(exchange);
            result.add(LiveExchangeDTO.builder()
                    .exchangeId(exchange.getExchangeId())
                    .deploymentId(deploymentInfo.getId())
                    .sessionId(ExchangeUtil.getSessionId(exchange))
                    .chainId(deploymentInfo.getChain().getId())
                    .sessionStartTime(ExchangeUtil.getSessionStartTime(exchange))
                    .sessionDuration(ExchangeUtil.getSessionDuration(exchange))
                    .sessionLogLevel(properties.calculateSessionLevel(exchange))
                    .duration(ExchangeUtil.getExchangeDuration(exchange))
                    .main(exchange.getProperty(CamelConstants.Properties.IS_MAIN_EXCHANGE, Boolean.class))
                    .build());
        }

        return result;
    }

    public void killLiveExchangeById(String deploymentId, String exchangeId) {
        Exchange exchange = camelContext.getInflightRepository().browse().stream()
                .filter(inflightExchange -> MetadataUtil.getBean(inflightExchange.getExchange(), DeploymentInfo.class)
                        .getId().equals(deploymentId))
                .filter(inflightExchange -> exchangeId.equals(inflightExchange.getExchange().getExchangeId()))
                .findAny()
                .orElseThrow(() -> new EntityNotFoundException("No live exchange found for deployment id " + deploymentId))
                .getExchange();

        exchange.setException(new ChainExecutionTerminatedException("Chain was interrupted manually"));
    }


}
