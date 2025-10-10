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

import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.grpc.MetricCollectingClientInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.camel.repository.RegistryHelper;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before.helpers.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesCreated;

import java.util.function.UnaryOperator;

@ApplicationScoped
@OnBeforeRoutesCreated
public class GrpcElementDependencyBinder extends ElementProcessingAction {
    private final MetricsStore metricsStore;
    private final MetricTagsHelper metricTagsHelper;

    @Inject
    public GrpcElementDependencyBinder(
        MetricsStore metricsStore,
        MetricTagsHelper metricTagsHelper
    ) {
        this.metricsStore = metricsStore;
        this.metricTagsHelper = metricTagsHelper;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        String elementType = properties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        String protocol = properties.getProperties().get(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP);
        return ChainElementType.SERVICE_CALL.equals(chainElementType)
            && ChainProperties.OPERATION_PROTOCOL_TYPE_GRPC.equals(protocol);
    }

    @Override
    public void apply(
        CamelContext context,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        if (metricsStore.isMetricsEnabled()) {
            bindMetricInterceptor(context, properties, deploymentInfo);
        }
    }

    private void bindMetricInterceptor(
            CamelContext context,
            ElementProperties properties,
            DeploymentInfo deploymentInfo
    ) {
        Iterable<Tag> tags = metricTagsHelper.buildMetricTags(deploymentInfo, properties,
                deploymentInfo.getChainName());
        UnaryOperator<Counter.Builder> counterCustomizer = counter -> counter.tags(tags);
        UnaryOperator<Timer.Builder> timerCustomizer = timer -> timer.tags(tags);
        MetricCollectingClientInterceptor metricInterceptor = new MetricCollectingClientInterceptor(
                metricsStore.getMeterRegistry(), counterCustomizer, timerCustomizer, Status.Code.OK);
        String elementId = properties.getElementId();
        RegistryHelper.getRegistry(context, deploymentInfo.getDeploymentId()).bind(elementId, metricInterceptor);
    }
}
