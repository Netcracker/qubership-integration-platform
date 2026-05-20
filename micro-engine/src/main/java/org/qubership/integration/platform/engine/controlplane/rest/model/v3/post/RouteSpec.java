package org.qubership.integration.platform.engine.controlplane.rest.model.v3.post;

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
