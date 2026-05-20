package org.qubership.integration.platform.engine.controlplane.rest.model.v1.get;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionV1 {
    private String hostRewrite;
    private String prefixRewrite;
}
