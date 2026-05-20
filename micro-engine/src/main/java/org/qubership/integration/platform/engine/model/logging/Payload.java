package org.qubership.integration.platform.engine.model.logging;

import org.qubership.integration.platform.engine.model.SessionElementProperty;

import java.util.Map;

public interface Payload {
    Map<String, String> getHeaders();

    Map<String, String> getContext();

    Map<String, SessionElementProperty> getProperties();

    String getBody();
}
