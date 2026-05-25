package org.qubership.integration.platform.engine.model.controlplane.v1.get;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualHost {
    private String name;
    private List<RouteV1> routes;
}
