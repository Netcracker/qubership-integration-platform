package org.qubership.integration.platform.maven.plugin.domain.adapters;

import org.qubership.integration.platform.chain.model.SpecificationSource;
import org.qubership.integration.platform.codegen.model.CodegenSpecificationSource;

/**
 * Presents a {@link SpecificationSource} as a {@link CodegenSpecificationSource} for the
 * DTO-library code generators.
 */
public class SpecificationSourceCodegenAdapter implements CodegenSpecificationSource {
    private final SpecificationSource specificationSource;

    public SpecificationSourceCodegenAdapter(SpecificationSource specificationSource) {
        this.specificationSource = specificationSource;
    }

    @Override
    public String getName() {
        return specificationSource.getName();
    }

    @Override
    public String getSource() {
        return specificationSource.getText();
    }
}
