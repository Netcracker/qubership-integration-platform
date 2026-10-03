package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import java.util.Collection;

public class ServiceSpecificationAdapter implements ServiceSpecification {
    private final SystemModel systemModel;

    public ServiceSpecificationAdapter(SystemModel systemModel) {
        this.systemModel = systemModel;
    }

    @Override
    public Collection<SpecificationSource> getSources() {
        return systemModel.getSpecificationSources()
            .stream()
            .<SpecificationSource>map(SpecificationSourceAdapter::new)
            .toList();
    }

    @Override
    public String getId() {
        return systemModel.getId();
    }

    @Override
    public String getName() {
        return systemModel.getName();
    }

    @Override
    public String getDescription() {
        return systemModel.getDescription();
    }
}
