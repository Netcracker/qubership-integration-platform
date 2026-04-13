package org.qubership.integration.platform.engine.controlplane.rest.model.v1.get;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteConfigurationResponse {
    private String nodeGroup;
    private List<VirtualHost> virtualHosts;
}
