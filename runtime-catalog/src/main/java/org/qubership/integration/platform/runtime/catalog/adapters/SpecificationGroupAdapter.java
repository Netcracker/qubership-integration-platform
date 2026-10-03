package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;

import java.util.Collection;

public class SpecificationGroupAdapter implements SpecificationGroup {
    private final org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup specificationGroup;

    public SpecificationGroupAdapter(
        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup specificationGroup
    ) {
        this.specificationGroup = specificationGroup;
    }

    @Override
    public Collection<ServiceSpecification> getSpecifications() {
        return specificationGroup.getSystemModels()
            .stream()
            .<ServiceSpecification>map(ServiceSpecificationAdapter::new)
            .toList();
    }

    @Override
    public String getId() {
        return specificationGroup.getId();
    }

    @Override
    public String getName() {
        return specificationGroup.getName();
    }

    @Override
    public String getDescription() {
        return specificationGroup.getDescription();
    }
}
