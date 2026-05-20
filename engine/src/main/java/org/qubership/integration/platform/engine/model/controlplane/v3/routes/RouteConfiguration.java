package org.qubership.integration.platform.engine.model.controlplane.v3.routes;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RouteConfiguration {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String version;

    private List<RouteV3> routes;
}
