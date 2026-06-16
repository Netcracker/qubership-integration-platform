package org.qubership.integration.platform.testutils.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.sql.Timestamp;
import java.util.Collection;

@Data
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@SuperBuilder
public class ChainDTO {
    private String id;
    private String name;
    private String description;
    private String businessDescription;
    private String assumptions;
    private String outOfScope;
    private Collection<ElementDTO> elements;
    private Timestamp modifiedWhen;
    private Collection<DependencyDTO> dependencies;

    //    @JsonProperty("default-swimlane-id")
    //    private String defaultSwimlaneId;
    //
    //    @JsonProperty("reuse-swimlane-id")
    //    private String reuseSwimlaneId;
}
