package org.qubership.integration.platform.maven.plugin.domain.adapters;

import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.Protocol;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.SpecificationGroup;
import org.qubership.integration.platform.codegen.model.CodegenSpecificationSource;
import org.qubership.integration.platform.codegen.model.CodegenSystemModel;
import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;

import java.util.List;

/**
 * Presents a {@link ServiceSpecification} as a {@link CodegenSystemModel} for the DTO-library code
 * generators.
 *
 * <p>A specification holds no reference to its owners, so the service and the group it was read
 * from are passed in; the generators build the package name from both names. The protocol comes
 * from the service and is mapped to the library enum by name, as the catalog does.
 */
public class ServiceSpecificationCodegenAdapter implements CodegenSystemModel {
    private final IntegrationService service;
    private final SpecificationGroup specificationGroup;
    private final ServiceSpecification specification;

    public ServiceSpecificationCodegenAdapter(
        IntegrationService service,
        SpecificationGroup specificationGroup,
        ServiceSpecification specification
    ) {
        this.service = service;
        this.specificationGroup = specificationGroup;
        this.specification = specification;
    }

    @Override
    public String getId() {
        return specification.getId();
    }

    @Override
    public String getName() {
        return specification.getName();
    }

    @Override
    public String getSystemName() {
        return service.getName();
    }

    @Override
    public String getGroupName() {
        return specificationGroup.getName();
    }

    @Override
    public OperationProtocol getProtocol() {
        Protocol protocol = service.getProtocol();
        return protocol == null ? null : OperationProtocol.valueOf(protocol.name());
    }

    @Override
    public List<CodegenSpecificationSource> getSpecificationSources() {
        return specification.getSources().stream()
            .<CodegenSpecificationSource>map(SpecificationSourceCodegenAdapter::new)
            .toList();
    }
}
