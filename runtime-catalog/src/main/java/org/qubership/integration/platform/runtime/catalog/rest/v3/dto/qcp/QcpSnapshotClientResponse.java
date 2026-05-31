package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QcpSnapshotClientResponse {

    private String clientId;
    private String namespace;
    private String status;
    private List<QcpClientError> errors;
}
