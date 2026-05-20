package org.qubership.integration.platform.engine.model.controlplane.v3.post.tlsdef;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class TlsSpec {
    private String name;
    private TLS tls;
}
