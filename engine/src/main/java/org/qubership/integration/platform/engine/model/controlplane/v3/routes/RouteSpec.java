package org.qubership.integration.platform.engine.model.controlplane.v3.routes;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RouteSpec {
    private List<String> gateways;
    private List<VirtualService> virtualServices;
}
