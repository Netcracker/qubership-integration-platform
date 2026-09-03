package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteFilter {
    private String type;
    private HTTPUrlRewriteFilter urlRewrite;
}
