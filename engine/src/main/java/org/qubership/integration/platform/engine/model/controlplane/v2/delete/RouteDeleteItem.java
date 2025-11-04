package org.qubership.integration.platform.engine.model.controlplane.v2.delete;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class RouteDeleteItem {
    private String prefix;
}
