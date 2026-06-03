package org.qubership.integration.platform.runtime.catalog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
public class ErrorCodePayload {

    private String code;
    private String reason;
    private String message;
}
