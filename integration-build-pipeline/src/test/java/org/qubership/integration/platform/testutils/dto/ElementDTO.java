package org.qubership.integration.platform.testutils.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Map;

@Data
@Jacksonized
@JsonInclude(value = JsonInclude.Include.NON_EMPTY, content = JsonInclude.Include.NON_NULL)
@SuperBuilder
public class ElementDTO {
    private String id;
    @JsonProperty("original-id")
    private String originalId;
    private String name;
    private String description;
    @JsonProperty("element-type")
    private String type;
    private Map<String, Object> properties;
    private Collection<ElementDTO> children;
    @JsonProperty("service-environment")
    private ServiceEnvironmentDTO serviceEnvironment;
    private Timestamp modifiedWhen;
}
