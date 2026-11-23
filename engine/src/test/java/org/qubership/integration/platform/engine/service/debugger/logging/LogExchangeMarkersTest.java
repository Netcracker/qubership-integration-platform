package org.qubership.integration.platform.engine.service.debugger.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import net.logstash.logback.composite.loggingevent.LoggingEventJsonProviders;
import net.logstash.logback.composite.loggingevent.LogstashMarkersJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogExchangeMarkersTest {

    @ParameterizedTest
    @CsvSource({
            "-1, properties-value, headers-value, body-value",
            "4, prop..., head..., body..."
    })
    void shouldWriteExchangeFieldsInOrder(int maxSize, String properties, String headers, String body) {
        LogExchangeMarkers markers = new LogExchangeMarkers();
        markers.fieldValueMaxSize = maxSize;
        LoggingEvent event = new LoggingEvent();
        event.addMarker(markers.buildExchangeMarkers("body-value", "headers-value", "properties-value"));

        LoggerContext context = new LoggerContext();
        LoggingEventCompositeJsonEncoder encoder = new LoggingEventCompositeJsonEncoder();
        encoder.setContext(context);
        LoggingEventJsonProviders providers = new LoggingEventJsonProviders();
        providers.addLogstashMarkers(new LogstashMarkersJsonProvider());
        encoder.setProviders(providers);
        encoder.start();
        try {
            String json = new String(encoder.encode(event), StandardCharsets.UTF_8).stripTrailing();

            assertEquals("{\"exchange_properties\":\"" + properties
                    + "\",\"exchange_headers\":\"" + headers
                    + "\",\"exchange_body\":\"" + body + "\"}", json);
        } finally {
            encoder.stop();
            context.stop();
        }
    }
}
