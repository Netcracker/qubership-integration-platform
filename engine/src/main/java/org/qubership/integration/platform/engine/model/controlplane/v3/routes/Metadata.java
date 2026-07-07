package org.qubership.integration.platform.engine.model.controlplane.v3.routes;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class Metadata {
    private String name;
    private String namespace;
}
