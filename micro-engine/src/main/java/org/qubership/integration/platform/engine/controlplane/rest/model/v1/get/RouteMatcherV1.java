package org.qubership.integration.platform.engine.controlplane.rest.model.v1.get;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteMatcherV1 {
    private String prefix;
    private String regExp;
}
