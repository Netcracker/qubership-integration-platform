package org.qubership.integration.platform.engine.service.testing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndpointInfo {
    private String elementId;
    private String path;
    private String protocol;
}
