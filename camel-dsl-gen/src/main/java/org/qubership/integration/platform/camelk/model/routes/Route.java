package org.qubership.integration.platform.camelk.model.routes;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
public class Route {
    private String id;
    private String path;
    private String gatewayPrefix;
    private String variableName;
    private RouteType type;
    @Builder.Default
    private Long connectTimeout = 120000L;
}
