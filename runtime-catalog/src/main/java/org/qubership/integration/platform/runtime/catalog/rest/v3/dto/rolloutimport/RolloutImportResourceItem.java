package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RolloutImportResourceItem {

    private String id;
    private String name;
    @JsonProperty("resourceName")
    private String legacyResourceName;
    private String resourceContent;
    private Boolean encoded;
}
