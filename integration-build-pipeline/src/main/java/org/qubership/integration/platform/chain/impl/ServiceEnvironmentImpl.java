package org.qubership.integration.platform.chain.impl;

import lombok.Data;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;

import java.util.Map;

@Data
public class ServiceEnvironmentImpl implements ServiceEnvironment {
    private String id;
    private String name;
    private String description;
    private String systemId;
    private String address;
    private EnvironmentSourceType sourceType;
    private Map<String, Object> properties;
    private boolean activated;
    private Long createdWhen;
    private Long modifiedWhen;
}
