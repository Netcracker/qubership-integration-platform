package org.qubership.integration.platform.chain.impl;

import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ServiceEnvironmentBuilder {
    private String id;
    private String name;
    private String description;
    private String systemId;
    private String address;
    private EnvironmentSourceType sourceType;
    private Map<String, Object> properties = new HashMap<>();
    private boolean activated;
    private Long createdWhen;
    private Long modifiedWhen;

    private ServiceEnvironmentBuilder() {
    }

    public static ServiceEnvironmentBuilder createNew() {
        return new ServiceEnvironmentBuilder();
    }

    public ServiceEnvironmentBuilder from(ServiceEnvironment environment) {
        this.id = environment.getId();
        this.name = environment.getName();
        this.description = environment.getDescription();
        this.systemId = environment.getSystemId();
        this.address = environment.getAddress();
        this.sourceType = environment.getSourceType();
        this.properties = new HashMap<>(environment.getProperties());
        this.activated = environment.isActivated();
        this.createdWhen = environment.getCreatedWhen();
        this.modifiedWhen = environment.getModifiedWhen();
        return this;
    }

    public ServiceEnvironmentBuilder id(String id) {
        this.id = id;
        return this;
    }

    public ServiceEnvironmentBuilder name(String name) {
        this.name = name;
        return this;
    }

    public ServiceEnvironmentBuilder description(String description) {
        this.description = description;
        return this;
    }

    public ServiceEnvironmentBuilder systemId(String systemId) {
        this.systemId = systemId;
        return this;
    }

    public ServiceEnvironmentBuilder address(String address) {
        this.address = address;
        return this;
    }

    public ServiceEnvironmentBuilder sourceType(EnvironmentSourceType sourceType) {
        this.sourceType = sourceType;
        return this;
    }

    public ServiceEnvironmentBuilder properties(Map<String, Object> properties) {
        this.properties = properties;
        return this;
    }

    public ServiceEnvironmentBuilder activated(boolean activated) {
        this.activated = activated;
        return this;
    }

    public ServiceEnvironmentBuilder createdWhen(Long createdWhen) {
        this.createdWhen = createdWhen;
        return this;
    }

    public ServiceEnvironmentBuilder modifiedWhen(Long modifiedWhen) {
        this.modifiedWhen = modifiedWhen;
        return this;
    }

    public ServiceEnvironment build() {
        ServiceEnvironmentImpl environment = new ServiceEnvironmentImpl();
        environment.setId(id);
        environment.setName(name);
        environment.setDescription(description);
        environment.setSystemId(systemId);
        environment.setAddress(address);
        environment.setSourceType(sourceType);
        environment.setProperties(Optional.ofNullable(properties).orElseGet(HashMap::new));
        environment.setActivated(activated);
        environment.setCreatedWhen(createdWhen);
        environment.setModifiedWhen(modifiedWhen);
        return environment;
    }
}
