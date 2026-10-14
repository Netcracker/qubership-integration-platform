package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ParentReference {
    private String group;
    private String kind;
    private String name;
}
