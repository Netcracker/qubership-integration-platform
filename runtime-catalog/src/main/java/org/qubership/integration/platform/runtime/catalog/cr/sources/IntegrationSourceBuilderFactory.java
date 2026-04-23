package org.qubership.integration.platform.runtime.catalog.cr.sources;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IntegrationSourceBuilderFactory {
    private final PlaceholdersSubstitutionService placeholdersSubstitutionService;
    private final List<IntegrationSourceBuilder> builders;

    @Autowired
    public IntegrationSourceBuilderFactory(
            PlaceholdersSubstitutionService placeholdersSubstitutionService,
            List<IntegrationSourceBuilder> builders
    ) {
        this.placeholdersSubstitutionService = placeholdersSubstitutionService;
        this.builders = builders;
    }

    public IntegrationSourceBuilder getBuilder(
            String languageName
    ) throws LanguageNotSupportedError {
        return builders.stream()
                .filter(builder -> languageName.equals(builder.getLanguageName()))
                .findFirst()
                .map(this::wrapWithPlaceholderSubstitutor)
                .orElseThrow(() -> new LanguageNotSupportedError(languageName));
    }

    private IntegrationSourceBuilder wrapWithPlaceholderSubstitutor(IntegrationSourceBuilder builder) {
        return new IntegrationSourceBuilder() {
            @Override
            public String getLanguageName() {
                return builder.getLanguageName();
            }

            @Override
            public String build(Snapshot snapshot, SourceBuilderContext context) throws Exception {
                return placeholdersSubstitutionService.substitute(builder.build(snapshot, context));
            }
        };
    }
}
