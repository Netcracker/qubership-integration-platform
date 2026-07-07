package org.qubership.integration.platform.engine.model.controlplane.v3.post;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RouteConfigurationObjectV3 {
    @Builder.Default
    private final String apiVersion = "nc.core.mesh/v3";
    @Builder.Default
    private final String kind = "RouteConfiguration";
    private Metadata metadata;
    private RouteSpec spec;
}
