package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;

import java.util.Map;

public class ServiceEnvironmentAdapter implements ServiceEnvironment {
    private final org.qubership.integration.platform.io.model.exportimport.system.ServiceEnvironment delegate;

    public ServiceEnvironmentAdapter(org.qubership.integration.platform.io.model.exportimport.system.ServiceEnvironment delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getSystemId() {
        return delegate.getSystemId();
    }

    @Override
    public String getAddress() {
        return delegate.getAddress();
    }

    @Override
    public EnvironmentSourceType getSourceType() {
        return delegate.getSourceType();
    }

    @Override
    public Map<String, Object> getProperties() {
        return delegate.getProperties();
    }

    @Override
    public boolean isActivated() {
        return !delegate.isNotActivated();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }
}
