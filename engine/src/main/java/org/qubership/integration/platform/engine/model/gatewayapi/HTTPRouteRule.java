package org.qubership.integration.platform.engine.model.gatewayapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteRule {
    private List<HTTPRouteMatch> matches;
    private List<HTTPRouteFilter> filters;
    private List<HTTPBackendRef> backendRefs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private HTTPRouteTimeouts timeouts;
}
