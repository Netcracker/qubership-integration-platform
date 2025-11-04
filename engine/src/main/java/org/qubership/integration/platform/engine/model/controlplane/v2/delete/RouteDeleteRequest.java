package org.qubership.integration.platform.engine.model.controlplane.v2.delete;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class RouteDeleteRequest {
    private List<RouteDeleteItem> routes;
    private String namespace;
    private String version;
}
