package org.qubership.integration.platform.maven.plugin.domain.adapters;

import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.chain.model.SpecificationSource;

public class ImportSpecificationSourceAdapter implements SpecificationSource {
    private final ImportSpecificationSource specificationSource;

    public ImportSpecificationSourceAdapter(ImportSpecificationSource specificationSource) {
        this.specificationSource = specificationSource;
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
