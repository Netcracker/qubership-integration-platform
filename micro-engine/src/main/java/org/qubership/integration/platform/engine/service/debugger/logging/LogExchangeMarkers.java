package org.qubership.integration.platform.engine.service.debugger.logging;

import io.quarkiverse.loggingjson.providers.StructuredArgument;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.engine.model.logging.LoggedPayloadValues;

import java.util.ArrayList;
import java.util.List;

import static io.quarkiverse.loggingjson.providers.KeyValueStructuredArgument.kv;

@ApplicationScoped
public class LogExchangeMarkers extends AbstractTruncatedFieldLogger {

    public List<StructuredArgument> buildExchangeMarkers(LoggedPayloadValues loggedPayloadValues) {
        return buildExchangeMarkers(loggedPayloadValues.getBody(), loggedPayloadValues.getHeaders(),
                loggedPayloadValues.getProperties());
    }

    public List<StructuredArgument> buildExchangeMarkers(String body, String headers, String properties) {
        List<StructuredArgument> result = new ArrayList<>();
        result.add(kv("exchange_body", truncateValue(body)));
        result.add(kv("exchange_headers", truncateValue(headers)));
        result.add(kv("exchange_properties", truncateValue(properties)));
        return result;
    }
}
