package org.qubership.integration.platform.engine.camel.context.propagation;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.context.propagation.core.contexts.SerializableDataContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;




@Slf4j
@Component
public class ContextPropsProvider {

    public Set<String> getDownstreamHeaders() {
        Set<String> headers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        headers.addAll(getDownstreamHeadersSafe());
        return headers;
    }

    private Set<String> getDownstreamHeadersSafe() {
        return ContextManager.getAll()
                .stream()
                .filter(SerializableDataContext.class::isInstance)
                .map(SerializableDataContext.class::cast)
                .map(context -> {
                    try {
                        return context.getSerializableContextData();
                    } catch (Exception ex) {
                        log.warn("Failed to get serializable context data", ex);
                        return Collections.<String, Object>emptyMap();
                    }
                })
                .filter(Objects::nonNull)
                .flatMap(map -> map.keySet().stream())
                .collect(Collectors.toSet());
    }
}
