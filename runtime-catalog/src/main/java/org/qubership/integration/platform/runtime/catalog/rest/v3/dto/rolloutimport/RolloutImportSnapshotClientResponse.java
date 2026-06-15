package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.qubership.integration.platform.runtime.catalog.model.ErrorCodePayload;

import java.util.List;

@Getter
@Setter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RolloutImportSnapshotClientResponse {

    private String clientId;
    private String namespace;
    private String status;
    private List<ErrorCodePayload> errors;
}
