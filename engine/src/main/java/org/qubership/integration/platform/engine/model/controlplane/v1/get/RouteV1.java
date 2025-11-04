package org.qubership.integration.platform.engine.model.controlplane.v1.get;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteV1 {
    private String uuid;
    private RouteMatcherV1 matcher;
    private ActionV1 action;
}
