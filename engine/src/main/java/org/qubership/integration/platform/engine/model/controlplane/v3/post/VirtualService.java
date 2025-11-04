package org.qubership.integration.platform.engine.model.controlplane.v3.post;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class VirtualService {
    private String name;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> hosts;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<HeaderDefinition> addHeaders;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> removeHeaders;

    private RouteConfiguration routeConfiguration;
}
