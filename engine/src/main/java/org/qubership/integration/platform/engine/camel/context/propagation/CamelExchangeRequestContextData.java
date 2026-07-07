package org.qubership.integration.platform.engine.camel.context.propagation;

import com.netcracker.cloud.context.propagation.core.contextdata.IncomingContextData;
import org.apache.camel.util.CaseInsensitiveMap;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;


public class CamelExchangeRequestContextData implements IncomingContextData {
    private final Map<String, Object> headers = new CaseInsensitiveMap();

    public CamelExchangeRequestContextData(Map<String, Object> headers) {
        this.headers.putAll(headers.entrySet().stream()
                .filter(header -> header.getKey() != null && header.getValue() != null)
                .collect(toMap(
                        Map.Entry::getKey,
                        e -> stringify(e.getValue())
                )));
    }

    @Override
    public Object get(String name) {
        return headers.get(name);
    }

    @Override
    public Map<String, List<?>> getAll() {
        return headers.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> {
            Object data = e.getValue();
            if (data instanceof List) {
                return (List<?>) data;
            }
            return Collections.singletonList(data);
        }));
    }

    private Object stringify(Object headerValue) {
        if (headerValue == null) {
            return null;
        }

        if (headerValue instanceof List) {
            List<?> headerValues = (List<?>) headerValue;
            return headerValues.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
        }
        return headerValue.toString();
    }
}
