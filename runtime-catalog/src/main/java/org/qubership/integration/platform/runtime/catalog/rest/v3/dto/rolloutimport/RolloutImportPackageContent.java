package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RolloutImportPackageContent {

    private String name;
    private String version;
    private List<RolloutImportConfigurationItem> configurations = new ArrayList<>();
    private List<RolloutImportResourceItem> resources = new ArrayList<>();
    private List<JsonNode> confSchemas = new ArrayList<>();
    private List<JsonNode> infoSchemas = new ArrayList<>();
}
