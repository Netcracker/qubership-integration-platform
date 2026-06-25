package org.qubership.integration.platform.camelk.model.routes;

import lombok.*;
import org.springframework.http.HttpMethod;

import java.util.Set;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ElementRoute {
    private String path;
    private Set<HttpMethod> methods;
    private boolean isExternal;
    private boolean isPrivate;
    private long connectionTimeout;
}
