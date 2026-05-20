package org.qubership.integration.platform.engine.util.builders;

import com.rabbitmq.client.MetricsCollector;
import com.rabbitmq.client.impl.MicrometerMetricsCollector;
import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.NoOpMetricsCollector;
import org.qubership.integration.platform.engine.service.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;

import java.util.Collection;

import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.MAAS_CLASSIFIER;

public class RabbitMQMetricsCollectorBuilder {
    private String cId;
    private String cName;
    private String eId;
    private String eName;
    private String classifier;

    public RabbitMQMetricsCollectorBuilder() {
        cId = "";
        cName = "";
        eId = "";
        eName = "";
        classifier = "";
    }

    public RabbitMQMetricsCollectorBuilder chainId(String value) {
        cId = value;
        return this;
    }

    public RabbitMQMetricsCollectorBuilder chainName(String value) {
        cName = value;
        return this;
    }

    public RabbitMQMetricsCollectorBuilder elementId(String value) {
        eId = value;
        return this;
    }

    public RabbitMQMetricsCollectorBuilder elementName(String value) {
        eName = value;
        return this;
    }

    public RabbitMQMetricsCollectorBuilder maasClassifier(String value) {
        classifier = value;
        return this;
    }

    public MetricsCollector build() {
        MetricTagsHelper metricTagsHelper = CDI.current().select(MetricTagsHelper.class).get();

        Collection<Tag> tags = metricTagsHelper.buildMetricTags(cId, cName, eId, eName);
        if (StringUtils.isNotBlank(classifier)) {
            tags.add(Tag.of(MAAS_CLASSIFIER, classifier));
        }

        MetricsStore metricsStore = CDI.current().select(MetricsStore.class).get();
        return metricsStore.isMetricsEnabled()
                ? new MicrometerMetricsCollector(metricsStore.getMeterRegistry(), "rabbitmq", tags)
                : new NoOpMetricsCollector();
    }
}
