package org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.builders.SourceDefinitionBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.locations.LibraryLocationGetterProvider;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Component
public class IntegrationsConfigurationBuilder {
    private final SourceDefinitionBuilder sourceDefinitionBuilder;
    private final LibraryLocationGetterProvider libraryLocationGetterProvider;

    @Autowired
    public IntegrationsConfigurationBuilder(
            SourceDefinitionBuilder sourceDefinitionBuilder,
            LibraryLocationGetterProvider libraryLocationGetterProvider
    ) {
        this.sourceDefinitionBuilder = sourceDefinitionBuilder;
        this.libraryLocationGetterProvider = libraryLocationGetterProvider;
    }

    public IntegrationsConfiguration build(ResourceBuildContext<List<Snapshot>> context) {
        List<Snapshot> chains = context.getData();
        return IntegrationsConfiguration.builder()
                .sources(chains.stream().map(snapshot -> buildSourceDefinition(context.updateTo(snapshot))).toList())
                .libraries(buildLibrariesDefinitions(context))
                .build();
    }

    private SourceDefinition buildSourceDefinition(ResourceBuildContext<Snapshot> context) {
        return sourceDefinitionBuilder.build(context);
    }

    private List<LibraryDefinition> buildLibrariesDefinitions(
            ResourceBuildContext<List<Snapshot>> context
    ) {
        List<Snapshot> snapshots = context.getData();

        // TODO use findAll(specification) method of ElementRepository to get all specification IDs
        Stream<String> specificationIds = snapshots.stream()
                .map(Snapshot::getElements)
                .flatMap(Collection::stream)
                .map(ChainElement::getProperties)
                .map(properties -> properties.get(CamelOptions.SPECIFICATION_ID))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(StringUtils::isNotBlank)
                .distinct();

        return specificationIds
                .map(id -> LibraryDefinition.builder()
                        .specificationId(id)
                        .location(getLibraryLocation(id, context))
                        .build())
                .toList();
    }

    private String getLibraryLocation(String id, ResourceBuildContext<List<Snapshot>> context) {
        return libraryLocationGetterProvider.get(context).apply(context.updateTo(id));
    }
}
