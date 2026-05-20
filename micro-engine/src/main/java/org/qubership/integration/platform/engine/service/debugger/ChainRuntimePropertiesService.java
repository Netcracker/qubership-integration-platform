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

package org.qubership.integration.platform.engine.service.debugger;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.consul.ConsulConstants;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.isNull;

@Slf4j
@ApplicationScoped
public class ChainRuntimePropertiesService {
    private final AtomicReference<Map<String, ChainRuntimeProperties>> propertiesMap =
        new AtomicReference<>(Collections.emptyMap());

    public ChainRuntimeProperties getRuntimeProperties(final Exchange exchange) {
        ChainRuntimeProperties runtimeProperties = exchange.getProperty(
                Properties.CHAIN_RUNTIME_PROPERTIES_PROP, ChainRuntimeProperties.class);

        if (isNull(runtimeProperties)) {
            DeploymentInfo deploymentInfo = MetadataUtil.getBean(exchange, DeploymentInfo.class);
            String chainId = deploymentInfo.getChain().getId();
            runtimeProperties = getActualProperties(chainId);
            exchange.setProperty(Properties.CHAIN_RUNTIME_PROPERTIES_PROP, runtimeProperties);
        }

        return runtimeProperties;
    }

    public ChainRuntimeProperties getActualProperties(final String chainId) {
        Map<String, ChainRuntimeProperties> propMap = propertiesMap.get();
        return Optional.ofNullable(propMap.get(chainId))
                .or(() -> Optional.ofNullable(propMap.get(ConsulConstants.DEFAULT_CONSUL_SETTING_KEY)))
                .orElse(ChainRuntimeProperties.getDefault());
    }

    public void updateRuntimeProperties(Map<String, ChainRuntimeProperties> propertiesMap) {
        this.propertiesMap.set(propertiesMap);
    }
}
