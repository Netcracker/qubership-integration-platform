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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before;

import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletTagsProvider;
import org.qubership.integration.platform.engine.camel.repository.RegistryHelper;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before.helpers.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesCreated;

import java.util.Collection;

import static org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before.helpers.ChainElementTypeHelper.isHttpTriggerElement;

@ApplicationScoped
@OnBeforeRoutesCreated
public class HttpTriggerDependencyBinder extends ElementProcessingAction {
    private final MetricTagsHelper metricTagsHelper;

    @Inject
    public HttpTriggerDependencyBinder(MetricTagsHelper metricTagsHelper) {
        this.metricTagsHelper = metricTagsHelper;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        return isHttpTriggerElement(properties);
    }

    @Override
    public void apply(
        CamelContext context,
        ElementProperties elementProperties,
        DeploymentInfo deploymentInfo
    ) {
        Collection<Tag> tags = metricTagsHelper.buildMetricTags(deploymentInfo, elementProperties,
            deploymentInfo.getChainName());
        ServletTagsProvider servletTagsProvider = new ServletTagsProvider(tags);
        String elementId = elementProperties.getElementId();
        RegistryHelper.getRegistry(context, deploymentInfo.getDeploymentId())
                .bind(elementId, ServletTagsProvider.class, servletTagsProvider);
    }
}
