package org.qubership.integration.platform.maven.plugin.domain.adapters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.chain.impl.LabelImpl;
import org.qubership.integration.platform.chain.impl.ServiceEnvironmentBuilder;
import org.qubership.integration.platform.chain.model.ImportEnvironment;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.Label;
import org.qubership.integration.platform.chain.model.Protocol;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.chain.model.ServiceSpecification;
import org.qubership.integration.platform.chain.model.ServiceType;
import org.qubership.integration.platform.chain.model.SpecificationGroup;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Presents an {@link ImportSystem} read from a service file as the {@link IntegrationService} the
 * resource builders consume.
 *
 * <p>Two fields have no counterpart in the export format and are filled in here. An environment is
 * activated when its id matches the system's {@code activeEnvironmentId}, the same rule the catalog
 * applies to its own entities. Labels come back non-technical, because the export records names
 * only.
 *
 * <p>A specification group exposes its system models as {@link ServiceSpecification}s, and each
 * source carries the text the reader loaded from the archive file.
 */
public class ImportSystemAdapter implements IntegrationService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImportSystem importSystem;

    public ImportSystemAdapter(ImportSystem importSystem) {
        this.importSystem = importSystem;
    }

    @Override
    public ServiceType getType() {
        return importSystem.getIntegrationSystemType() == null
            ? null
            : ServiceType.valueOf(importSystem.getIntegrationSystemType().name());
    }

    @Override
    public Protocol getProtocol() {
        return importSystem.getProtocol() == null
            ? null
            : Protocol.valueOf(importSystem.getProtocol().name());
    }

    @Override
    public Optional<ServiceEnvironment> getActiveEnvironment() {
        return getEnvironments().stream().filter(ServiceEnvironment::isActivated).findFirst();
    }

    @Override
    public Collection<ServiceEnvironment> getEnvironments() {
        return importSystem.getEnvironments().stream().map(this::toServiceEnvironment).toList();
    }

    @Override
    public Collection<Label> getLabels() {
        return importSystem.getLabels().stream().<Label>map(name -> new LabelImpl(name, false)).toList();
    }

    @Override
    public Collection<SpecificationGroup> getSpecificationGroups() {
        return importSystem.getSpecificationGroups().stream()
            .<SpecificationGroup>map(ImportSpecificationGroupAdapter::new)
            .toList();
    }

    @Override
    public String getId() {
        return importSystem.getId();
    }

    @Override
    public String getName() {
        return importSystem.getName();
    }

    @Override
    public String getDescription() {
        return importSystem.getDescription();
    }

    private ServiceEnvironment toServiceEnvironment(ImportEnvironment environment) {
        return ServiceEnvironmentBuilder.createNew()
            .id(environment.getId())
            .name(environment.getName())
            .description(environment.getDescription())
            .systemId(importSystem.getId())
            .address(environment.getAddress())
            .sourceType(environment.getSourceType())
            .properties(MAPPER.convertValue(environment.getProperties(), new TypeReference<Map<String, Object>>() {}))
            .activated(Objects.equals(importSystem.getActiveEnvironmentId(), environment.getId()))
            .build();
    }
}
