package org.qubership.integration.platform.maven.plugin.domain.adapters;

import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationSource;

import java.util.Collection;

public class ImportSystemModelAdapter implements ServiceSpecification {
    private final ImportSystemModel systemModel;

    public ImportSystemModelAdapter(ImportSystemModel systemModel) {
        this.systemModel = systemModel;
    }

    @Override
    public Collection<SpecificationSource> getSources() {
        return systemModel.getSpecificationSources().stream()
            .<SpecificationSource>map(ImportSpecificationSourceAdapter::new)
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
