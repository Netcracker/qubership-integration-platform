package org.qubership.integration.platform.engine.controlplane.rest.model.v3.post;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HeaderDefinition {
    private String name;
    private String value;
}
