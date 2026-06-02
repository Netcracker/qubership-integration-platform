package org.qubership.integration.platform.runtime.catalog.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;

import java.util.Map;

@Getter
@AllArgsConstructor
public class ImportConfig {

    private final Map<String, RolloutImportConfigurationItem> chains;
    private final Map<String, RolloutImportConfigurationItem> services;
    private final Map<String, RolloutImportConfigurationItem> specificationGroups;
    private final Map<String, RolloutImportConfigurationItem> specifications;
    private final Map<String, RolloutImportConfigurationItem> commonVariables;
    private final Map<String, RolloutImportConfigurationItem> contextServices;
    private final Map<String, String> resources;

    public boolean isEmpty() {
        return chains.isEmpty()
                && services.isEmpty()
                && specificationGroups.isEmpty()
                && specifications.isEmpty()
                && commonVariables.isEmpty()
                && contextServices.isEmpty();
    }
}
