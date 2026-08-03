package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPBackendRef {
    private String group;
    private String kind;
    private String name;
    private Integer port;
    private Integer weight;
}
