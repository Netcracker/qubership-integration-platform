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
public class Rule {

    private RouteMatcherV3 match;

    private Boolean allowed;

    private String prefixRewrite;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<HeaderDefinition> addHeaders;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> removeHeaders;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long timeout;
}
