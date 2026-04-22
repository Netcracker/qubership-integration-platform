package org.qubership.integration.platform.engine.util.builders;

import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.grpc.MetricCollectingClientInterceptor;
import jakarta.enterprise.inject.spi.CDI;
import org.qubership.integration.platform.engine.service.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;

import java.util.Collection;
import java.util.function.UnaryOperator;

public class GrpcMetricCollectingClientInspectorBuilder {
    private String cId;
    private String cName;
    private String eId;
    private String eName;

    public GrpcMetricCollectingClientInspectorBuilder() {
        cId = "";
        cName = "";
        eId = "";
        eName = "";
    }

    public GrpcMetricCollectingClientInspectorBuilder chainId(String value) {
        cId = value;
        return this;
    }

    public GrpcMetricCollectingClientInspectorBuilder chainName(String value) {
        cName = value;
        return this;
    }

    public GrpcMetricCollectingClientInspectorBuilder elementId(String value) {
        eId = value;
        return this;
    }

    public GrpcMetricCollectingClientInspectorBuilder elementName(String value) {
        eName = value;
        return this;
    }

    public MetricCollectingClientInterceptor build() {
        MetricTagsHelper metricTagsHelper = CDI.current().select(MetricTagsHelper.class).get();
        Collection<Tag> tags = metricTagsHelper.buildMetricTags(cId, cName, eId, eName);
        UnaryOperator<Counter.Builder> counterCustomizer = counter -> counter.tags(tags);
        UnaryOperator<Timer.Builder> timerCustomizer = timer -> timer.tags(tags);
        MetricsStore metricsStore = CDI.current().select(MetricsStore.class).get();
        return new MetricCollectingClientInterceptor(
                metricsStore.getMeterRegistry(),
                counterCustomizer,
                timerCustomizer,
                Status.Code.OK
        );
    }
}
