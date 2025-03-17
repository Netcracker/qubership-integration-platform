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

import io.micrometer.core.instrument.Tag;
import org.apache.camel.component.kafka.DefaultKafkaClientFactory;
import org.apache.camel.component.kafka.KafkaClientFactory;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.camel.components.kafka.TaggedMetricsKafkaClientFactory;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collection;

import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.MAAS_CLASSIFIER;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers.ChainElementTypeHelper.isServiceCallOrAsyncApiTrigger;

@Component
@Order(KafkaElementDependencyBinder.ORDER)
@OnAfterDeploymentContextCreated
public class KafkaElementDependencyBinder extends ElementProcessingAction {
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    private final MetricsStore metricsStore;
    private final MetricTagsHelper metricTagsHelper;

    @Autowired
    public KafkaElementDependencyBinder(
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
        return ChainElementType.isKafkaAsyncElement(chainElementType) && (
            (!isServiceCallOrAsyncApiTrigger(chainElementType))
                || ChainProperties.OPERATION_PROTOCOL_TYPE_KAFKA.equals(
                properties.getProperties().get(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP)));
    }

    @Override
    public void apply(
        SpringCamelContext context,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        String elementId = properties.getElementId();
        DefaultKafkaClientFactory defaultFactory = new DefaultKafkaClientFactory();
        Collection<Tag> tags = metricTagsHelper.buildMetricTagsLegacy(deploymentInfo, properties, deploymentInfo.getChainName());

        String maasClassifier = properties.getProperties().get(ElementOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP);
        if (!StringUtils.isEmpty(maasClassifier)) {
            tags.add(Tag.of(MAAS_CLASSIFIER, maasClassifier));
        }

        // For camel 'kafka' and 'kafka-custom' component
        KafkaClientFactory kafkaClientFactory = metricsStore.isMetricsEnabled()
            ? new TaggedMetricsKafkaClientFactory(
            defaultFactory,
            metricsStore.getMeterRegistry(),
            tags)
            : defaultFactory;
        context.getRegistry().bind(elementId, KafkaClientFactory.class, kafkaClientFactory);
        context.getRegistry().bind(elementId + "-v2", KafkaClientFactory.class, kafkaClientFactory);
    }
}
