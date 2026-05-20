package org.qubership.integration.platform.engine.model.controlplane.v2.post;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RouteRegistrationItem {
    private String cluster;
    private List<PostRouteEntry> routes;
    private String endpoint;
    @Builder.Default
    private Boolean allowed = Boolean.TRUE;
    private String namespace;
    private String version;
}
