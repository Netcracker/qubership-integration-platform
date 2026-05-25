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
public class RouteMatcherV3 {
    private String prefix;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String path;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String regExp;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<HeaderMatcher> headers;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<HeaderDefinition> addHeaders;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> removeHeaders;
}
