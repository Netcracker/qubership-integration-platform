package org.qubership.integration.platform.chain.model;

import java.util.Map;

public interface ServiceEnvironment extends Entity {
    String getSystemId();

    String getAddress();

    EnvironmentSourceType getSourceType();

    Map<String, Object> getProperties();

    boolean isActivated();

    /**
     * Creation timestamp (epoch milliseconds) preserved across an import round-trip. Adapters that
     * do not track it return {@code null}.
     */
    default Long getCreatedWhen() {
        return null;
    }

    /**
     * Last-modification timestamp (epoch milliseconds) preserved across an import round-trip. Adapters
     * that do not track it return {@code null}.
     */
    default Long getModifiedWhen() {
        return null;
    }
}
