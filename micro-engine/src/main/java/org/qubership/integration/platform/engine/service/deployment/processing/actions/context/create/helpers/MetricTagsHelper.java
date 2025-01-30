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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers;

import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import io.micrometer.core.instrument.Tag;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.CHAIN_ID_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.CHAIN_NAME_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.ELEMENT_ID_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.ELEMENT_NAME_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.ENGINE_DOMAIN_TAG;

@Component
public class MetricTagsHelper {
    public final ServerConfiguration serverConfiguration;

    @Autowired
    public MetricTagsHelper(ServerConfiguration serverConfiguration) {
        this.serverConfiguration = serverConfiguration;
    }

    public Collection<Tag> buildMetricTagsLegacy(
        DeploymentInfo deploymentInfo,
        ElementProperties elementProperties,
        String chainName
    ) {
        return new ArrayList<>(Arrays.asList(
            Tag.of(CHAIN_ID_TAG, deploymentInfo.getChainId()),
            Tag.of(CHAIN_NAME_TAG, chainName),
            Tag.of(ELEMENT_ID_TAG, elementProperties.getProperties().get(ChainProperties.ELEMENT_ID)),
            Tag.of(ELEMENT_NAME_TAG, elementProperties.getProperties().get(
                ChainProperties.ELEMENT_NAME)),
            Tag.of(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain()))
        );
    }

    public KeyValues buildMetricTags(
        DeploymentInfo deploymentInfo,
        ElementProperties elementProperties,
        String chainName
    ) {
        return KeyValues.of(
            KeyValue.of(CHAIN_ID_TAG, deploymentInfo.getChainId()),
            KeyValue.of(CHAIN_NAME_TAG, chainName),
            KeyValue.of(ELEMENT_ID_TAG, elementProperties.getProperties().get(
                ChainProperties.ELEMENT_ID)),
            KeyValue.of(ELEMENT_NAME_TAG, elementProperties.getProperties().get(
                ChainProperties.ELEMENT_NAME)),
            KeyValue.of(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain()));
    }
}
