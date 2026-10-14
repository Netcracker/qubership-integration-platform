package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteSpec {
    private List<ParentReference> parentRefs;
    private List<HTTPRouteRule> rules;
}
