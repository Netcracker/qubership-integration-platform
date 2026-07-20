package org.qubership.integration.platform.engine.model.controlplane.v1.get;

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
