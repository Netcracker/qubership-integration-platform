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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import io.micrometer.common.KeyValues;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletTagsProvider;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers.ChainElementTypeHelper.isHttpTriggerElement;

@Component
@OnAfterDeploymentContextCreated
public class HttpTriggerDependencyBinder extends ElementProcessingAction {
    private final MetricTagsHelper metricTagsHelper;

    @Autowired
    public HttpTriggerDependencyBinder(MetricTagsHelper metricTagsHelper) {
        this.metricTagsHelper = metricTagsHelper;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        return isHttpTriggerElement(properties);
    }

    @Override
    public void apply(
        SpringCamelContext context,
        ElementProperties elementProperties,
        DeploymentInfo deploymentInfo
    ) {
        KeyValues tags = metricTagsHelper.buildMetricTags(deploymentInfo, elementProperties,
            deploymentInfo.getChainName());
        ServletTagsProvider servletTagsProvider = new ServletTagsProvider(tags);
        String elementId = elementProperties.getElementId();
        context.getRegistry().bind(elementId, ServletTagsProvider.class, servletTagsProvider);
    }
}
