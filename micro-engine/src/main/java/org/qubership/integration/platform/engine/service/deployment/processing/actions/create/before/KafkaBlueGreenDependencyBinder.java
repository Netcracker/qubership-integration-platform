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

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.component.kafka.DefaultKafkaClientFactory;
import org.apache.camel.spi.Registry;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.DefaultKafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.TaggedMetricsKafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.repository.RegistryHelper;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before.helpers.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesCreated;

import java.util.Collection;

import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.MAAS_CLASSIFIER;
import static org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before.helpers.ChainElementTypeHelper.isServiceCallOrAsyncApiTrigger;

@ApplicationScoped
@Priority(KafkaBlueGreenDependencyBinder.ORDER)
@OnBeforeRoutesCreated
public class KafkaBlueGreenDependencyBinder extends ElementProcessingAction {
    public static final int ORDER = 0;

    private final MetricsStore metricsStore;
    private final MetricTagsHelper metricTagsHelper;
    private final BlueGreenStatePublisher blueGreenStatePublisher;

    @Inject
    public KafkaBlueGreenDependencyBinder(
            MetricsStore metricsStore,
            MetricTagsHelper metricTagsHelper,
            BlueGreenStatePublisher blueGreenStatePublisher
    ) {
        this.metricsStore = metricsStore;
        this.metricTagsHelper = metricTagsHelper;
        this.blueGreenStatePublisher = blueGreenStatePublisher;
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
            CamelContext context,
            ElementProperties properties,
            DeploymentInfo deploymentInfo
    ) {
        String elementId = properties.getElementId();
        DefaultKafkaClientFactory defaultFactory = new DefaultKafkaClientFactory();
        Collection<Tag> tags = metricTagsHelper.buildMetricTags(deploymentInfo, properties, deploymentInfo.getChainName());

        String maasClassifier = properties.getProperties().get(ElementOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP);
        if (!StringUtils.isEmpty(maasClassifier)) {
            tags.add(Tag.of(MAAS_CLASSIFIER, maasClassifier));
        }

        // For custom 'kafka-custom' component
        KafkaBGClientFactory kafkaClientFactory = metricsStore.isMetricsEnabled()
                ? new TaggedMetricsKafkaBGClientFactory(
                        defaultFactory,
                        metricsStore.getMeterRegistry(),
                        tags,
                        blueGreenStatePublisher
                )
                : new DefaultKafkaBGClientFactory(defaultFactory, blueGreenStatePublisher);
        Registry registry = RegistryHelper.getRegistry(context, deploymentInfo.getDeploymentId());
        registry.bind(elementId + "-v2", KafkaBGClientFactory.class, kafkaClientFactory);
    }
}
