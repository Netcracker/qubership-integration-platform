package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteMatch {
    private HTTPPathMatch path;
}
