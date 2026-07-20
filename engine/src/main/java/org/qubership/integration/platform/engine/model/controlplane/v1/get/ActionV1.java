package org.qubership.integration.platform.engine.model.controlplane.v1.get;

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
