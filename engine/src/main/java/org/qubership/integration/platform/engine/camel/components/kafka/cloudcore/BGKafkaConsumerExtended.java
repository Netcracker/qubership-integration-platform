package org.qubership.integration.platform.engine.camel.components.kafka.cloudcore;

import com.netcracker.cloud.maas.bluegreen.kafka.BGKafkaConsumer;
import com.netcracker.cloud.maas.bluegreen.kafka.CommitMarker;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;

import java.time.Duration;
import java.util.Map;

public interface BGKafkaConsumerExtended<K, V> extends BGKafkaConsumer<K, V> {

    void setOnCloseCallback(Runnable onCloseCallback);

    Map<MetricName, ? extends Metric> metrics();

    void commitSync(CommitMarker marker, Duration timeout);

    void wakeup();
}
