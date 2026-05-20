package org.qubership.integration.platform.engine.model.controlplane.v3.post.tlsdef;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class TLSDefinitionObjectV3 {
    @Builder.Default
    private final String apiVersion = "nc.core.mesh/v3";
    @Builder.Default
    private final String kind = "TlsDef";
    private TlsSpec spec;
}
