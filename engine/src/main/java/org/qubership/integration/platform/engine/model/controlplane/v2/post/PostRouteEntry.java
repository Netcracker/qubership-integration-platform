package org.qubership.integration.platform.engine.model.controlplane.v2.post;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class PostRouteEntry {
    private String prefix;
    private String prefixRewrite;
}
