package org.qubership.integration.platform.runtime.catalog.model.exportimport.system;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@SuperBuilder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MCPServiceContentDto {
    private String description;
    private String identifier;
    private String instructions;
    private Timestamp createdWhen;
    private Timestamp modifiedWhen;
    private User createdBy;
    private User modifiedBy;
    private String migrations;

    @Builder.Default
    private List<String> labels = new ArrayList<>();
}
