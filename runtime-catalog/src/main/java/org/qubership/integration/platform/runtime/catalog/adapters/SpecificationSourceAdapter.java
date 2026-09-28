package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.SpecificationSource;

public class SpecificationSourceAdapter implements SpecificationSource {
    private final org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource specificationSource;

    public SpecificationSourceAdapter(
        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource specificationSource
    ) {
        this.specificationSource = specificationSource;
    }

    @Override
    public String getName() {
        return specificationSource.getName();
    }

    @Override
    public boolean isMainSource() {
        return specificationSource.isMainSource();
    }

    @Override
    public String getText() {
        return specificationSource.getSource();
    }
}
