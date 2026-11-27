package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.databind.JsonNode;


@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RolloutImportConfigurationItem {

    private String id;
    @JsonProperty("$schema")
    private String schema;
    private String name;
    private JsonNode content;
}
