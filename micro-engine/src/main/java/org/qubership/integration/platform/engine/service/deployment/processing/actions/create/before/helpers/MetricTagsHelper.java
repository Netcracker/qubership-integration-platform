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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before.helpers;

import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.*;

@ApplicationScoped
public class MetricTagsHelper {
    private final ServerConfiguration serverConfiguration;

    @Inject
    public MetricTagsHelper(ServerConfiguration serverConfiguration) {
        this.serverConfiguration = serverConfiguration;
    }

    public Collection<Tag> buildMetricTags(
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
}
