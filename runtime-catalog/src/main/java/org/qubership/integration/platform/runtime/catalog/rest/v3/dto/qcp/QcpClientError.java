package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.qcp;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class QcpClientError {

    private String code;
    private String reason;
    private String message;
}
