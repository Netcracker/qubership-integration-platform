package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import org.apache.camel.Exchange;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public final class SnapshotExchangeHeaders {
    private static final String PROPERTY = SnapshotExchangeHeaders.class.getName();

    private SnapshotExchangeHeaders() {
    }

    public static void capture(Exchange result, Exchange internalExchange) {
        Map<String, Object> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(internalExchange.getMessage().getHeaders());
        result.setProperty(PROPERTY, new CapturedHeaders(Collections.unmodifiableMap(headers)));
    }

    public static Map<String, Object> get(Exchange result) {
        CapturedHeaders captured = result.getProperty(PROPERTY, CapturedHeaders.class);
        return captured == null ? result.getMessage().getHeaders() : captured.values();
    }

    private record CapturedHeaders(Map<String, Object> values) {
    }
}
