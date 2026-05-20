package org.qubership.integration.platform.engine.util.builders;

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.component.kafka.DefaultKafkaClientFactory;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.DefaultKafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.TaggedMetricsKafkaBGClientFactory;
import org.qubership.integration.platform.engine.service.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;

import java.util.Collection;

import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.MAAS_CLASSIFIER;

public class KafkaClientFactoryBuilder {
    private String cId;
    private String cName;
    private String eId;
    private String eName;
    private String classifier;

    public KafkaClientFactoryBuilder() {
        cId = "";
        cName = "";
        eId = "";
        eName = "";
        classifier = "";
    }

    public KafkaClientFactoryBuilder chainId(String value) {
        cId = value;
        return this;
    }

    public KafkaClientFactoryBuilder chainName(String value) {
        cName = value;
        return this;
    }

    public KafkaClientFactoryBuilder elementId(String value) {
        eId = value;
        return this;
    }

    public KafkaClientFactoryBuilder elementName(String value) {
        eName = value;
        return this;
    }

    public KafkaClientFactoryBuilder maasClassifier(String value) {
        classifier = value;
        return this;
    }

    public KafkaBGClientFactory build() {
        BlueGreenStatePublisher blueGreenStatePublisher = CDI.current().select(BlueGreenStatePublisher.class).get();
        MetricTagsHelper metricTagsHelper = CDI.current().select(MetricTagsHelper.class).get();
        DefaultKafkaClientFactory defaultFactory = new DefaultKafkaClientFactory();
        Collection<Tag> tags = metricTagsHelper.buildMetricTags(cId, cName, eId, eName);

        if (StringUtils.isNotBlank(classifier)) {
            tags.add(Tag.of(MAAS_CLASSIFIER, classifier));
        }

        MetricsStore metricsStore = CDI.current().select(MetricsStore.class).get();

        // For camel 'kafka' and 'kafka-custom' component
        return metricsStore.isMetricsEnabled()
                ? new TaggedMetricsKafkaBGClientFactory(
                    defaultFactory,
                    metricsStore.getMeterRegistry(),
                    tags,
                    blueGreenStatePublisher
                )
                : new DefaultKafkaBGClientFactory(defaultFactory, blueGreenStatePublisher);
    }
}
