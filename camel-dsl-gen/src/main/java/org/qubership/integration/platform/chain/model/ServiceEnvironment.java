package org.qubership.integration.platform.chain.model;

import java.util.Map;

public interface ServiceEnvironment extends Entity {
    String getSystemId();

    String getAddress();

    EnvironmentSourceType getSourceType();

    Map<String, Object> getProperties();

    boolean isActivated();
}
