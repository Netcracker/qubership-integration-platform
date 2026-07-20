package org.qubership.integration.platform.engine.model.controlplane.v3.post.tlsdef;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class TLS {
    @Builder.Default
    private boolean enabled = true;
    private boolean insecure;

    private String sni;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String trustedCA;
}
