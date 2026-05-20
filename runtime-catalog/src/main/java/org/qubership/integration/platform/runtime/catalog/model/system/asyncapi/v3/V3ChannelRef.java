package org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class V3ChannelRef {
    @JsonProperty("$ref")
    private String ref;
}
