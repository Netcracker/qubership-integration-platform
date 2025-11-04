package org.qubership.integration.platform.engine.camel.context.propagation;

import java.util.Map;

public class CamelExchangeOverrideContextData extends CamelExchangeRequestContextData {

    public CamelExchangeOverrideContextData(Map<String, Object> headers) {
        super(headers);
    }
}
