package org.qubership.integration.platform.engine.camel.context.propagation;

import com.netcracker.cloud.context.propagation.core.contextdata.OutgoingContextData;

import java.util.Map;

public class CamelExchangeResponseContextData implements OutgoingContextData {
    private final Map<String, Object> headers;


    public CamelExchangeResponseContextData(Map<String, Object> headers) {
        this.headers = headers;
    }

    @Override
    public void set(String name, Object values) {
        if (name == null || values == null) {
            return;
        }

        headers.put(name, values.toString());
    }
}
