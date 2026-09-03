package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.impl.LabelImpl;
import org.qubership.integration.platform.chain.model.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;

import java.util.Collection;
import java.util.Optional;

public class IntegrationServiceAdapter implements IntegrationService {
    private final IntegrationSystem integrationSystem;

    public IntegrationServiceAdapter(IntegrationSystem integrationSystem) {
        this.integrationSystem = integrationSystem;
    }

    @Override
    public ServiceType getType() {
        return ServiceType.valueOf(integrationSystem.getIntegrationSystemType().name());
    }

    @Override
    public Protocol getProtocol() {
        return Protocol.valueOf(integrationSystem.getProtocol().name());
    }

    @Override
    public Optional<ServiceEnvironment> getActiveEnvironment() {
        return getEnvironments().stream()
            .filter(e -> e.getId().equals(integrationSystem.getActiveEnvironmentId()))
            .findFirst();
    }

    @Override
    public Collection<ServiceEnvironment> getEnvironments() {
        return integrationSystem.getEnvironments()
            .stream()
            .<ServiceEnvironment>map(EnvironmentAdapter::new)
            .toList();
    }

    @Override
    public Collection<Label> getLabels() {
        return integrationSystem.getLabels()
            .stream()
            .<Label>map(l -> new LabelImpl(l.getName(), l.isTechnical()))
            .toList();
    }

    @Override
    public String getId() {
        return integrationSystem.getId();
    }

    @Override
    public String getName() {
        return integrationSystem.getName();
    }

    @Override
    public String getDescription() {
        return integrationSystem.getDescription();
    }
}
