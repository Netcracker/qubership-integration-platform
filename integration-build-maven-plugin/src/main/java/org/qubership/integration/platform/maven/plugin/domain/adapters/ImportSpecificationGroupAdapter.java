package org.qubership.integration.platform.maven.plugin.domain.adapters;

import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;

import java.util.Collection;

public class ImportSpecificationGroupAdapter implements SpecificationGroup {
    private final ImportSpecificationGroup specificationGroup;

    public ImportSpecificationGroupAdapter(ImportSpecificationGroup specificationGroup) {
        this.specificationGroup = specificationGroup;
    }

    @Override
    public Collection<ServiceSpecification> getSpecifications() {
        return specificationGroup.getSystemModels().stream()
            .<ServiceSpecification>map(ImportSystemModelAdapter::new)
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
