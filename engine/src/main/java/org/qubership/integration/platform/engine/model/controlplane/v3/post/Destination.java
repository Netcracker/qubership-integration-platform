package org.qubership.integration.platform.engine.model.controlplane.v3.post;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class Destination {
    private String cluster;
    private String endpoint;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String tlsConfigName;
}
