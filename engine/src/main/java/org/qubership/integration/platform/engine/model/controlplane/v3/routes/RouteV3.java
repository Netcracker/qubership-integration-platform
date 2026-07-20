package org.qubership.integration.platform.engine.model.controlplane.v3.routes;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RouteV3 {
    private Destination destination;
    private List<Rule> rules;
}
