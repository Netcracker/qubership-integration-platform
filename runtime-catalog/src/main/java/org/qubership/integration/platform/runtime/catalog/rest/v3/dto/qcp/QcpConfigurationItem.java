package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QcpConfigurationItem {

    private String id;
    @JsonProperty("$schema")
    private String schema;
    private String name;
    private JsonNode content;
}
