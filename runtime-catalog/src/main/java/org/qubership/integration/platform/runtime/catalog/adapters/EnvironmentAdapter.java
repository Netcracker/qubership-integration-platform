package org.qubership.integration.platform.runtime.catalog.adapters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;

import java.util.Map;

public class EnvironmentAdapter implements ServiceEnvironment {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Environment environment;

    public EnvironmentAdapter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String getSystemId() {
        return environment.getSystem().getId();
    }

    @Override
    public String getAddress() {
        return environment.getAddress();
    }

    @Override
    public EnvironmentSourceType getSourceType() {
        return environment.getSourceType();
    }

    @Override
    public Map<String, Object> getProperties() {
        return MAPPER.convertValue(
            environment.getProperties(),
            new TypeReference<Map<String, Object>>() {}
        );
    }

    @Override
    public boolean isActivated() {
        return environment.getSystem().getActiveEnvironmentId() == environment.getId();
    }

    @Override
    public String getId() {
        return environment.getId();
    }

    @Override
    public String getName() {
        return environment.getName();
    }

    @Override
    public String getDescription() {
        return environment.getDescription();
    }
}
