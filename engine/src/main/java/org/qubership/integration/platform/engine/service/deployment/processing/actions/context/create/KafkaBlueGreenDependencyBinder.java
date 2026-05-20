package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import io.micrometer.core.instrument.Tag;
import org.apache.camel.component.kafka.DefaultKafkaClientFactory;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.DefaultKafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.TaggedMetricsKafkaBGClientFactory;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;

import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers.ChainElementTypeHelper.isServiceCallOrAsyncApiTrigger;

@Component
@OnAfterDeploymentContextCreated
public class KafkaBlueGreenDependencyBinder extends ElementProcessingAction {
    private final MetricsStore metricsStore;
    private final MetricTagsHelper metricTagsHelper;
    private final BlueGreenStatePublisher blueGreenStatePublisher;

    @Autowired
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
    public void apply(SpringCamelContext context, ElementProperties properties, DeploymentInfo deploymentInfo) {
        String elementId = properties.getElementId();
        DefaultKafkaClientFactory defaultFactory = new DefaultKafkaClientFactory();
        Collection<Tag> tags = metricTagsHelper.buildMetricTagsLegacy(deploymentInfo, properties, deploymentInfo.getChainName());
        // For custom 'kafka-custom' component
        KafkaBGClientFactory kafkaClientFactory = metricsStore.isMetricsEnabled()
            ? new TaggedMetricsKafkaBGClientFactory(
            defaultFactory,
            metricsStore.getMeterRegistry(),
            tags,
            blueGreenStatePublisher)
            : new DefaultKafkaBGClientFactory(defaultFactory, blueGreenStatePublisher);
        context.getRegistry()
            .bind(elementId + "-v2", KafkaBGClientFactory.class, kafkaClientFactory);
    }
}
