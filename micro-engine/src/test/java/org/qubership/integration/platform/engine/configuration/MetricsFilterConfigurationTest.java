package org.qubership.integration.platform.engine.configuration;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetricsFilterConfigurationTest {

    private final MetricsFilterConfiguration configuration = new MetricsFilterConfiguration();

    @Test
    void shouldRemoveClientIdAndNodeIdTagsForKafkaMetrics() {
        MeterFilter meterFilter = configuration.meterFilter();
        Meter.Id id = new Meter.Id(
                "kafka.consumer.records",
                Tags.of(
                        Tag.of("client.id", "client-1"),
                        Tag.of("node.id", "node-1"),
                        Tag.of("topic", "orders"),
                        Tag.of("partition", "0")
                ),
                null,
                null,
                Meter.Type.COUNTER
        );

        Meter.Id result = meterFilter.map(id);

        List<Tag> tags = result.getTags();

        assertEquals(2, tags.size());
        assertEquals(
                Set.of(
                        Tag.of("topic", "orders"),
                        Tag.of("partition", "0")
                ),
                Set.copyOf(tags)
        );
    }

    @Test
    void shouldReturnSameIdForNonKafkaMetrics() {
        MeterFilter meterFilter = configuration.meterFilter();
        Meter.Id id = new Meter.Id(
                "http.server.requests",
                Tags.of(
                        Tag.of("client.id", "client-1"),
                        Tag.of("node.id", "node-1"),
                        Tag.of("uri", "/health")
                ),
                null,
                null,
                Meter.Type.TIMER
        );

        Meter.Id result = meterFilter.map(id);

        assertSame(id, result);
        assertEquals(id.getTags(), result.getTags());
    }
}
