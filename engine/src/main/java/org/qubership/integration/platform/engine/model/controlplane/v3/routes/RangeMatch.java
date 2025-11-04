package org.qubership.integration.platform.engine.model.controlplane.v3.routes;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RangeMatch {
    private Long start;
    private Long end;
}
