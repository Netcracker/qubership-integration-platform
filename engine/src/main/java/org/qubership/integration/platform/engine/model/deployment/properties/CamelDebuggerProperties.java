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

package org.qubership.integration.platform.engine.model.deployment.properties;

import org.qubership.integration.platform.engine.consul.ConsulService;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.apache.camel.Exchange;

@Getter
@Setter
@AllArgsConstructor
@Builder(toBuilder = true)
public class CamelDebuggerProperties {
    private DeploymentInfo deploymentInfo;
    @Getter(AccessLevel.PRIVATE)
    private AtomicReference<Map<String, DeploymentRuntimeProperties>> runtimePropertiesCacheRef;
    private Map<String, Map<String, String>> elementsProperties; // <element_id, properties>
    private Set<String> maskedFields;

    public DeploymentRuntimeProperties getActualRuntimeProperties() {
        return getRuntimeProperties(null);
    }

    public DeploymentRuntimeProperties getRuntimeProperties(@Nullable Exchange exchange) {
        // get params from exchange if property is present
        if (exchange != null) {
            DeploymentRuntimeProperties exchangeRuntimeProperties = exchange.getProperty(
                Properties.DEPLOYMENT_RUNTIME_PROPERTIES_MAP_PROP, DeploymentRuntimeProperties.class);
            if (exchangeRuntimeProperties != null) {
                return exchangeRuntimeProperties;
            }
        }

        // otherwise, try to find params in the cache
        String chainId = deploymentInfo.getChainId();
        if (chainId != null && runtimePropertiesCacheRef != null) {
            Map<String, DeploymentRuntimeProperties> cacheMap = runtimePropertiesCacheRef.get();

            if (cacheMap != null) {
                if (cacheMap.containsKey(chainId)) {
                    return cacheMap.get(chainId); // use custom chain settings
                }

                if (cacheMap.containsKey(ConsulService.DEFAULT_CONSUL_SETTING_KEY)) {
                    return cacheMap.get(ConsulService.DEFAULT_CONSUL_SETTING_KEY); // use consul defaults
                }
            }
        }

        // fallback to microservice defaults
        return DeploymentRuntimeProperties.getDefaultValues();
    }

    public void setElementsProperties(List<ElementProperties> elementsProperties) {
        this.elementsProperties = elementsProperties.stream()
            .collect(Collectors.toMap(ElementProperties::getElementId, ElementProperties::getProperties));
    }

    public Map<String, String> getElementProperty(String elementId) {
        return elementsProperties == null ? null : elementsProperties.get(elementId);
    }

    public boolean containsElementProperty(String elementId) {
        return elementsProperties != null && elementsProperties.containsKey(elementId);
    }

    public static class CamelDebuggerPropertiesBuilder {
        public CamelDebuggerPropertiesBuilder properties(List<ElementProperties> props) {
            this.elementsProperties = props.stream()
                .collect(Collectors.toMap(ElementProperties::getElementId, ElementProperties::getProperties));
            return this;
        }
    }
}
