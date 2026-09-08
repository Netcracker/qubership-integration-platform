package org.qubership.integration.platform.engine.service.debugger.logging;

import net.logstash.logback.marker.LogstashMarker;
import org.springframework.stereotype.Component;

import static net.logstash.logback.marker.Markers.append;

@Component
public class LogExchangeMarkers extends AbstractTruncatedFieldLogger {

    public LogstashMarker buildExchangeMarkers(String body, String headers, String properties) {
        return append("exchange_body", truncateValue(body))
                .and(append("exchange_headers", truncateValue(headers)))
                .and(append("exchange_properties", truncateValue(properties)));
    }
}
